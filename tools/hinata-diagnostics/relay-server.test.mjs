import assert from "node:assert/strict";
import crypto from "node:crypto";
import http from "node:http";
import net from "node:net";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const HELLO = JSON.stringify({
  action: "CLIENT_HELLO",
  body: { protocol: "aimeio-remote", version: 2 },
});

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function reservePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const { port } = server.address();
  await new Promise((resolve) => server.close(resolve));
  return port;
}

async function waitUntil(predicate, timeout = 2500) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await predicate();
    if (value) return value;
    await delay(25);
  }
  throw new Error("Timed out waiting for condition");
}

function requestUpgrade(port, origin) {
  return new Promise((resolve, reject) => {
    const headers = {
      Connection: "Upgrade",
      Upgrade: "websocket",
      "Sec-WebSocket-Key": crypto.randomBytes(16).toString("base64"),
      "Sec-WebSocket-Version": "13",
    };
    if (origin) headers.Origin = origin;

    const request = http.request({
      host: "127.0.0.1",
      port,
      path: "/game?card_protocol=2&client_version=test",
      headers,
    });
    request.once("error", reject);
    request.once("upgrade", (response, socket, head) => {
      resolve({ upgraded: true, statusCode: response.statusCode, socket, head });
    });
    request.once("response", (response) => {
      response.resume();
      resolve({ upgraded: false, statusCode: response.statusCode });
    });
    request.end();
  });
}

function encodeClientFrame(opcode, value = "") {
  const payload = Buffer.isBuffer(value) ? value : Buffer.from(value);
  assert.ok(payload.length < 126, "test client uses only short frames");
  const mask = crypto.randomBytes(4);
  const body = Buffer.from(payload);
  for (let index = 0; index < body.length; index += 1) body[index] ^= mask[index % 4];
  return Buffer.concat([Buffer.from([0x80 | opcode, 0x80 | body.length]), mask, body]);
}

function parseServerFrame(state) {
  if (state.buffer.length < 2) return null;
  const first = state.buffer[0];
  const second = state.buffer[1];
  assert.equal(first & 0x80, 0x80, "server frames must not be fragmented");
  assert.equal(second & 0x80, 0, "server frames must not be masked");

  let length = second & 0x7f;
  let offset = 2;
  if (length === 126) {
    if (state.buffer.length < 4) return null;
    length = state.buffer.readUInt16BE(2);
    offset = 4;
  } else if (length === 127) {
    if (state.buffer.length < 10) return null;
    length = Number(state.buffer.readBigUInt64BE(2));
    offset = 10;
  }
  if (state.buffer.length < offset + length) return null;

  const frame = {
    opcode: first & 0x0f,
    payload: Buffer.from(state.buffer.subarray(offset, offset + length)),
  };
  state.buffer = state.buffer.subarray(offset + length);
  return frame;
}

function readChunk(socket, timeout = 1000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(new Error("Timed out waiting for WebSocket frame")), timeout);
    const onData = (chunk) => finish(null, chunk);
    const onClose = () => finish(new Error("WebSocket closed"));
    const onError = (error) => finish(error);
    function finish(error, chunk) {
      clearTimeout(timer);
      socket.off("data", onData);
      socket.off("close", onClose);
      socket.off("error", onError);
      if (error) reject(error);
      else resolve(chunk);
    }
    socket.once("data", onData);
    socket.once("close", onClose);
    socket.once("error", onError);
  });
}

async function nextServerFrame(socket, state, timeout = 1000) {
  for (;;) {
    const frame = parseServerFrame(state);
    if (frame) return frame;
    state.buffer = Buffer.concat([state.buffer, await readChunk(socket, timeout)]);
  }
}

async function relayStatus(port) {
  const response = await fetch(`http://127.0.0.1:${port}/api/relay/status`);
  assert.equal(response.status, 200);
  return response.json();
}

async function stopChild(child) {
  if (child.exitCode !== null) return;
  child.kill("SIGTERM");
  await Promise.race([
    new Promise((resolve) => child.once("exit", resolve)),
    delay(1000).then(() => child.kill()),
  ]);
}

test("serves a guarded relay, forwards AIC V2, and expires stale clients", async () => {
  const port = await reservePort();
  const child = spawn(process.execPath, ["relay-server.mjs"], {
    cwd: ROOT,
    env: {
      ...process.env,
      HINATA_DIAGNOSTICS_PORT: String(port),
      HINATA_RELAY_HEARTBEAT_MS: "75",
      HINATA_RELAY_TIMEOUT_MS: "225",
    },
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  let output = "";
  child.stdout.on("data", (chunk) => { output += chunk; });
  child.stderr.on("data", (chunk) => { output += chunk; });

  let firstSocket;
  let secondSocket;
  try {
    await waitUntil(() => output.includes("HINATA diagnostics:"));
    assert.equal((await relayStatus(port)).gameConnected, false);

    const browserClient = await requestUpgrade(port, "https://example.test");
    assert.equal(browserClient.upgraded, false);
    assert.equal(browserClient.statusCode, 403);

    const firstClient = await requestUpgrade(port);
    assert.equal(firstClient.upgraded, true);
    firstSocket = firstClient.socket;
    firstSocket.write(encodeClientFrame(0x01, HELLO));
    await waitUntil(async () => (await relayStatus(port)).gameConnected);
    await waitUntil(async () => !(await relayStatus(port)).gameConnected, 1500);

    const secondClient = await requestUpgrade(port);
    assert.equal(secondClient.upgraded, true);
    secondSocket = secondClient.socket;
    const frameState = { buffer: secondClient.head };
    secondSocket.write(encodeClientFrame(0x01, HELLO));
    await waitUntil(async () => (await relayStatus(port)).gameConnected);

    const denied = await fetch(`http://127.0.0.1:${port}/api/relay/card`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    assert.equal(denied.status, 403);

    const card = {
      id: "02FE001145141919",
      pmm: "00F1000000014300",
      accessCode: "00010203040506070809",
    };
    const accepted = await fetch(`http://127.0.0.1:${port}/api/relay/card`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Origin: `http://127.0.0.1:${port}`,
      },
      body: JSON.stringify(card),
    });
    assert.equal(accepted.status, 202);

    let relayMessage;
    while (!relayMessage) {
      const frame = await nextServerFrame(secondSocket, frameState);
      if (frame.opcode === 0x09) secondSocket.write(encodeClientFrame(0x0a, frame.payload));
      if (frame.opcode === 0x01) relayMessage = JSON.parse(frame.payload.toString("utf8"));
    }
    assert.equal(relayMessage.action, "SET_CARD_V2");
    assert.equal(relayMessage.body.card.accessCode, card.accessCode);

    let keepAlive = true;
    const heartbeatPump = (async () => {
      while (keepAlive && !secondSocket.destroyed) {
        try {
          const frame = await nextServerFrame(secondSocket, frameState, 150);
          if (frame.opcode === 0x09) secondSocket.write(encodeClientFrame(0x0a, frame.payload));
        } catch (error) {
          if (!keepAlive || secondSocket.destroyed) return;
          if (!String(error).includes("Timed out")) throw error;
        }
      }
    })();
    await delay(450);
    assert.equal((await relayStatus(port)).gameConnected, true);
    keepAlive = false;
    secondSocket.destroy();
    await heartbeatPump;
    await waitUntil(async () => !(await relayStatus(port)).gameConnected);
  } finally {
    firstSocket?.destroy();
    secondSocket?.destroy();
    await stopChild(child);
  }
});
