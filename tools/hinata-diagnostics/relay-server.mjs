import crypto from "node:crypto";
import fs from "node:fs/promises";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { createAicRelayMessage, parseClientHello } from "./relay-protocol.mjs";

const HOST = "127.0.0.1";
const PORT = Number.parseInt(process.env.HINATA_DIAGNOSTICS_PORT ?? "8765", 10);
const ROOT = path.dirname(fileURLToPath(import.meta.url));
const MAX_BODY_BYTES = 4096;
const HEARTBEAT_INTERVAL_MS = readDuration("HINATA_RELAY_HEARTBEAT_MS", 5000);
const HEARTBEAT_TIMEOUT_MS = Math.max(
  readDuration("HINATA_RELAY_TIMEOUT_MS", 15000),
  HEARTBEAT_INTERVAL_MS * 2,
);
const MIME_TYPES = new Map([
  [".css", "text/css; charset=utf-8"],
  [".html", "text/html; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".mjs", "text/javascript; charset=utf-8"],
]);

let gameClient = null;

function readDuration(name, fallback) {
  const value = Number.parseInt(process.env[name] ?? "", 10);
  return Number.isSafeInteger(value) && value >= 50 ? value : fallback;
}

function clientConnected(client = gameClient) {
  return Boolean(
    client?.ready &&
    client.socket.writable &&
    !client.socket.destroyed &&
    Date.now() - client.lastPongAt <= HEARTBEAT_TIMEOUT_MS,
  );
}

function sendJson(response, status, value) {
  response.writeHead(status, {
    "Cache-Control": "no-store",
    "Content-Type": "application/json; charset=utf-8",
    "X-Content-Type-Options": "nosniff",
  });
  response.end(JSON.stringify(value));
}

function parseRequestUrl(request) {
  try {
    return new URL(request.url, `http://${HOST}:${PORT}`);
  } catch {
    return null;
  }
}

function relayStatus() {
  return {
    gameConnected: clientConnected(),
    protocol: gameClient?.ready ? gameClient.protocol : null,
    protocolVersion: gameClient?.ready ? gameClient.protocolVersion : null,
    clientVersion: gameClient?.ready ? gameClient.clientVersion : null,
    serverUrl: `ws://${HOST}:${PORT}/game`,
  };
}

function originAllowed(request) {
  const origin = request.headers.origin;
  return origin === `http://${HOST}:${PORT}` || origin === `http://localhost:${PORT}`;
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new RangeError("Request body is too large");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function safeStaticPath(urlPath) {
  let requested;
  try {
    requested = urlPath === "/" ? "/index.html" : decodeURIComponent(urlPath);
  } catch {
    return null;
  }
  const resolved = path.resolve(ROOT, `.${requested}`);
  const relative = path.relative(ROOT, resolved);
  if (relative.startsWith("..") || path.isAbsolute(relative)) return null;
  return resolved;
}

async function serveStatic(request, response, pathname) {
  const filePath = safeStaticPath(pathname);
  const mimeType = filePath ? MIME_TYPES.get(path.extname(filePath).toLowerCase()) : null;
  if (!filePath || !mimeType) {
    response.writeHead(404).end();
    return;
  }

  try {
    const data = await fs.readFile(filePath);
    response.writeHead(200, {
      "Cache-Control": "no-store",
      "Content-Security-Policy": "default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; base-uri 'none'; frame-ancestors 'none'",
      "Content-Type": mimeType,
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "DENY",
    });
    response.end(request.method === "HEAD" ? undefined : data);
  } catch (error) {
    response.writeHead(error?.code === "ENOENT" ? 404 : 500).end();
  }
}

function encodeFrame(opcode, payload = Buffer.alloc(0)) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
  const header = [0x80 | opcode];
  if (body.length < 126) {
    header.push(body.length);
  } else if (body.length <= 0xffff) {
    header.push(126, body.length >> 8, body.length & 0xff);
  } else {
    throw new RangeError("WebSocket payload is too large");
  }
  return Buffer.concat([Buffer.from(header), body]);
}

function closeSocket(socket, code, reason) {
  const reasonBytes = Buffer.from(reason);
  const payload = Buffer.alloc(2 + reasonBytes.length);
  payload.writeUInt16BE(code, 0);
  reasonBytes.copy(payload, 2);
  socket.end(encodeFrame(0x08, payload));
}

function writeFrame(socket, frame) {
  return new Promise((resolve, reject) => {
    socket.write(frame, (error) => {
      if (error) reject(error);
      else resolve();
    });
  });
}

function handleTextFrame(client, text) {
  if (client.ready) return;
  let hello;
  try {
    hello = parseClientHello(text);
  } catch {
    hello = null;
  }
  if (!hello) {
    closeSocket(client.socket, 1002, "Expected CLIENT_HELLO v2");
    return;
  }
  client.ready = true;
  client.protocol = hello.protocol;
  client.protocolVersion = hello.version;
  process.stdout.write(`HINATA DLL connected (client ${client.clientVersion || "unknown"})\n`);
}

function consumeFrames(client, chunk) {
  client.lastActivityAt = Date.now();
  client.buffer = Buffer.concat([client.buffer, chunk]);
  while (client.buffer.length >= 2) {
    const first = client.buffer[0];
    const second = client.buffer[1];
    if ((first & 0x80) === 0) {
      closeSocket(client.socket, 1002, "Fragmented frames are not supported");
      return;
    }

    const opcode = first & 0x0f;
    const masked = (second & 0x80) !== 0;
    let length = second & 0x7f;
    let offset = 2;
    if (!masked) {
      closeSocket(client.socket, 1002, "Client frames must be masked");
      return;
    }
    if (length === 126) {
      if (client.buffer.length < 4) return;
      length = client.buffer.readUInt16BE(2);
      offset = 4;
    } else if (length === 127) {
      if (client.buffer.length < 10) return;
      const longLength = client.buffer.readBigUInt64BE(2);
      if (longLength > BigInt(MAX_BODY_BYTES)) {
        closeSocket(client.socket, 1009, "Frame is too large");
        return;
      }
      length = Number(longLength);
      offset = 10;
    }
    if (length > MAX_BODY_BYTES) {
      closeSocket(client.socket, 1009, "Frame is too large");
      return;
    }
    if (client.buffer.length < offset + 4 + length) return;

    const mask = client.buffer.subarray(offset, offset + 4);
    offset += 4;
    const payload = Buffer.from(client.buffer.subarray(offset, offset + length));
    client.buffer = client.buffer.subarray(offset + length);
    for (let index = 0; index < payload.length; index += 1) {
      payload[index] ^= mask[index % 4];
    }

    if (opcode === 0x01) handleTextFrame(client, payload.toString("utf8"));
    else if (opcode === 0x08) client.socket.end(encodeFrame(0x08, payload));
    else if (opcode === 0x09) client.socket.write(encodeFrame(0x0a, payload));
    else if (opcode === 0x0a) client.lastPongAt = Date.now();
    else {
      closeSocket(client.socket, 1003, "Unsupported frame type");
      return;
    }
  }
}

const server = http.createServer(async (request, response) => {
  const requestUrl = parseRequestUrl(request);
  if (!requestUrl) {
    response.writeHead(400).end();
    return;
  }
  if (request.method === "GET" && requestUrl.pathname === "/api/relay/status") {
    sendJson(response, 200, relayStatus());
    return;
  }

  if (request.method === "POST" && requestUrl.pathname === "/api/relay/card") {
    if (!originAllowed(request) || !request.headers["content-type"]?.startsWith("application/json")) {
      sendJson(response, 403, { error: "Request origin or content type is not allowed" });
      return;
    }
    const client = gameClient;
    if (!clientConnected(client)) {
      sendJson(response, 409, { error: "HINATA DLL is not connected" });
      return;
    }
    let message;
    try {
      message = createAicRelayMessage(await readJson(request));
    } catch (error) {
      sendJson(response, error instanceof RangeError ? 413 : 400, {
        error: error instanceof Error ? error.message : "Invalid request",
      });
      return;
    }
    if (gameClient !== client || !clientConnected(client)) {
      sendJson(response, 409, { error: "HINATA DLL disconnected before handoff" });
      return;
    }
    try {
      await writeFrame(client.socket, encodeFrame(0x01, JSON.stringify(message)));
      sendJson(response, 202, { accepted: true });
    } catch {
      if (gameClient === client) gameClient = null;
      client.socket.destroy();
      sendJson(response, 409, { error: "HINATA DLL disconnected during handoff" });
    }
    return;
  }

  if (request.method === "GET" || request.method === "HEAD") {
    await serveStatic(request, response, requestUrl.pathname);
    return;
  }
  response.writeHead(405, { Allow: "GET, HEAD, POST" }).end();
});

server.on("upgrade", (request, socket) => {
  const requestUrl = parseRequestUrl(request);
  const key = request.headers["sec-websocket-key"];
  if (request.headers.origin !== undefined) {
    socket.end("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");
    return;
  }
  if (
    !requestUrl ||
    request.method !== "GET" ||
    requestUrl.pathname !== "/game" ||
    requestUrl.searchParams.get("card_protocol") !== "2" ||
    !key ||
    request.headers.upgrade?.toLowerCase() !== "websocket" ||
    !request.headers.connection?.toLowerCase().split(/\s*,\s*/).includes("upgrade") ||
    request.headers["sec-websocket-version"] !== "13"
  ) {
    socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
    return;
  }

  if (gameClient && !gameClient.socket.destroyed) {
    closeSocket(gameClient.socket, 1012, "Replaced by a new client");
  }
  const accept = crypto
    .createHash("sha1")
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\n" +
      "Connection: Upgrade\r\n" +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );

  const client = {
    socket,
    buffer: Buffer.alloc(0),
    ready: false,
    protocol: null,
    protocolVersion: null,
    clientVersion: requestUrl.searchParams.get("client_version"),
    lastActivityAt: Date.now(),
    lastPongAt: Date.now(),
  };
  gameClient = client;
  socket.setKeepAlive(true, HEARTBEAT_INTERVAL_MS);
  socket.setNoDelay(true);
  socket.on("data", (chunk) => consumeFrames(client, chunk));
  socket.on("error", () => {});
  socket.on("close", () => {
    if (gameClient === client) gameClient = null;
    process.stdout.write("HINATA DLL disconnected\n");
  });
});

const heartbeatTimer = setInterval(() => {
  const client = gameClient;
  if (!client?.ready || client.socket.destroyed) return;
  if (!clientConnected(client)) {
    if (gameClient === client) gameClient = null;
    client.socket.destroy();
    process.stdout.write("HINATA DLL heartbeat timed out\n");
    return;
  }
  client.socket.write(encodeFrame(0x09, Buffer.from(String(Date.now()))), (error) => {
    if (!error) return;
    if (gameClient === client) gameClient = null;
    client.socket.destroy();
  });
}, HEARTBEAT_INTERVAL_MS);
heartbeatTimer.unref();

server.listen(PORT, HOST, () => {
  process.stdout.write(`HINATA diagnostics: http://${HOST}:${PORT}/\n`);
  process.stdout.write(`AimeIO serverUrl: ws://${HOST}:${PORT}/game\n`);
  process.stdout.write("The relay is loopback-only and does not persist card data.\n");
});

function shutdown() {
  clearInterval(heartbeatTimer);
  if (gameClient && !gameClient.socket.destroyed) closeSocket(gameClient.socket, 1001, "Server stopped");
  server.close(() => process.exit(0));
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
