package io.github.umislat.aicecho.hceflab;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Restricted synthetic test image, never a user's card or an Access Code decoder. */
public final class LabProtocol {
    public static final String ID = "02FE6C6162323031";
    public static final String PROVISIONED_ID = "012E6C6162323031";
    private static final byte[] ID_BYTES = {2, (byte) 0xFE, 0x6C, 0x61, 0x62, 0x32, 0x30, 0x31};
    private static final byte[] PROVISIONED_BYTES = {1, 0x2E, 0x6C, 0x61, 0x62, 0x32, 0x30, 0x31};

    private LabProtocol() {}

    public static byte[] reply(byte[] request, int imageSc) {
        if (imageSc != 0x4000 && imageSc != 0x88B4) throw new IllegalArgumentException("imageSc");
        return reply(request, imageSc, ID_BYTES);
    }

    public static byte[] replyProvisioned(byte[] request) {
        return reply(request, 0x88B4, PROVISIONED_BYTES);
    }

    private static byte[] reply(byte[] request, int imageSc, byte[] id) {
        if (request == null || request.length < 10 || (request[0] & 255) != request.length
                || !Arrays.equals(id, Arrays.copyOfRange(request, 2, 10))) return null;
        int cmd = request[1] & 255;
        if (cmd != 6 && cmd != 8) return null;
        if (request.length < 14) return null;
        int services = request[10] & 255;
        int serviceEnd = 11 + 2 * services;
        if (services == 0 || serviceEnd >= request.length) return null;
        int count = request[serviceEnd] & 255;
        if (count == 0) return null;
        int cursor = serviceEnd + 1;
        byte[][] blocks = new byte[count][];
        boolean supported = count <= 15;
        for (int i = 0; i < count; i++) {
            if (cursor >= request.length) return null;
            int desc = request[cursor] & 255;
            int index = desc & 15;
            int width = (desc & 0x80) != 0 ? 2 : 3;
            if (index >= services || cursor + width > request.length) return null;
            int service = (request[11 + 2 * index] & 255) | (request[12 + 2 * index] & 255) << 8;
            int number = request[cursor + 1] & 255;
            if (width == 3) number |= (request[cursor + 2] & 255) << 8;
            blocks[i] = block(number, imageSc, id);
            supported &= service == 0x000B && (desc & 0x70) == 0 && blocks[i] != null;
            cursor += width;
        }
        if (cursor + (cmd == 8 ? count * 16 : 0) != request.length) return null;
        if (cmd == 8) return frame(9, new byte[] {1, (byte) 0xA8}, id);
        if (!supported) return frame(7, new byte[] {1, (byte) 0xA2, 0}, id);
        byte[] body = new byte[3 + 16 * count];
        body[2] = (byte) count;
        for (int i = 0; i < count; i++) System.arraycopy(blocks[i], 0, body, 3 + 16 * i, 16);
        return frame(7, body, id);
    }

    private static byte[] block(int number, int sc, byte[] id) {
        if (number == 0) return "HCEF-LAB-0000000".getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[16];
        if (number == 0x82) {
            System.arraycopy(id, 0, result, 0, 8);
            return result;
        }
        if (number == 0x85) {
            result[0] = (byte) (sc >> 8);
            result[1] = (byte) sc;
            return result;
        }
        return null;
    }

    private static byte[] frame(int cmd, byte[] body, byte[] id) {
        byte[] result = new byte[10 + body.length];
        result[0] = (byte) result.length;
        result[1] = (byte) cmd;
        System.arraycopy(id, 0, result, 2, 8);
        System.arraycopy(body, 0, result, 10, body.length);
        return result;
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02X", value & 255));
        return result.toString();
    }
}
