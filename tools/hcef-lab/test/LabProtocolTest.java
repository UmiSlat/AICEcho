import io.github.umislat.aicecho.hceflab.LabProtocol;
import java.util.Arrays;

public final class LabProtocolTest {
    private static int checks;
    private static void check(boolean passed) {
        checks++;
        if (!passed) throw new AssertionError("check " + checks);
    }
    private static byte[] unhex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
    public static void main(String[] args) {
        byte[] two = unhex("120602FE6C6162323031010B000280008082");
        byte[] one = unhex("100602FE6C6162323031010B00018085");
        byte[] response = LabProtocol.reply(two, 0x4000);
        check(response != null && response.length == 45 && response[12] == 2);
        check(new String(response, 13, 16, java.nio.charset.StandardCharsets.US_ASCII).equals("HCEF-LAB-0000000"));
        check(LabProtocol.hex(Arrays.copyOfRange(response, 29, 45)).equals("02FE6C6162323031" + "00".repeat(8)));
        check(LabProtocol.reply(one, 0x4000)[13] == 0x40);
        check((LabProtocol.reply(one, 0x88B4)[13] & 255) == 0x88);
        check((LabProtocol.reply(one, 0x88B4)[14] & 255) == 0xB4);
        check(LabProtocol.reply(null, 0x4000) == null);
        for (int end = 0; end < two.length; end++) check(LabProtocol.reply(Arrays.copyOf(two, end), 0x4000) == null);
        byte[] bad = two.clone();
        bad[2] ^= 1;
        check(LabProtocol.reply(bad, 0x4000) == null);
        bad = one.clone(); bad[15] = 1;
        check(LabProtocol.reply(bad, 0x4000)[10] == 1);
        bad = one.clone(); bad[11] = 9;
        check(LabProtocol.reply(bad, 0x4000)[10] == 1);
        bad = one.clone(); bad[14] = (byte) 0x81;
        check(LabProtocol.reply(bad, 0x4000) == null);
        bad = one.clone(); bad[14] = (byte) 0x90;
        check(LabProtocol.reply(bad, 0x4000)[10] == 1);
        bad = one.clone(); bad[13] = 0;
        check(LabProtocol.reply(bad, 0x4000) == null);
        bad = Arrays.copyOf(one, 32); bad[0] = 32; bad[1] = 8;
        check(LabProtocol.hex(LabProtocol.reply(bad, 0x4000)).equals("0C0902FE6C616232303101A8"));
        check(LabProtocol.reply(unhex("110602FE6C6162323031010B0001008500"), 0x4000).length == 29);
        byte[] originalStyle = unhex("1206012E6C6162323031010B000280008082");
        byte[] provisioned = LabProtocol.replyProvisioned(originalStyle);
        check(provisioned != null && provisioned.length == 45 && provisioned[12] == 2);
        check(LabProtocol.hex(Arrays.copyOfRange(provisioned, 2, 10)).equals(LabProtocol.PROVISIONED_ID));
        check(LabProtocol.hex(Arrays.copyOfRange(provisioned, 29, 45)).equals(LabProtocol.PROVISIONED_ID + "00".repeat(8)));
        check(new String(provisioned, 13, 16, java.nio.charset.StandardCharsets.US_ASCII).equals("HCEF-LAB-0000000"));
        check(LabProtocol.replyProvisioned(two) == null);
        check(LabProtocol.reply(originalStyle, 0x4000) == null);
        check(LabProtocol.replyProvisioned(null) == null);
        for (int end = 0; end < originalStyle.length; end++) check(LabProtocol.replyProvisioned(Arrays.copyOf(originalStyle, end)) == null);
        byte[] originalOne = unhex("1006012E6C6162323031010B00018085");
        check(LabProtocol.hex(Arrays.copyOfRange(LabProtocol.replyProvisioned(originalOne), 13, 15)).equals("88B4"));
        bad = originalOne.clone(); bad[13] = 0;
        check(LabProtocol.replyProvisioned(bad) == null);
        bad = originalOne.clone(); bad[15] = 1;
        check(LabProtocol.replyProvisioned(bad)[10] == 1);
        bad = Arrays.copyOf(originalOne, 32); bad[0] = 32; bad[1] = 8;
        check(LabProtocol.hex(LabProtocol.replyProvisioned(bad)).equals("0C09012E6C616232303101A8"));
        provisioned[2] = 0;
        check(LabProtocol.replyProvisioned(originalStyle)[2] == 1);
        System.out.println("PASS " + checks + " checks");
    }
}
