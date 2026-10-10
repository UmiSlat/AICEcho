import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/** Decodes the existing Android NFCSNOOP ring without taking the global vendor callback. */
public final class NciTrace {
    private static final Pattern BLOCK = Pattern.compile(
            "--- BEGIN:NFCSNOOP_LOG_SUMMARY \\((\\d+) bytes in\\) ---\\s*(.*?)\\s*--- END:NFCSNOOP_LOG_SUMMARY ---",
            Pattern.DOTALL);
    public static final class Packet {
        public long epochUs;
        final long delta;
        public final boolean received;
        public final byte[] bytes;
        Packet(long delta, boolean received, byte[] bytes) {
            this.delta = delta; this.received = received; this.bytes = bytes;
        }
    }

    public static List<Packet> decode(String dump) throws Exception {
        Matcher match = BLOCK.matcher(dump);
        if (!match.find()) throw new IllegalStateException("No NFCSNOOP system block");
        int expected = Integer.parseInt(match.group(1));
        if (expected < 0 || expected > 2 * 1024 * 1024) throw new IllegalStateException("Snoop size limit");
        byte[] compressed = Base64.getMimeDecoder().decode(match.group(2));
        if (compressed.length < 11 || compressed[0] != 1) throw new IllegalStateException("Snoop version");
        long timestamp = little(compressed, 1, 8);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (InflaterInputStream inflater = new InflaterInputStream(
                new ByteArrayInputStream(compressed, 9, compressed.length - 9))) {
            byte[] buffer = new byte[4096];
            int size;
            while ((size = inflater.read(buffer)) != -1) {
                if (raw.size() + size > expected) throw new IllegalStateException("Snoop decompression size");
                raw.write(buffer, 0, size);
            }
        }
        byte[] records = raw.toByteArray();
        if (records.length != expected) throw new IllegalStateException("Snoop length mismatch");
        List<Packet> packets = new ArrayList<>();
        for (int offset = 0; offset < records.length;) {
            if (offset + 7 > records.length) throw new IllegalStateException("Record header truncated");
            int length = (int) little(records, offset, 2);
            long delta = little(records, offset + 2, 4);
            int received = records[offset + 6] & 255;
            offset += 7;
            if (length < 3 || offset + length > records.length || received > 1) {
                throw new IllegalStateException("Invalid snoop record");
            }
            packets.add(new Packet(delta, received == 1, Arrays.copyOfRange(records, offset, offset + length)));
            offset += length;
        }
        for (int i = packets.size() - 1; i >= 0; i--) {
            Packet p = packets.get(i);
            p.epochUs = timestamp;
            timestamp -= p.delta;
        }
        return packets;
    }

    public static byte[] response(List<Packet> packets, byte[] tx, long sinceMs) {
        if (tx.length < 3 || (tx[0] & 0xF0) != 0x20 || tx.length != 3 + (tx[2] & 255)) {
            throw new IllegalStateException("Expected a complete unfragmented command");
        }
        int command = -1;
        for (int i = 0; i < packets.size(); i++) {
            Packet p = packets.get(i);
            if (p.epochUs / 1000 >= sinceMs && !p.received && Arrays.equals(p.bytes, tx)) {
                if (command >= 0) throw new IllegalStateException("Ambiguous duplicate NCI command");
                command = i;
            }
        }
        if (command < 0) throw new IllegalStateException("Exact outgoing NCI frame not in trace");
        int expectedHeader = 0x40 | (tx[0] & 15);
        int oid = tx[1] & 63;
        // A preceding unresolved command with the same opcode could own the response.
        for (int i = command - 1; i >= 0; i--) {
            Packet p = packets.get(i);
            int header = p.bytes[0] & 255;
            if ((p.bytes[1] & 63) != oid) continue;
            if (p.received && header == expectedHeader) break;
            if (!p.received && (header & 0xEF) == (tx[0] & 255)) {
                throw new IllegalStateException("Earlier same-opcode NCI command is unresolved");
            }
        }
        for (int i = command + 1; i < packets.size(); i++) {
            Packet p = packets.get(i);
            int header = p.bytes[0] & 255;
            if (!p.received && (header & 0xEF) == (tx[0] & 255) && (p.bytes[1] & 63) == oid) {
                throw new IllegalStateException("Ambiguous concurrent NCI command");
            }
            if (p.received && (header & 0xEF) == expectedHeader && (p.bytes[1] & 63) == oid) {
                if ((header & 0x10) != 0) throw new IllegalStateException("Fragmented response unsupported");
                if (p.bytes.length != 3 + (p.bytes[2] & 255)) throw new IllegalStateException("Response length");
                return Arrays.copyOfRange(p.bytes, 3, p.bytes.length);
            }
        }
        throw new IllegalStateException("No correlated NCI response");
    }

    public static byte[] parameter(byte[] response, int id) {
        if (response.length < 2 || response[0] != 0 || response[1] != 1) {
            throw new IllegalStateException("GET_CONFIG rejected: " + hex(response));
        }
        if (response.length < 4 || (response[2] & 255) != id || response.length != 4 + (response[3] & 255)) {
            throw new IllegalStateException("GET_CONFIG unexpected parameter");
        }
        return Arrays.copyOfRange(response, 4, response.length);
    }

    private static long little(byte[] bytes, int offset, int count) {
        long value = 0;
        for (int i = 0; i < count; i++) value |= (long) (bytes[offset + i] & 255) << (8 * i);
        return value;
    }

    public static byte[] unhex(String value) {
        if (!value.matches("(?:[0-9A-Fa-f]{2})+")) throw new IllegalArgumentException("Hex bytes");
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02X", b & 255));
        return out.toString();
    }
}
