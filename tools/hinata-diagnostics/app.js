import {
  PN532_COMMAND_IN_DATA_EXCHANGE,
  PN532_COMMAND_IN_LIST_PASSIVE_TARGET,
  PN532_COMMAND_IN_RELEASE,
  PN532_DIRECTION_CHIP_TO_HOST,
  PN532_DIRECTION_HOST_TO_CHIP,
  buildFelicaPollPayload,
  buildFelicaReadWithoutEncryption,
  buildMifareAuthenticate,
  buildMifarePollPayload,
  buildMifareReadBlock,
  buildPn532Frame,
  classifyDiagnostic,
  formatHex,
  isPn532Ack,
  normalizeSystemCode,
  parseFelicaPollResponse,
  parseFelicaReadResponse,
  parseMifareClassic1kDump,
  parseMifareDataExchange,
  parseMifarePollResponse,
  parsePn532Frame,
  trimPn532Frame,
} from "./protocol.mjs?v=20260907-release1";

const HINATA_VENDOR_ID = 0xf822;
const HINATA_REPORT_ID = 1;
const HINATA_PN532_HEADER = 0xe2;
const PROBE_CODES = ["FFFF", "88B4", "4000"];

const elements = {
  support: document.querySelector("#browser-support"),
  connection: document.querySelector("#connection-state"),
  device: document.querySelector("#device-name"),
  connect: document.querySelector("#connect-button"),
  run: document.querySelector("#run-button"),
  customCode: document.querySelector("#custom-code"),
  customRun: document.querySelector("#custom-run-button"),
  verdict: document.querySelector("#verdict"),
  verdictDetail: document.querySelector("#verdict-detail"),
  log: document.querySelector("#raw-log"),
  logCount: document.querySelector("#log-count"),
  clearLog: document.querySelector("#clear-log-button"),
  exportLog: document.querySelector("#export-button"),
  mifareDump: document.querySelector("#mifare-dump"),
  mifareRun: document.querySelector("#mifare-run-button"),
  mifareStatus: document.querySelector("#mifare-status"),
  relayServer: document.querySelector("#relay-server-state"),
  relayGame: document.querySelector("#relay-game-state"),
  relayCard: document.querySelector("#relay-card-state"),
  relayUrl: document.querySelector("#relay-url"),
  relayCopy: document.querySelector("#relay-copy-button"),
  relayForm: document.querySelector("#relay-form"),
  relayAccessCode: document.querySelector("#relay-access-code"),
  relaySend: document.querySelector("#relay-send-button"),
  relayResult: document.querySelector("#relay-result"),
};

let device = null;
let activeExchange = null;
let busy = false;
let logSequence = 0;
let logs = [];
let results = {};
let mifareDump = null;
let mifareResult = null;
let relayStatus = {
  reachable: false,
  gameConnected: false,
  serverUrl: "ws://127.0.0.1:8765/game",
};

function setText(element, value) {
  element.textContent = value;
}

function timestamp() {
  return new Date().toISOString();
}

function describeError(error) {
  const message = error instanceof Error ? error.message : String(error);
  if (/access|open|claim|lock|busy|network/i.test(message)) {
    return `${message}；请关闭 HINATA Client 和游戏后重试`;
  }
  return message;
}

function setBusy(value) {
  busy = value;
  elements.connect.disabled = value || !("hid" in navigator);
  elements.run.disabled = value || !device?.opened;
  elements.customRun.disabled = value || !device?.opened;
  elements.customCode.disabled = value;
  elements.mifareRun.disabled = value || !device?.opened || !mifareDump;
  elements.mifareDump.disabled = value;
  elements.connect.textContent = device?.opened ? "断开设备" : "连接 HINATA";
  updateRelayControls();
}

function addLog(direction, label, bytes, note = "") {
  logs.push({
    sequence: ++logSequence,
    time: timestamp(),
    direction,
    label,
    hex: formatHex(bytes, " "),
    note,
  });
  if (logs.length > 300) logs = logs.slice(-300);
  renderLog();
}

