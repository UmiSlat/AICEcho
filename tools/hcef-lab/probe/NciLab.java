import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Targeted shell research tool: only the independently registered synthetic lab identity. */
public final class NciLab implements NciSession.Controller {
    private static final Path LOCK = Path.of("/data/local/tmp/aicecho-hcef-lab.lock");
    private static final Path RECOVERY = Path.of("/data/local/tmp/aicecho-hcef-recovery");
    private final Class<?> iface;
    private final Object service;
    private final Method send;

    private NciLab() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "nfc");
        iface = Class.forName("android.nfc.INfcAdapter");
        service = Class.forName("android.nfc.INfcAdapter$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        send = iface.getMethod("sendVendorNciMessage", int.class, int.class, int.class, byte[].class);
    }

    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (Exception error) {
            error.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        validateArguments(args);
        int uid = ((Number) Class.forName("android.os.Process").getMethod("myUid").invoke(null)).intValue();
        System.out.println("uid=" + uid);
        requireShellUid(uid);
        NciRecoveryJournal journal = new NciRecoveryJournal(RECOVERY);
        NciSession.Time time = androidTime();
        String boot = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim();
        switch (args[0]) {
            case "session-status":
                if (!journal.exists()) { log("NO_PENDING_SESSION"); return; }
                NciRecoveryJournal.Entry status = journal.read();
                log("SESSION " + status.token + " phase=" + status.phase + " sameBoot=" + boot.equals(status.boot));
                return;
            case "session-stop":
                journal.stop(args[1], boot, time.millis());
                log("STOP_REQUEST_RECORDED " + args[1] + "; restoration not yet confirmed");
                return;
            case "session-watch":
                NciRecovery.watch(LOCK, journal, args[1], boot, new NciLab(), time, NciLab::log);
                return;
            case "session-start":
                NciSupervised.run(LOCK, journal, boot, new NciLab(), time, (token, path) -> {
                    ProcessBuilder builder = new ProcessBuilder("/system/bin/nohup", "/system/bin/app_process",
                            "/system/bin", "NciLab", "session-watch", token);
                    builder.redirectInput(Path.of("/dev/null").toFile());
                    builder.redirectErrorStream(true);
                    builder.redirectOutput(path.toFile());
                    log("GUARD_LOG " + path);
                    return builder.start();
                }, NciLab::log);
                return;
            default: break;
        }
        try (NciProbeLock ignored = NciProbeLock.acquire(LOCK)) {
            NciLab lab = new NciLab();
            lab.checkReady();
            System.out.println("ID1=" + NciTrace.hex(lab.readSlot()));
            System.out.println("FLAGS2=" + NciTrace.hex(lab.readFlags()));
            if (args[0].equals("read-lab")) return;
            if (args[0].equals("session-ack")) {
                NciRecoveryJournal.Entry entry = journal.read();
                if (!entry.token.equals(args[1])) throw new IllegalArgumentException("Recovery session changed");
                if (!Arrays.equals(lab.readSlot(), NciSession.baseline())) {
                    throw new IllegalStateException("Acknowledge requires independently restored synthetic 4000 baseline");
                }
                journal.clear(entry);
                log("ACKNOWLEDGED_BASELINE; no controller write");
                return;
            }
            journal.requireClear();
            NciSession session = new NciSession(lab, time, NciLab::log);
            switch (args[0]) {
                case "restore-lab": session.restore(true); break;
                case "set-baseline": session.sameValueControl(); break;
                default: session.temporary(args[0].equals("temporary-sc-idle"));
            }
        }
    }

    static void validateArguments(String[] args) {
        boolean one = args.length == 1 && Arrays.asList("read-lab", "temporary-sc", "restore-lab",
                "set-baseline", "temporary-sc-idle", "session-start", "session-status").contains(args[0]);
        boolean two = args.length == 2 && Arrays.asList("session-stop", "session-watch", "session-ack").contains(args[0]);
        if (!one && !two) throw new IllegalArgumentException("Invalid mode/argument count; no arbitrary NCI input supported");
        if (two && !java.util.UUID.fromString(args[1]).toString().equals(args[1])) {
            throw new IllegalArgumentException("Invalid session token");
        }
    }

    private static NciSession.Time androidTime() throws Exception {
        Method elapsed = Class.forName("android.os.SystemClock").getMethod("elapsedRealtime");
        return new NciSession.Time() {
            @Override public long millis() {
                try { return ((Number) elapsed.invoke(null)).longValue(); }
                catch (Exception error) { throw new IllegalStateException("Monotonic clock unavailable", error); }
            }
            @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        };
    }

    private static void log(String message) { System.out.println(message); System.out.flush(); }

    static void requireShellUid(int uid) {
        if (uid != 2000) throw new SecurityException("This lab probe requires shell UID 2000, not root/app UID");
    }

    @Override public long maxPauseMillis() throws Exception {
        return ((Number) iface.getMethod("getMaxPausePollingTimeoutMs").invoke(service)).longValue();
    }

    @Override public int pause(long millis) throws Exception {
        return ((Number) iface.getMethod("pausePolling", long.class).invoke(service, millis)).intValue();
    }

    @Override public int resume() throws Exception {
        return ((Number) iface.getMethod("resumePolling").invoke(service)).intValue();
    }

    @Override public byte[] readSlot() throws Exception { return get(0x40); }
    @Override public byte[] readFlags() throws Exception { return get(0x53); }

    @Override public void writeSlot(byte[] value) throws Exception {
        if (!Arrays.equals(value, NciSession.baseline()) && !Arrays.equals(value, NciSession.temporary())) {
            throw new IllegalArgumentException("Only the exact synthetic lab images are allowed");
        }
        set(0x40, value);
    }

    @Override public void checkReady() throws Exception {
        if (!Integer.valueOf(3).equals(iface.getMethod("getState").invoke(service))) throw new IllegalStateException("NFC off");
        if (Boolean.TRUE.equals(iface.getMethod("isTagPresent").invoke(service))) throw new IllegalStateException("Active tag");
    }

    private byte[] get(int id) throws Exception {
        return NciTrace.parameter(exchange(3, new byte[] {1, (byte) id}), id);
    }

    private void set(int id, byte[] value) throws Exception {
        byte[] payload = new byte[3 + value.length];
        payload[0] = 1; payload[1] = (byte) id; payload[2] = (byte) value.length;
        System.arraycopy(value, 0, payload, 3, value.length);
        byte[] result = exchange(2, payload);
        if (!Arrays.equals(result, new byte[] {0, 0})) {
            throw new IllegalStateException("SET_CONFIG not accepted: " + NciTrace.hex(result));
        }
    }

    private byte[] exchange(int oid, byte[] payload) throws Exception {
        checkReady();
        byte[] tx = new byte[3 + payload.length];
        tx[0] = 0x20; tx[1] = (byte) oid; tx[2] = (byte) payload.length;
        System.arraycopy(payload, 0, tx, 3, payload.length);
        long startMs = System.currentTimeMillis();
        Object status = send.invoke(service, 1, 0, oid, payload);
        System.out.println("TX=" + NciTrace.hex(tx) + " API=" + status);
        byte[] response = NciTrace.response(NciTrace.decode(dump()), tx, startMs);
        System.out.println("RX_PAYLOAD=" + NciTrace.hex(response));
        if (!Integer.valueOf(0).equals(status)) throw new IllegalStateException("Transport failed: " + status);
        return response;
    }

    private static String dump() throws Exception {
        Process process = new ProcessBuilder("dumpsys", "nfc").redirectErrorStream(true).start();
        FutureTask<String> read = new FutureTask<>(() -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = stream.read(buffer)) != -1) {
                    if (output.size() + size > 8 * 1024 * 1024) throw new IllegalStateException("Dump size limit");
                    output.write(buffer, 0, size);
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        });
        Thread reader = new Thread(read, "bounded-nfc-dump");
        reader.setDaemon(true);
        reader.start();
        try {
            String result = read.get(8, TimeUnit.SECONDS);
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException("dumpsys failed");
            return result;
        } finally {
            process.destroy();
        }
    }
}
