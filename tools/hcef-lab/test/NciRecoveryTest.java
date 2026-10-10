import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class NciRecoveryTest {
    private static final String BOOT = "d04b4fff-caa8-4970-a059-b876c596d9b9";
    private static final byte[] FOREIGN = NciTrace.unhex("FEEF4D494D494245414DFFFFFFFFFFFFFFFF");
    private static int count;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    private static void test(String name, Checked run) throws Exception {
        try { run.run(); count++; }
        catch (Throwable error) { throw new AssertionError(name, error); }
    }
    private static void fails(Checked run, String message) throws Exception {
        try { run.run(); }
        catch (Exception error) {
            if (!String.valueOf(error.getMessage()).contains(message)) throw error;
            return;
        }
        throw new AssertionError("Expected failure: " + message);
    }
    private static Path root() throws IOException { return Files.createTempDirectory("hcef-recovery-"); }
    private static NciRecoveryJournal journal(Path root) throws IOException {
        return new NciRecoveryJournal(root.resolve("journal"));
    }

    private static final class Clock implements NciSession.Time {
        long offset;
        @Override public long millis() { return System.currentTimeMillis() + offset; }
        @Override public void sleep(long millis) throws InterruptedException {
            if (millis >= 5000) { offset += millis; Thread.sleep(1); }
            else Thread.sleep(Math.min(millis, 25));
        }
    }
    private static final class Controller implements NciSession.Controller {
        final Path root;
        int writes;
        boolean unavailable;
        byte[] flags = {3, 0};
        Checked afterWrite = () -> {};
        Checked beforeRead = () -> {};
        Controller(Path root) { this.root = root; }
        @Override public void checkReady() throws IOException {
            if (unavailable) throw new IOException("service unavailable");
        }
        @Override public byte[] readSlot() throws Exception {
            beforeRead.run();
            return Files.readAllBytes(root.resolve("slot"));
        }
        @Override public byte[] readFlags() { return flags.clone(); }
        @Override public void writeSlot(byte[] value) throws Exception {
            writes++; Files.write(root.resolve("slot"), value); afterWrite.run();
        }
        @Override public long maxPauseMillis() { return 15000; }
        @Override public int pause(long timeout) { return 1; }
        @Override public int resume() { return 1; }
    }
    private static void baseline(Path root) throws IOException { Files.write(root.resolve("slot"), NciSession.baseline()); }
    private static Process launch(String mode, Path root, String token, Path log) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                NciRecoveryTest.class.getName(), mode, root.toString(), token)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(file)) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Missing marker " + file);
            Thread.sleep(20);
        }
    }
    private static void finish(Process process) throws Exception {
        if (process != null && process.isAlive()) { process.destroyForcibly(); check(process.waitFor(5, TimeUnit.SECONDS)); }
    }
    private static void awaitGuardian(Path root) throws Exception {
        awaitFile(root.resolve("guard.pid"));
        long pid = Long.parseLong(Files.readString(root.resolve("guard.pid")));
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle != null) {
            try { handle.onExit().get(10, TimeUnit.SECONDS); }
            finally { if (handle.isAlive()) { handle.destroyForcibly(); handle.onExit().get(5, TimeUnit.SECONDS); } }
        }
    }
    private static NciSupervised.Launcher launcher(Path root) {
        return (token, log) -> {
            Process child = launch("guard", root, token, log);
            Files.writeString(root.resolve("guard.pid"), Long.toString(child.pid()));
            return child;
        };
    }
    private static void recoveryCase(String name, int phase, byte[] slot, boolean disabled,
            boolean unavailable, boolean expectedFailure) throws Exception {
        test(name, () -> {
            Path root = root(); Files.write(root.resolve("slot"), slot);
            NciRecoveryJournal journal = journal(root);
            Clock clock = new Clock();
            NciRecoveryJournal.Entry entry = journal.create(BOOT, clock.millis());
            Controller controller = new Controller(root);
            List<String> logs = new ArrayList<>();
            Checked run = () -> NciRecovery.watch(root.resolve("lock"), journal, entry.token, BOOT,
                    controller, clock, message -> {
                        logs.add(message);
                        if (message.startsWith("GUARD_READY")) {
                            try { journal.phase(entry, phase); }
                            catch (IOException error) { throw new IllegalStateException(error); }
                            controller.unavailable = unavailable;
                            if (disabled) controller.flags[0] = 0;
                        }
                    });
            if (expectedFailure) {
                try { run.run(); throw new AssertionError("Expected refusal"); }
                catch (IOException | IllegalStateException expected) { }
                check(journal.exists() && controller.writes == 0);
                check(Arrays.equals(controller.readSlot(), slot));
            } else {
                run.run(); check(!journal.exists());
                if (phase == NciRecoveryJournal.ARMED && Arrays.equals(slot, NciSession.temporary())) {
                    check(controller.writes == 1 && Arrays.equals(controller.readSlot(), NciSession.baseline()));
                } else check(controller.writes == 0);
            }
        });
    }
    private static void crashCase(boolean replaceSlot) throws Exception {
        test(replaceSlot ? "crash preserves competing owner" : "real worker death recovered by surviving guardian", () -> {
            Path root = root(); baseline(root);
            Process worker = launch("hold-worker", root, "unused", root.resolve("worker.log"));
            try {
                awaitFile(root.resolve("applied"));
                check(worker.isAlive());
                NciRecoveryJournal journal = journal(root);
                check(journal.read().phase == NciRecoveryJournal.ARMED);
                fails(journal::requireClear, "Pending recovery");
                if (replaceSlot) Files.write(root.resolve("slot"), FOREIGN);
                worker.destroyForcibly(); check(worker.waitFor(5, TimeUnit.SECONDS));
                awaitGuardian(root);
                check(Arrays.equals(Files.readAllBytes(root.resolve("slot")), replaceSlot ? FOREIGN : NciSession.baseline()));
                check(journal.exists() == replaceSlot);
                Path log;
                try (var files = Files.list(root.resolve("journal"))) {
                    log = files.filter(p -> p.getFileName().toString().startsWith("guard-")).findFirst().orElseThrow();
                }
                String output = Files.readString(log);
                check(output.contains(replaceSlot ? "RESTORE SKIPPED" : "GUARD_COMPLETE"));
            } finally { finish(worker); if (Files.exists(root.resolve("guard.pid"))) awaitGuardian(root); }
        });
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3) {
            Path root = Path.of(args[1]);
            if (args[0].equals("dead")) return;
            NciRecoveryJournal journal = journal(root);
            Clock clock = new Clock(); Controller controller = new Controller(root);
            if (args[0].equals("guard")) {
                NciRecovery.watch(root.resolve("lock"), journal, args[2], BOOT, controller, clock, System.out::println);
            } else if (args[0].equals("hold-worker")) {
                controller.afterWrite = () -> { Files.createFile(root.resolve("applied")); Thread.sleep(60000); };
                NciSupervised.run(root.resolve("lock"), journal, BOOT, controller, clock, launcher(root), System.out::println);
            } else throw new IllegalArgumentException("Unknown child mode");
            return;
        }
        test("journal round trip and pending gate", () -> {
            NciRecoveryJournal j = journal(root());
            NciRecoveryJournal.Entry e = j.create(BOOT, 100);
            check(j.read().token.equals(e.token) && j.read().phase == 0);
            fails(() -> j.create(BOOT, 101), "Pending recovery");
            j.phase(e, 1); check(j.read().phase == 1);
            fails(() -> j.phase(e, 0), "phase changed");
            j.phase(e, 2); j.clear(e); j.requireClear();
        });
        test("ownership and boot", () -> {
            NciRecoveryJournal j = journal(root()); var e = j.create(BOOT, 100);
            String other = UUID.randomUUID().toString();
            fails(() -> e.validate(other, BOOT, 100), "session/boot");
            fails(() -> e.validate(e.token, other, 100), "session/boot");
            fails(() -> j.clear(new NciRecoveryJournal.Entry(other, BOOT, 100, 0)), "session changed");
            fails(() -> j.clear(new NciRecoveryJournal.Entry(e.token, other, 100, 0)), "session changed");
            fails(() -> j.clear(new NciRecoveryJournal.Entry(e.token, BOOT, 101, 0)), "session changed");
        });
        test("bounded age and monotonicity", () -> {
            var e = journal(root()).create(BOOT, 100);
            e.validate(e.token, BOOT, 299999);
            fails(() -> e.validate(e.token, BOOT, 99), "expired");
            fails(() -> e.validate(e.token, BOOT, 300100), "expired");
        });
        test("corrupt record rejects", () -> {
            Path r = root(); var j = journal(r); j.create(BOOT, 100);
            Path p = r.resolve("journal/lease.bin"); byte[] bytes = Files.readAllBytes(p);
            bytes[10] ^= 1; Files.write(p, bytes); fails(j::read, "checksum");
            Files.write(p, new byte[300]); fails(j::read, "length");
            Files.write(p, new byte[3]); fails(j::read, "length");
        });
        test("stop token cannot target a different session", () -> {
            Path r = root(); var j = journal(r); var e = j.create(BOOT, 100);
            fails(() -> j.stop(UUID.randomUUID().toString(), BOOT, 100), "session/boot");
            check(!j.stopped(e.token)); j.stop(e.token, BOOT, 100); j.stop(e.token, BOOT, 100);
            check(j.stopped(e.token)); j.clear(e);
            var fresh = j.create(BOOT, 101); check(!j.stopped(fresh.token));
            fails(() -> j.stop(e.token, BOOT, 101), "session/boot");
        });
        test("malformed signals and path tokens rejected", () -> {
            Path r = root(); var j = journal(r); var e = j.create(BOOT, 100);
            Files.writeString(r.resolve("journal/ready-" + e.token), "bad");
            fails(() -> j.ready(e.token), "Invalid recovery signal");
            fails(() -> j.ready("../lease.bin"), "UUID");
        });
        test("late guardian cannot adopt a previous writer", () -> {
            Path r = root(); baseline(r); var j = journal(r); Clock c = new Clock();
            var e = j.create(BOOT, c.millis()); j.phase(e, NciRecoveryJournal.ARMED);
            fails(() -> NciRecovery.watch(r.resolve("lock"), j, e.token, BOOT, new Controller(r), c, s -> {}), "attach before");
            check(j.exists());
        });
        recoveryCase("recover exact synthetic image", 1, NciSession.temporary(), false, false, false);
        recoveryCase("already restored makes no write", 1, NciSession.baseline(), false, false, false);
        recoveryCase("unarmed session cannot adopt existing temporary image", 0, NciSession.temporary(), false, false, false);
        recoveryCase("completed session makes no controller write", 2, FOREIGN, false, false, false);
        recoveryCase("different owner retained", 1, FOREIGN, false, false, true);
        recoveryCase("disabled slot retained", 1, NciSession.temporary(), true, false, true);
        recoveryCase("lost service retained", 1, NciSession.temporary(), false, true, true);
        test("lock timeout never steals worker lock", () -> {
            Path r = root(); baseline(r); var j = journal(r); Clock c = new Clock();
            var e = j.create(BOOT, c.millis());
            try (var lock = NciProbeLock.acquire(r.resolve("lock"))) {
                fails(() -> NciRecovery.watch(r.resolve("lock"), j, e.token, BOOT,
                        new Controller(r), c, s -> { if (s.startsWith("GUARD_READY")) c.offset += 240001; }), "still owns");
            }
            check(j.exists());
        });
        test("launcher failure clears unarmed record", () -> {
            Path r = root(); baseline(r); var j = journal(r); Controller c = new Controller(r);
            fails(() -> NciSupervised.run(r.resolve("lock"), j, BOOT, c, new Clock(),
                    (t, p) -> { throw new IOException("launch failed"); }, s -> {}), "launch failed");
            check(!j.exists() && c.writes == 0);
        });
        test("dead guardian prevents write", () -> {
            Path r = root(); baseline(r); var j = journal(r); Controller c = new Controller(r);
            fails(() -> NciSupervised.run(r.resolve("lock"), j, BOOT, c, new Clock(),
                    (t, p) -> launch("dead", r, t, p), s -> {}), "before ready");
            check(j.exists() && c.writes == 0);
        });
        test("live worker disarms intent if ownership changes before SET", () -> {
            Path r = root(); baseline(r); var j = journal(r); Controller c = new Controller(r);
            c.beforeRead = () -> {
                if (j.exists() && j.read().phase == NciRecoveryJournal.ARMED) {
                    Files.write(r.resolve("slot"), NciSession.temporary());
                }
            };
            try {
                fails(() -> NciSupervised.run(r.resolve("lock"), j, BOOT, c, new Clock(), launcher(r), s -> {}), "Slot owner");
            } finally { if (Files.exists(r.resolve("guard.pid"))) awaitGuardian(r); }
            check(c.writes == 0 && !j.exists());
            check(Arrays.equals(c.readSlot(), NciSession.temporary()));
        });
        for (boolean stop : new boolean[] {false, true}) test(stop ? "live stop then independent guardian cleanup" : "normal supervised deadline", () -> {
            Path r = root(); baseline(r); var j = journal(r); Clock clock = new Clock(); Controller c = new Controller(r);
            if (stop) c.afterWrite = () -> { if (c.writes == 1) { var e = j.read(); j.stop(e.token, BOOT, clock.millis()); } };
            try { NciSupervised.run(r.resolve("lock"), j, BOOT, c, clock, launcher(r), s -> {}); }
            finally { if (Files.exists(r.resolve("guard.pid"))) awaitGuardian(r); }
            check(!j.exists() && c.writes == 2 && Arrays.equals(c.readSlot(), NciSession.baseline()));
        });
        crashCase(false); crashCase(true);
        test("CLI has no arbitrary write arguments", () -> {
            NciLab.validateArguments(new String[] {"session-start"});
            NciLab.validateArguments(new String[] {"session-stop", UUID.randomUUID().toString()});
            fails(() -> NciLab.validateArguments(new String[] {"session-start", "88B4"}), "Invalid mode");
            fails(() -> NciLab.validateArguments(new String[] {"session-stop", "../x"}), "Invalid UUID");
        });
        System.out.println("PASS NciRecovery " + count + " scenarios (including real subprocess crash)");
    }
}