function renderLog() {
  elements.log.replaceChildren();
  if (logs.length === 0) {
    const empty = document.createElement("div");
    empty.className = "log-empty";
    empty.textContent = "暂无报文";
    elements.log.append(empty);
  } else {
    for (const entry of logs) {
      const row = document.createElement("div");
      row.className = "log-row";

      const meta = document.createElement("span");
      meta.className = `log-direction ${entry.direction.toLowerCase()}`;
      meta.textContent = entry.direction;

      const body = document.createElement("code");
      body.textContent = `${entry.label}  ${entry.hex}${entry.note ? `  ${entry.note}` : ""}`;

      row.append(meta, body);
      elements.log.append(row);
    }
    elements.log.scrollTop = elements.log.scrollHeight;
  }
  setText(elements.logCount, String(logs.length));
  elements.exportLog.disabled =
    logs.length === 0 && Object.keys(results).length === 0 && !mifareResult;
  elements.clearLog.disabled = logs.length === 0;
}

function completeExchange(frame) {
  const exchange = activeExchange;
  if (!exchange) return;

  let packet;
  try {
    packet = parsePn532Frame(frame);
  } catch (error) {
    cancelExchange(error);
    return;
  }
  if (packet.direction !== PN532_DIRECTION_CHIP_TO_HOST) {
    cancelExchange(new Error("Unexpected PN532 response direction"));
    return;
  }
  if (packet.command === 0x7f) {
    cancelExchange(new Error(`PN532 error frame: ${formatHex(packet.payload, " ")}`));
    return;
  }
  if (packet.command !== exchange.expectedCommand) {
    addLog(
      "INFO",
      "IGNORED",
      exchange.sensitive ? [] : frame,
      exchange.sensitive ? "[REDACTED]" : `expected ${formatHex([exchange.expectedCommand])}`,
    );
    return;
  }

  activeExchange = null;
  clearTimeout(exchange.timer);
  exchange.resolve(packet);
}

function onInputReport(event) {
  if (event.reportId === 2) return;
  const data = new Uint8Array(event.data.buffer, event.data.byteOffset, event.data.byteLength);
  if (data[0] !== HINATA_PN532_HEADER) return;

  let frame;
  try {
    frame = trimPn532Frame(data.slice(1));
  } catch (error) {
    addLog(
      "RX",
      "INVALID",
      activeExchange?.sensitive ? [] : data,
      activeExchange?.sensitive ? "[REDACTED]" : describeError(error),
    );
    cancelExchange(error);
    return;
  }

  if (isPn532Ack(frame)) {
    addLog("RX", "PN532 ACK", frame);
    return;
  }
  addLog(
    "RX",
    "PN532 DATA",
    activeExchange?.sensitive ? [] : frame,
    activeExchange?.sensitive ? "[REDACTED]" : "",
  );
  completeExchange(frame);
}

function cancelExchange(error) {
  if (!activeExchange) return;
  const exchange = activeExchange;
  activeExchange = null;
  clearTimeout(exchange.timer);
  exchange.reject(error);
}

async function exchangePn532(command, payload, label, timeoutMs = 2600, options = {}) {
  if (!device?.opened) throw new Error("HINATA is not connected");
  if (activeExchange) throw new Error("Another PN532 exchange is active");

  const frame = buildPn532Frame(PN532_DIRECTION_HOST_TO_CHIP, command, payload);
  const response = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (activeExchange?.timer === timer) activeExchange = null;
      reject(new Error(`${label} timed out`));
    }, timeoutMs);
    activeExchange = {
      expectedCommand: (command + 1) & 0xff,
      resolve,
      reject,
      timer,
      sensitive: options.sensitive === true,
    };
  });

  addLog(
    "TX",
    label,
    options.sensitive ? [] : frame,
    options.sensitive ? "[REDACTED]" : "",
  );
  try {
    await device.sendReport(
      HINATA_REPORT_ID,
      Uint8Array.from([HINATA_PN532_HEADER, ...frame]),
    );
  } catch (error) {
    cancelExchange(error);
    throw error;
  }
  return response;
}

