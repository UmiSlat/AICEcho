import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.zip.DeflaterOutputStream;

public final class NciTraceTest {
    private static int count;
    private static void check(boolean result) { count++; if (!result) throw new AssertionError(count); }
    private static void rejects(Runnable fn) {
        count++;
        try { fn.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection " + count);
    }
    private static String fixture(byte[] record, int declaredSize) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {1, (byte) 0xA0, (byte) 0x86, 1, 0, 0, 0, 0, 0});
        try (DeflaterOutputStream zlib = new DeflaterOutputStream(out)) { zlib.write(record); }
        return "--- BEGIN:NFCSNOOP_LOG_SUMMARY (" + declaredSize + " bytes in) ---\n"
                + Base64.getEncoder().encodeToString(out.toByteArray()) + "\n--- END:NFCSNOOP_LOG_SUMMARY ---";
    }
    private static void rejectsDump(String dump) throws Exception {
        count++;
        try { NciTrace.decode(dump); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected invalid dump " + count);
    }
    public static void main(String[] args) throws Exception {
        check(Arrays.equals(NciTrace.parameter(NciTrace.unhex("0001520110"), 0x52), new byte[] {16}));
        rejects(() -> NciTrace.parameter(NciTrace.unhex("09015100"), 0x51));
        rejects(() -> NciTrace.parameter(NciTrace.unhex("0001520110FF"), 0x52));
        rejects(() -> NciTrace.parameter(NciTrace.unhex("0001530110"), 0x52));
        byte[] record = NciTrace.unhex("080000000000014003050001520110");
        List<NciTrace.Packet> single = NciTrace.decode(fixture(record, record.length));
        check(single.size() == 1 && single.get(0).epochUs == 100000 && single.get(0).received);
        rejectsDump(fixture(record, record.length + 1));
        rejectsDump(fixture(record, record.length - 1));
        rejectsDump(fixture(Arrays.copyOf(record, 6), 6));
        byte[] invalid = record.clone(); invalid[6] = 2;
        rejectsDump(fixture(invalid, invalid.length));
        byte[] tx = NciTrace.unhex("2003020152");
        List<NciTrace.Packet> synthetic = new ArrayList<>();
        NciTrace.Packet sent = new NciTrace.Packet(0, false, tx); sent.epochUs = 100000;
        synthetic.add(sent);
        synthetic.add(new NciTrace.Packet(0, true, NciTrace.unhex("60060100")));
        synthetic.add(single.get(0));
        check(Arrays.equals(NciTrace.response(synthetic, tx, 100), NciTrace.unhex("0001520110")));
        rejects(() -> NciTrace.response(synthetic, tx, 101));
        synthetic.add(1, sent);
        rejects(() -> NciTrace.response(synthetic, tx, 100));
        synthetic.remove(1);
        // Never attribute a later same-OID response to this command without a unique send.
        synthetic.add(2, new NciTrace.Packet(0, false, NciTrace.unhex("2003020153")));
        rejects(() -> NciTrace.response(synthetic, tx, 100));
        synthetic.remove(2);
        synthetic.set(synthetic.size() - 1, new NciTrace.Packet(0, true, NciTrace.unhex("5003050001520110")));
        rejects(() -> NciTrace.response(synthetic, tx, 100));
        rejects(() -> NciTrace.response(synthetic, new byte[] {0x20}, 100));
        rejects(() -> NciTrace.response(synthetic, NciTrace.unhex("3003020152"), 100));
        rejects(() -> NciTrace.response(synthetic, NciTrace.unhex("2003030152"), 100));
        List<NciTrace.Packet> pending = new ArrayList<>();
        NciTrace.Packet earlier = new NciTrace.Packet(0, false, NciTrace.unhex("2003020153"));
        earlier.epochUs = 90000;
        pending.add(earlier); pending.add(sent); pending.add(single.get(0));
        rejects(() -> NciTrace.response(pending, tx, 100));
        pending.add(1, new NciTrace.Packet(0, true, NciTrace.unhex("400306000153020300")));
        check(Arrays.equals(NciTrace.response(pending, tx, 100), NciTrace.unhex("0001520110")));
        pending.add(3, new NciTrace.Packet(0, false, NciTrace.unhex("3003020153")));
        rejects(() -> NciTrace.response(pending, tx, 100));
        pending.remove(3);
        pending.set(1, new NciTrace.Packet(0, true, NciTrace.unhex("50030100")));
        rejects(() -> NciTrace.response(pending, tx, 100));
        if (args.length == 1) {
            List<NciTrace.Packet> trace = NciTrace.decode(Files.readString(Path.of(args[0])));
            check(trace.size() > 0);
            check(Arrays.equals(NciTrace.response(trace, NciTrace.unhex("2003020152"), 1791429115700L), NciTrace.unhex("0001520110")));
            check(Arrays.equals(NciTrace.response(trace, NciTrace.unhex("2003050440415153"), 1791429115700L), NciTrace.unhex("09015100")));
            rejects(() -> NciTrace.response(trace, NciTrace.unhex("2003020152"), Long.MAX_VALUE));
        }
        System.out.println("PASS NciTrace " + count + " checks");
    }
}
