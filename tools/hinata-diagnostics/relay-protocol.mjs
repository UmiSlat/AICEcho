const HEX_16 = /^[0-9a-f]{16}$/i;
const ACCESS_CODE = /^\d{20}$/;

function requireString(value, field) {
  if (typeof value !== "string") throw new TypeError(`${field} must be a string`);
  return value;
}

export function createAicRelayMessage(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new TypeError("Request body must be an object");
  }

  const id = requireString(input.id, "id").trim().toLowerCase();
  const pmm = requireString(input.pmm, "pmm").trim().toLowerCase();
  const accessCode = requireString(input.accessCode, "accessCode").trim();

  if (!HEX_16.test(id)) throw new TypeError("id must contain 16 hexadecimal digits");
  if (!id.startsWith("02fe")) throw new TypeError("id must be an 02FE compatibility NFCID2");
  if (!HEX_16.test(pmm)) throw new TypeError("pmm must contain 16 hexadecimal digits");
  if (!ACCESS_CODE.test(accessCode)) {
    throw new TypeError("accessCode must contain 20 decimal digits");
  }

  return {
    action: "SET_CARD_V2",
    body: {
      card: {
        type: "aic",
        id,
        pmm,
        systemCode: [0x88b4],
        accessCode,
      },
    },
  };
}

export function parseClientHello(value) {
  const message = typeof value === "string" ? JSON.parse(value) : value;
  if (!message || typeof message !== "object" || Array.isArray(message)) return null;
  if (message.action !== "CLIENT_HELLO") return null;
  if (message.body?.protocol !== "aimeio-remote") return null;
  if (message.body?.version !== 2) return null;
  return { protocol: message.body.protocol, version: message.body.version };
}