async function connectDevice() {
  if (!("hid" in navigator)) throw new Error("当前浏览器不支持 WebHID");
  let devices = await navigator.hid.getDevices();
  devices = devices.filter((candidate) => candidate.vendorId === HINATA_VENDOR_ID);
  if (devices.length === 0) {
    devices = await navigator.hid.requestDevice({ filters: [{ vendorId: HINATA_VENDOR_ID }] });
  }
  if (devices.length === 0) return;

  device = devices[0];
  device.addEventListener("inputreport", onInputReport);
  if (!device.opened) await device.open();
  setText(elements.connection, "已连接");
  elements.connection.dataset.state = "connected";
  setText(elements.relayResult, "-");
  setText(elements.device, `${device.productName || "HINATA"} · PID ${formatHex([
    (device.productId >> 8) & 0xff,
    device.productId & 0xff,
  ])}`);
}

async function disconnectDevice() {
  if (device?.opened && !activeExchange) await releaseTargetsBestEffort();
  cancelExchange(new Error("Device disconnected"));
  if (device) {
    device.removeEventListener("inputreport", onInputReport);
    if (device.opened) await device.close();
  }
  device = null;
  setText(elements.connection, "未连接");
  elements.connection.dataset.state = "disconnected";
  setText(elements.device, "未选择设备");
  if (findRelayCandidate()) {
    setText(elements.relayResult, "已释放设备；请移开手机，再启动游戏");
  }
}

async function releaseTargets() {
  const response = await exchangePn532(
    PN532_COMMAND_IN_RELEASE,
    [0x00],
    "RELEASE ALL",
    1200,
  );
  const status = response.payload[0];
  if (!Number.isInteger(status)) throw new Error("InRelease returned no status");
  if (status !== 0x00) {
    throw new Error(`InRelease failed with status ${formatHex([status])}`);
  }
}

async function releaseTargetsBestEffort() {
  if (!device?.opened || activeExchange) return { state: "skipped" };
  try {
    await releaseTargets();
    return { state: "released" };
  } catch (error) {
    const detail = describeError(error);
    addLog("INFO", "RELEASE FAILED", [], detail);
    return { state: "error", detail };
  }
}

async function pollSystemCode(systemCode) {
  const code = normalizeSystemCode(systemCode);
  const response = await exchangePn532(
    PN532_COMMAND_IN_LIST_PASSIVE_TARGET,
    buildFelicaPollPayload(code),
    `POLL ${code}`,
  );
  return parseFelicaPollResponse(response.payload);
}

async function readAimeBlockGroup(target, blocks) {
  const command = buildFelicaReadWithoutEncryption(target.idm, blocks);
  const response = await exchangePn532(
    PN532_COMMAND_IN_DATA_EXCHANGE,
    [target.targetNumber, ...command],
    `READ 000B:${blocks.map((block) => formatHex([block])).join(",")}`,
  );
  const parsed = parseFelicaReadResponse(response.payload);
  if (parsed.statusFlag1 !== 0 || parsed.statusFlag2 !== 0) {
    throw new Error(
      `FeliCa status ${formatHex([parsed.statusFlag1, parsed.statusFlag2], " ")}`,
    );
  }
  if (parsed.blockData.length !== blocks.length) {
    throw new Error(`Expected ${blocks.length} FeliCa blocks, received ${parsed.blockData.length}`);
  }
  return parsed.blockData;
}

async function readAimeBlocks(target) {
  // An E2 HID report carries only 63 PN532 bytes. Three blocks need 71 bytes,
  // so keep each response below the transport limit and merge them here.
  const firstPair = await readAimeBlockGroup(target, [0x00, 0x82]);
  const systemCodeBlock = await readAimeBlockGroup(target, [0x85]);
  return { blockData: [...firstPair, ...systemCodeBlock] };
}

