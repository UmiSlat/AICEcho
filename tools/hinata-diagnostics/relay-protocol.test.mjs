import assert from "node:assert/strict";
import test from "node:test";

import { createAicRelayMessage, parseClientHello } from "./relay-protocol.mjs";

test("builds the HINATA AIC V2 payload for an 02FE route", () => {
  assert.deepEqual(
    createAicRelayMessage({
      id: "02FE001145141919",
      pmm: "00F1000000014300",
      accessCode: "00010203040506070809",
    }),
    {
      action: "SET_CARD_V2",
      body: {
        card: {
          type: "aic",
          id: "02fe001145141919",
          pmm: "00f1000000014300",
          systemCode: [0x88b4],
          accessCode: "00010203040506070809",
        },
      },
    },
  );
});

test("rejects non-02FE IDs and malformed access codes", () => {
  assert.throws(
    () => createAicRelayMessage({
      id: "012e001145141919",
      pmm: "00f1000000014300",
      accessCode: "00010203040506070809",
    }),
    /02FE/,
  );
  assert.throws(
    () => createAicRelayMessage({
      id: "02fe001145141919",
      pmm: "00f1000000014300",
      accessCode: "1234",
    }),
    /20 decimal digits/,
  );
});

test("accepts only the HINATA remote protocol v2 hello", () => {
  assert.deepEqual(
    parseClientHello('{"action":"CLIENT_HELLO","body":{"protocol":"aimeio-remote","version":2}}'),
    { protocol: "aimeio-remote", version: 2 },
  );
  assert.equal(
    parseClientHello({ action: "CLIENT_HELLO", body: { protocol: "aimeio-remote", version: 1 } }),
    null,
  );
  assert.equal(parseClientHello({ action: "SET_CARD_V2", body: {} }), null);
});
