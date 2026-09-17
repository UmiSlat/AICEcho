import test from "node:test";
import assert from "node:assert/strict";

import {
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
  parseFelicaPollResponse,
  parseFelicaReadResponse,
  parseMifareClassic1kDump,
  parseMifareDataExchange,
  parseMifarePollResponse,
  parsePn532Frame,
  validateMifareAccessBits,
} from "./protocol.mjs";

test("builds the PN532 command that releases every active target", () => {
  const frame = buildPn532Frame(
    PN532_DIRECTION_HOST_TO_CHIP,
    PN532_COMMAND_IN_RELEASE,
    [0x00],
  );
  assert.equal(formatHex(frame), "0000FF03FDD45200DA00");
});

test("builds the HINATA Go compatible FFFF polling frame", () => {
  const frame = buildPn532Frame(
    PN532_DIRECTION_HOST_TO_CHIP,
    0x4a,
    buildFelicaPollPayload("FFFF"),
  );
  assert.equal(formatHex(frame), "0000FF09F7D44A010100FFFF0100E100");
  assert.deepEqual(parsePn532Frame(frame).payload, [0x01, 0x01, 0x00, 0xff, 0xff, 0x01, 0x00]);
});

test("parses a FeliCa target with one returned System Code", () => {
  const responsePayload = [
    0x01,
    0x01,
    0x14,
    0x01,
    0x02, 0xfe, 0x00, 0x11, 0x45, 0x14, 0x19, 0x19,
    0x00, 0xf1, 0x00, 0x00, 0x00, 0x01, 0x43, 0x00,
    0x40, 0x00,
  ];
  const target = parseFelicaPollResponse(responsePayload);
  assert.equal(target.targetNumber, 1);
  assert.equal(formatHex(target.idm), "02FE001145141919");
  assert.equal(formatHex(target.pmm), "00F1000000014300");
  assert.deepEqual(target.systemCodes, [0x4000]);
});

test("builds and parses a 106 kbps NFC-A polling exchange", () => {
  const frame = buildPn532Frame(
    PN532_DIRECTION_HOST_TO_CHIP,
    0x4a,
    buildMifarePollPayload(),
  );
  assert.equal(formatHex(frame), "0000FF04FCD44A0100E100");

  const target = parseMifarePollResponse([
    0x01, 0x01, 0x00, 0x04, 0x08, 0x04, 0xde, 0xad, 0xbe, 0xef,
  ]);
  assert.equal(target.targetNumber, 1);
  assert.equal(formatHex(target.atqa), "0004");
  assert.equal(target.sak, 0x08);
  assert.equal(target.type, "MIFARE Classic 1K");
  assert.equal(formatHex(target.uid), "DEADBEEF");
});

test("builds read-only MIFARE authentication and block commands", () => {
  assert.equal(
    formatHex(buildMifareAuthenticate(
      4,
      [0x01, 0x02, 0x03, 0x04, 0x05, 0x06],
      [0xde, 0xad, 0xbe, 0xef],
      "B",
    )),
    "6104010203040506DEADBEEF",
  );
  assert.equal(formatHex(buildMifareReadBlock(4)), "3004");
  assert.deepEqual(parseMifareDataExchange([0x00, ...Array(16).fill(0xaa)]), {
    status: 0,
    data: Array(16).fill(0xaa),
  });
});

test("parses a MIFARE Classic 1K dump without exposing keys through formatting", () => {
  const dump = Array(1024).fill(0x00);
  dump.splice(0, 5, 0xde, 0xad, 0xbe, 0xef, 0x22);
  for (let sector = 0; sector < 16; sector += 1) {
    dump.splice(
      sector * 64 + 48,
      16,
      ...Array(6).fill(0xff),
      0xff, 0x07, 0x80, 0x69,
      ...Array(6).fill(0xff),
    );
  }
  dump.fill(0xaa, 64, 112);
  dump.splice(112, 16, 1, 2, 3, 4, 5, 6, 0x7f, 0x07, 0x88, 0x69, 7, 8, 9, 10, 11, 12);

  const parsed = parseMifareClassic1kDump(dump);
  assert.equal(parsed.bccValid, true);
  assert.equal(formatHex(parsed.uid), "DEADBEEF");
  assert.equal(formatHex(parsed.sectors[1].keyA), "010203040506");
  assert.equal(formatHex(parsed.sectors[1].keyB), "0708090A0B0C");
  assert.equal(parsed.sectors[1].accessValid, true);
  assert.equal(formatHex(parsed.sectors[1].dataBlocks[0]), "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
});

test("rejects corrupted MIFARE access-bit redundancy", () => {
  assert.equal(validateMifareAccessBits([0xff, 0x07, 0x80]), true);
  assert.equal(validateMifareAccessBits([0xff, 0x07, 0x81]), false);
});

test("builds a little-endian 000B read for blocks 00, 82, and 85", () => {
  const command = buildFelicaReadWithoutEncryption(
    [0x02, 0xfe, 0x00, 0x11, 0x45, 0x14, 0x19, 0x19],
    [0x00, 0x82, 0x85],
  );
  assert.equal(formatHex(command), "140602FE001145141919010B0003800080828085");
});

test("parses a three-block Read Without Encryption response", () => {
  const idm = [0x02, 0xfe, 0x00, 0x11, 0x45, 0x14, 0x19, 0x19];
  const block0 = Array.from({ length: 16 }, (_, index) => index);
  const block82 = [...idm, ...Array(8).fill(0xaa)];
  const block85 = [0x40, 0x00, ...Array(14).fill(0x00)];
  const felica = [0x3d, 0x07, ...idm, 0x00, 0x00, 0x03, ...block0, ...block82, ...block85];
  const parsed = parseFelicaReadResponse([0x00, ...felica]);
  assert.equal(parsed.statusFlag1, 0);
  assert.equal(formatHex(parsed.blockData[0]), "000102030405060708090A0B0C0D0E0F");
  assert.equal(formatHex(parsed.blockData[1]), "02FE001145141919AAAAAAAAAAAAAAAA");
  assert.equal(formatHex(parsed.blockData[2]), "40000000000000000000000000000000");
});

test("keeps split block reads within one HINATA E2 HID report", () => {
  const idm = [0x02, 0xfe, 0x00, 0x11, 0x45, 0x14, 0x19, 0x19];
  const responseFrameLength = (blockCount) => {
    const felica = [13 + blockCount * 16, 0x07, ...idm, 0x00, 0x00, blockCount];
    for (let index = 0; index < blockCount * 16; index += 1) felica.push(0x00);
    return buildPn532Frame(PN532_DIRECTION_CHIP_TO_HOST, 0x41, [0x00, ...felica]).length;
  };

  assert.equal(responseFrameLength(3), 71);
  assert.ok(responseFrameLength(2) <= 63);
  assert.ok(responseFrameLength(1) <= 63);
});

test("validates PN532 checksums", () => {
  const frame = buildPn532Frame(PN532_DIRECTION_CHIP_TO_HOST, 0x4b, [0x00]);
  frame[frame.length - 2] ^= 0x01;
  assert.throws(() => parsePn532Frame(frame), /data checksum/);
});

test("classifies targeted polling independently from wildcard discovery", () => {
  assert.equal(
    classifyDiagnostic({ FFFF: { state: "found" }, "4000": { state: "found" }, "88B4": { state: "empty" } }),
    "generic-only",
  );
  assert.equal(
    classifyDiagnostic({ "88B4": { state: "found", read: { state: "read" } } }),
    "aic-read",
  );
});