function bytesEqual(left, right) {
  const a = Array.from(left ?? []);
  const b = Array.from(right ?? []);
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

function maskUid(value) {
  const hex = formatHex(value);
  if (hex.length <= 4) return "••••";
  return `${hex.slice(0, 4)}••${hex.slice(-4)}`;
}

async function pollMifareTarget() {
  const response = await exchangePn532(
    PN532_COMMAND_IN_LIST_PASSIVE_TARGET,
    buildMifarePollPayload(),
    "POLL NFC-A",
    2600,
    { sensitive: true },
  );
  return parseMifarePollResponse(response.payload);
}

async function exchangeMifare(target, command, label) {
  const response = await exchangePn532(
    PN532_COMMAND_IN_DATA_EXCHANGE,
    [target.targetNumber, ...command],
    label,
    2600,
    { sensitive: true },
  );
  return parseMifareDataExchange(response.payload);
}

async function verifyMifareKey(keyType, key, expectedBlocks) {
  try {
    const target = await pollMifareTarget();
    if (!target) return { state: "no-target" };

    const authentication = await exchangeMifare(
      target,
      buildMifareAuthenticate(4, key, target.uid, keyType),
      `AUTH SECTOR 1 KEY ${keyType}`,
    );
    if (authentication.status !== 0x00) {
      return { state: "failed", status: formatHex([authentication.status]) };
    }

    const matches = [];
    for (let index = 0; index < expectedBlocks.length; index += 1) {
      const blockNumber = 4 + index;
      const read = await exchangeMifare(
        target,
        buildMifareReadBlock(blockNumber),
        `READ BLOCK ${blockNumber}`,
      );
      if (read.status !== 0x00 || read.data.length < 16) {
        return {
          state: "read-failed",
          status: formatHex([read.status]),
          blocksMatch: false,
        };
      }
      matches.push(bytesEqual(read.data.slice(0, 16), expectedBlocks[index]));
    }

    return { state: "authenticated", blocksMatch: matches.every(Boolean), matches };
  } finally {
    await releaseTargetsBestEffort();
  }
}

function mifareField(field, value) {
  const element = document.querySelector(`[data-mifare-field="${field}"]`);
  if (element) element.textContent = value;
}

function describeMifareAuth(result) {
  return {
    authenticated: result?.blocksMatch ? "认证成功，数据一致" : "认证成功，数据不同",
    failed: `认证失败${result?.status ? `（${result.status}）` : ""}`,
    "read-failed": `认证成功，读取失败${result?.status ? `（${result.status}）` : ""}`,
    "no-target": "未发现目标",
    pending: "检测中",
  }[result?.state] ?? "-";
}

function renderMifareResult() {
  const result = mifareResult;
  const status = elements.mifareStatus;
  status.dataset.state = result?.state ?? "idle";
  status.textContent = {
    pending: "检测中",
    found: "已读取",
    empty: "无目标",
    error: "错误",
    idle: "未检测",
  }[result?.state ?? "idle"];

  const sector = mifareDump?.sectors?.[1];
  mifareField("dump", mifareDump ? "MIFARE Classic 1K · 已加载" : "-");
  mifareField("expectedUid", mifareDump ? maskUid(mifareDump.uid) : "-");
  mifareField("dumpIntegrity", mifareDump
    ? `BCC ${mifareDump.bccValid ? "有效" : "无效"} · 访问位 ${sector?.accessValid ? "有效" : "无效"}`
    : "-");
  mifareField("uid", result?.target?.uidMasked ?? "-");
  mifareField("atqa", result?.target?.atqa ?? "-");
  mifareField("sak", result?.target?.sak ?? "-");
  mifareField("type", result?.target?.type ?? "-");
  mifareField("uidMatch", result?.uidMatches == null ? "-" : result.uidMatches ? "一致" : "不一致");
  mifareField("keyA", describeMifareAuth(result?.auth?.A));
  mifareField("keyB", describeMifareAuth(result?.auth?.B));
  mifareField("detail", result?.detail ?? "-");
}

async function runMifareDiagnostic() {
  if (!mifareDump) throw new Error("请先加载 1024 字节的 MIFARE Classic 1K dump");
  const expectedSector = mifareDump.sectors[1];
  mifareResult = { state: "pending" };
  renderMifareResult();
  setBusy(true);

  try {
    let target;
    try {
      target = await pollMifareTarget();
    } finally {
      await releaseTargetsBestEffort();
    }
    if (!target) {
      mifareResult = { state: "empty", detail: "PN532 返回 0 个 NFC-A 目标" };
      return;
    }

    const uidMatches = bytesEqual(target.uid, mifareDump.uid);
    mifareResult = {
      state: "found",
      target: {
        uidMasked: maskUid(target.uid),
        atqa: formatHex(target.atqa),
        sak: formatHex([target.sak]),
        type: target.type,
      },
      uidMatches,
      auth: { A: { state: "pending" }, B: { state: "pending" } },
    };
    renderMifareResult();

    mifareResult.auth.A = await verifyMifareKey("A", expectedSector.keyA, expectedSector.dataBlocks);
    renderMifareResult();
    mifareResult.auth.B = await verifyMifareKey("B", expectedSector.keyB, expectedSector.dataBlocks);

    const authenticated = [mifareResult.auth.A, mifareResult.auth.B]
      .filter((value) => value.state === "authenticated");
    const blocksMatch = authenticated.some((value) => value.blocksMatch);
    if (!uidMatches) {
      mifareResult.detail = "模拟卡 UID 与 dump 不一致";
    } else if (authenticated.length === 0) {
      mifareResult.detail = "UID 一致，但模拟卡不接受 dump 中的扇区 1 密钥";
    } else if (!blocksMatch) {
      mifareResult.detail = "扇区 1 可以认证，但数据块与 dump 不一致";
    } else {
      mifareResult.detail = "UID、扇区 1 认证和数据块均一致";
    }
  } catch (error) {
    mifareResult = { state: "error", detail: describeError(error) };
  } finally {
    renderMifareResult();
    renderLog();
    setBusy(false);
  }
}

function probeElement(code) {
  return document.querySelector(`[data-probe="${code}"]`);
}

function setField(card, field, value) {
  const element = card.querySelector(`[data-field="${field}"]`);
  if (element) element.textContent = value;
}

function renderProbe(code, result) {
  const card = probeElement(code);
  if (!card) return;
  const status = card.querySelector(".probe-status");
  status.dataset.state = result?.state ?? "idle";
  status.textContent = {
    pending: "检测中",
    found: "已响应",
    empty: "无目标",
    error: "错误",
    idle: "未检测",
  }[result?.state ?? "idle"];

  const target = result?.target;
  setField(card, "idm", target ? formatHex(target.idm) : "-");
  setField(card, "pmm", target ? formatHex(target.pmm) : "-");
  setField(
    card,
    "systems",
    target?.systemCodes?.length
      ? target.systemCodes.map((value) => value.toString(16).padStart(4, "0").toUpperCase()).join(", ")
      : "-",
  );
  setField(card, "block0", result?.read?.blocks?.["00"] ?? "-");
  setField(card, "block82", result?.read?.blocks?.["82"] ?? "-");
  setField(card, "block85", result?.read?.blocks?.["85"] ?? "-");
  setField(
    card,
    "detail",
    result?.detail ?? result?.read?.detail ?? (result?.read?.state === "read" ? "000B 读取成功" : "-"),
  );
  updateRelayControls();
}

function findRelayCandidate() {
  for (const code of ["4000", "FFFF", "88B4"]) {
    const result = results[code];
    const idm = result?.target?.idm;
    if (
      result?.state === "found" &&
      result?.read?.state === "read" &&
      idm &&
      formatHex(idm).startsWith("02FE")
    ) {
      return { code, result, target: result.target };
    }
  }
  return null;
}

function maskRelayIdm(idm) {
  const value = formatHex(idm);
  return `${value.slice(0, 4)}••••${value.slice(-4)}`;
}

function updateRelayControls() {
  const candidate = findRelayCandidate();
  const accessCodeValid = /^\d{20}$/.test(elements.relayAccessCode.value);

  setText(elements.relayServer, relayStatus.reachable ? "运行中" : "不可用");
  elements.relayServer.dataset.state = relayStatus.reachable ? "connected" : "error";
  setText(
    elements.relayGame,
    relayStatus.gameConnected
      ? `已连接${relayStatus.clientVersion ? ` · ${relayStatus.clientVersion}` : ""}`
      : "未连接",
  );
  elements.relayGame.dataset.state = relayStatus.gameConnected ? "connected" : "disconnected";
  setText(
    elements.relayCard,
    candidate ? `${candidate.code} · ${maskRelayIdm(candidate.target.idm)}` : "尚未扫描",
  );
  elements.relayCard.dataset.state = candidate ? "connected" : "disconnected";
  setText(elements.relayUrl, relayStatus.serverUrl);

  elements.relayCopy.disabled = !relayStatus.reachable;
  elements.relaySend.disabled =
    busy ||
    Boolean(device?.opened) ||
    !relayStatus.gameConnected ||
    !candidate ||
    !accessCodeValid;
}

async function refreshRelayStatus() {
  try {
    const response = await fetch("/api/relay/status", { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const status = await response.json();
    relayStatus = { ...status, reachable: true };
  } catch {
    relayStatus = {
      reachable: false,
      gameConnected: false,
      serverUrl: "ws://127.0.0.1:8765/game",
    };
  }
  updateRelayControls();
}

async function sendRelayCard() {
  const candidate = findRelayCandidate();
  if (!candidate) throw new Error("没有完成块读取的 02FE 候选卡");
  if (device?.opened) throw new Error("请先断开 HINATA，再启动游戏");
  if (!relayStatus.gameConnected) throw new Error("HINATA DLL 尚未连接本机中继");

  const accessCode = elements.relayAccessCode.value;
  if (!/^\d{20}$/.test(accessCode)) throw new Error("Access Code 必须是 20 位数字");
  const response = await fetch("/api/relay/card", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      id: formatHex(candidate.target.idm),
      pmm: formatHex(candidate.target.pmm),
      accessCode,
    }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error ?? `HTTP ${response.status}`);

  elements.relayAccessCode.value = "";
  setText(elements.relayResult, "已转发至 HINATA DLL");
  updateRelayControls();
}

function renderVerdict() {
  const verdict = classifyDiagnostic(results);
  const content = {
    "aic-read": ["88B4 全链路通过", "定向轮询成功，000B 服务块读取成功。"],
    "aic-poll": ["88B4 轮询通过", "手机响应了 Aime System Code，但后续块读取未通过。"],
    "generic-only": ["仅 4000 可见", "通用 HCE-F 可见，88B4 定向轮询没有发现目标。"],
    "wildcard-only": ["仅通配轮询可见", "手机在 FeliCa RF 上可见，但两个定向 System Code 均未通过。"],
    error: ["诊断未完成", "连接、超时或报文解析发生错误，请查看原始记录。"],
    none: ["等待诊断", "尚未获得可判定的 FeliCa 响应。"],
  }[verdict];
  setText(elements.verdict, content[0]);
  setText(elements.verdictDetail, content[1]);
  elements.verdict.dataset.state = verdict;
}

async function runProbe(systemCode, { cardCode = systemCode, readBlocks = true } = {}) {
  const code = normalizeSystemCode(systemCode);
  const pending = { state: "pending", requestedSystemCode: code };
  if (PROBE_CODES.includes(cardCode)) {
    results[cardCode] = pending;
    renderProbe(cardCode, pending);
  }

  let result;
  try {
    const target = await pollSystemCode(code);
    if (!target) {
      result = { state: "empty", requestedSystemCode: code, detail: "PN532 返回 0 个目标" };
    } else {
      result = { state: "found", requestedSystemCode: code, target };
      if (readBlocks) {
        try {
          const read = await readAimeBlocks(target);
          result.read = {
            state: "read",
            blocks: {
              "00": read.blockData[0] ? formatHex(read.blockData[0]) : "-",
              "82": read.blockData[1] ? formatHex(read.blockData[1]) : "-",
              "85": read.blockData[2] ? formatHex(read.blockData[2]) : "-",
            },
          };
        } catch (error) {
          result.read = { state: "error", detail: describeError(error) };
        }
      }
    }
  } catch (error) {
    result = { state: "error", requestedSystemCode: code, detail: describeError(error) };
  } finally {
    result.release = await releaseTargetsBestEffort();
  }

  if (PROBE_CODES.includes(cardCode)) {
    results[cardCode] = result;
    renderProbe(cardCode, result);
  }
  renderVerdict();
  return result;
}

async function runFullDiagnostic() {
  setBusy(true);
  results = {};
  for (const code of PROBE_CODES) renderProbe(code, null);
  renderVerdict();
  try {
    for (const code of PROBE_CODES) {
      await runProbe(code);
      await new Promise((resolve) => setTimeout(resolve, 120));
    }
  } finally {
    setBusy(false);
  }
}

function exportDiagnostic() {
  const report = {
    generatedAt: timestamp(),
    device: device
      ? {
          productName: device.productName,
          vendorId: device.vendorId,
          productId: device.productId,
        }
      : null,
    verdict: classifyDiagnostic(results),
    results,
    mifare: mifareResult,
    logs,
  };
  const blob = new Blob([JSON.stringify(report, null, 2)], { type: "application/json" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `hinata-diagnostic-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
  link.click();
  URL.revokeObjectURL(link.href);
}

elements.connect.addEventListener("click", async () => {
  setBusy(true);
  try {
    if (device?.opened) await disconnectDevice();
    else await connectDevice();
  } catch (error) {
    await disconnectDevice().catch(() => {});
    setText(elements.connection, "连接失败");
    elements.connection.dataset.state = "error";
    setText(elements.device, describeError(error));
  } finally {
    setBusy(false);
  }
});

elements.run.addEventListener("click", runFullDiagnostic);
elements.mifareDump.addEventListener("change", async () => {
  mifareDump = null;
  mifareResult = null;
  try {
    const file = elements.mifareDump.files?.[0];
    if (!file) {
      renderMifareResult();
      setBusy(false);
      return;
    }
    mifareDump = parseMifareClassic1kDump(new Uint8Array(await file.arrayBuffer()));
    const sector = mifareDump.sectors[1];
    if (!mifareDump.bccValid) throw new Error("dump 的 UID BCC 校验失败");
    if (!sector.accessValid) throw new Error("dump 的扇区 1 访问位校验失败");
  } catch (error) {
    mifareDump = null;
    mifareResult = { state: "error", detail: describeError(error) };
  }
  renderMifareResult();
  renderLog();
  setBusy(false);
});
elements.mifareRun.addEventListener("click", runMifareDiagnostic);
elements.customRun.addEventListener("click", async () => {
  setBusy(true);
  try {
    const code = normalizeSystemCode(elements.customCode.value);
    elements.customCode.value = code;
    const result = await runProbe(code, { cardCode: "custom" });
    const target = result.target;
    setText(
      document.querySelector("#custom-result"),
      target
        ? `${code} · IDm ${formatHex(target.idm)} · PMm ${formatHex(target.pmm)}`
        : `${code} · ${result.detail ?? "无目标"}`,
    );
  } catch (error) {
    setText(document.querySelector("#custom-result"), describeError(error));
  } finally {
    setBusy(false);
  }
});

elements.customCode.addEventListener("input", () => {
  elements.customCode.value = elements.customCode.value
    .replace(/[^0-9a-f]/gi, "")
    .slice(0, 4)
    .toUpperCase();
});

elements.relayAccessCode.addEventListener("input", () => {
  elements.relayAccessCode.value = elements.relayAccessCode.value.replace(/\D/g, "").slice(0, 20);
  setText(elements.relayResult, "-");
  updateRelayControls();
});

elements.relayCopy.addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(relayStatus.serverUrl);
    setText(elements.relayResult, "serverUrl 已复制");
  } catch (error) {
    setText(elements.relayResult, describeError(error));
  }
});

elements.relayForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  setBusy(true);
  try {
    await sendRelayCard();
  } catch (error) {
    setText(elements.relayResult, describeError(error));
  } finally {
    setBusy(false);
    await refreshRelayStatus();
  }
});

elements.clearLog.addEventListener("click", () => {
  logs = [];
  renderLog();
});
elements.exportLog.addEventListener("click", exportDiagnostic);

if ("hid" in navigator && window.isSecureContext) {
  setText(elements.support, "WebHID 可用");
  elements.support.dataset.state = "connected";
  navigator.hid.addEventListener("disconnect", (event) => {
    if (event.device === device) {
      disconnectDevice().catch(() => {});
    }
  });
} else {
  setText(elements.support, "WebHID 不可用");
  elements.support.dataset.state = "error";
  setText(elements.device, "请使用 Chrome 通过 localhost 打开");
}

for (const code of PROBE_CODES) renderProbe(code, null);
renderMifareResult();
renderLog();
renderVerdict();
setBusy(false);
refreshRelayStatus();
window.setInterval(refreshRelayStatus, 1500);
