import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public final class NciSessionTest {
    private static int cases;
    private static final byte[] FOREIGN = NciTrace.unhex("FEEF4D494D494245414DFFFFFFFFFFFFFFFF");
    private interface Test { void run() throws Exception; }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static Exception fails(Test test, String message) throws Exception {
        try { test.run(); }
        catch (Exception error) {
            check(error.getMessage().contains(message));
            return error;
        }
        throw new AssertionError("Expected failure: " + message);
    }
    private static void test(String name, Test test) throws Exception {
        try { test.run(); cases++; }
        catch (Throwable error) { throw new AssertionError(name, error); }
    }

    private static final class Fake implements NciSession.Controller, NciSession.Time {
        byte[] slot = NciSession.baseline();
        byte[] flags = {3, 0};
        long now;
        long maxPause = 15000;
        int pauseResult = 1;
        int resumeResult = 1;
        boolean ready = true;
        boolean loseFirstResponse;
        boolean rejectFirstWrite;
        int slotReads, pauses, resumes, writes, sleeps;
        Consumer<String> event = ignored -> {};
        final List<String> logs = new ArrayList<>();
        NciSession session() { return new NciSession(this, this, logs::add); }
        @Override public void checkReady() throws IOException {
            if (!ready) throw new IOException("service unavailable");
        }
        @Override public byte[] readSlot() {
            slotReads++; now++; event.accept("read"); return slot.clone();
        }
        @Override public byte[] readFlags() { now++; event.accept("flags"); return flags.clone(); }
        @Override public void writeSlot(byte[] value) throws IOException {
            writes++; now++;
            if (writes == 1 && rejectFirstWrite) throw new IOException("SET rejected");
            slot = value.clone();
            event.accept("write");
            if (writes == 1 && loseFirstResponse) throw new IOException("SET response lost");
        }
        @Override public long maxPauseMillis() { return maxPause; }
        @Override public int pause(long millis) {
            check(millis >= 5000 && millis <= 15000);
            pauses++; now++; event.accept("pause"); return pauseResult;
        }
        @Override public int resume() { resumes++; now++; event.accept("resume"); return resumeResult; }
        @Override public long millis() { return now; }
        @Override public void sleep(long millis) { check(millis > 0); now += millis; sleeps++; event.accept("sleep"); }
    }

    public static void main(String[] args) throws Exception {
        test("timed paused transaction", () -> {
            Fake f = new Fake(); f.session().temporary(true);
            check(f.writes == 2 && f.pauses == 2 && f.resumes == 2 && f.now >= 120000);
            check(Arrays.equals(f.slot, NciSession.baseline()));
            check(f.logs.contains("RESTORED exact baseline"));
        });
        test("unpaused discovery control", () -> {
            Fake f = new Fake(); f.session().temporary(false);
            check(f.writes == 2 && f.pauses == 0 && f.resumes == 0);
        });
        test("same value control", () -> {
            Fake f = new Fake(); f.session().sameValueControl(); check(f.writes == 1 && f.pauses == 0);
        });
        test("unknown initial owner", () -> {
            Fake f = new Fake(); f.slot = FOREIGN.clone();
            fails(() -> f.session().temporary(true), "Slot owner"); check(f.writes == 0 && f.pauses == 0);
        });
        test("existing temporary owner is not ours", () -> {
            Fake f = new Fake(); f.slot = NciSession.temporary();
            fails(() -> f.session().temporary(true), "Slot owner");
            check(f.writes == 0 && Arrays.equals(f.slot, NciSession.temporary()));
        });
        test("disabled ID1", () -> {
            Fake f = new Fake(); f.flags[0] = 2;
            fails(() -> f.session().temporary(true), "not enabled"); check(f.writes == 0);
        });
        test("truncated flags", () -> {
            Fake f = new Fake(); f.flags = new byte[] {1};
            fails(() -> f.session().temporary(true), "not enabled"); check(f.writes == 0);
        });
        test("owner changes while pausing", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("pause")) f.slot = FOREIGN.clone(); };
            fails(() -> f.session().temporary(true), "Slot owner");
            check(f.writes == 0 && f.resumes == 1 && Arrays.equals(f.slot, FOREIGN));
        });
        test("same target installed by another actor", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("pause")) f.slot = NciSession.temporary(); };
            fails(() -> f.session().temporary(true), "Slot owner");
            check(f.writes == 0 && Arrays.equals(f.slot, NciSession.temporary()));
        });
        test("flags change while pausing", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("pause")) f.flags[0] = 0; };
            fails(() -> f.session().temporary(true), "not enabled"); check(f.writes == 0 && f.resumes == 1);
        });
        test("pause not owned", () -> {
            Fake f = new Fake(); f.pauseResult = 0;
            fails(() -> f.session().temporary(true), "not started"); check(f.writes == 0 && f.resumes == 0);
        });
        test("unsupported pause budget", () -> {
            Fake f = new Fake(); f.maxPause = 4999;
            fails(() -> f.session().temporary(true), "too short"); check(f.pauses == 0 && f.writes == 0);
        });
        test("slow pause call", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("pause")) f.now += 16000; };
            fails(() -> f.session().temporary(true), "deadline"); check(f.writes == 0 && f.resumes == 0);
        });
        test("insufficient write reserve", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("read") && f.slotReads == 2) f.now += 13000; };
            fails(() -> f.session().temporary(true), "budget"); check(f.writes == 0 && f.resumes == 1);
        });
        test("expired during pre-write readback", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("read") && f.slotReads == 2) f.now += 16000; };
            fails(() -> f.session().temporary(true), "deadline"); check(f.writes == 0 && f.resumes == 0);
        });
        test("rejected write leaves baseline", () -> {
            Fake f = new Fake(); f.rejectFirstWrite = true;
            fails(() -> f.session().temporary(true), "SET rejected");
            check(f.writes == 1 && Arrays.equals(f.slot, NciSession.baseline()));
        });
        test("accepted write but response lost", () -> {
            Fake f = new Fake(); f.loseFirstResponse = true;
            fails(() -> f.session().temporary(true), "SET response lost");
            check(f.writes == 2 && f.pauses == 2 && Arrays.equals(f.slot, NciSession.baseline()));
        });
        test("deadline exceeded during SET", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("write") && f.writes == 1) f.now += 16000; };
            fails(() -> f.session().temporary(true), "deadline");
            check(f.writes == 2 && f.resumes == 1 && Arrays.equals(f.slot, NciSession.baseline()));
        });
        test("readback changed owner preserves primary failure", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("read") && f.slotReads == 4) f.slot = FOREIGN.clone(); };
            Exception error = fails(() -> f.session().temporary(true), "Slot owner");
            check(error.getSuppressed().length == 1 && f.writes == 1 && Arrays.equals(f.slot, FOREIGN));
        });
        test("stop before session never writes", () -> {
            Fake f = new Fake();
            f.session().temporary(true, new NciSession.Control() {
                @Override public boolean keepRunning() { return false; }
                @Override public void beforeWrite() { throw new AssertionError("Unexpected preparation"); }
            });
            check(f.writes == 0 && f.pauses == 0 && f.slotReads == 0);
        });
        test("stop during window restores early", () -> {
            Fake f = new Fake();
            f.session().temporary(true, new NciSession.Control() {
                @Override public boolean keepRunning() { return f.writes == 0; }
            });
            check(f.writes == 2 && f.sleeps == 0 && f.now < 120000);
            check(Arrays.equals(f.slot, NciSession.baseline()) && f.logs.contains("STOP_REQUESTED"));
        });
        test("lost guardian during window restores before reporting failure", () -> {
            Fake f = new Fake();
            fails(() -> f.session().temporary(true, new NciSession.Control() {
                @Override public boolean keepRunning() throws IOException {
                    if (f.writes > 0) throw new IOException("guardian unavailable");
                    return true;
                }
            }), "guardian unavailable");
            check(f.writes == 2 && Arrays.equals(f.slot, NciSession.baseline()));
        });
        test("failed durable preparation prevents SET", () -> {
            Fake f = new Fake();
            boolean[] cancelled = {false};
            fails(() -> f.session().temporary(true, new NciSession.Control() {
                @Override public void beforeWrite() throws IOException { throw new IOException("journal write failed"); }
                @Override public void noWriteAttempted() { cancelled[0] = true; }
            }), "journal write failed");
            check(cancelled[0] && f.writes == 0 && f.resumes == 1 && Arrays.equals(f.slot, NciSession.baseline()));
        });
        test("disarming error does not hide failed preparation", () -> {
            Fake f = new Fake();
            Exception error = fails(() -> f.session().temporary(true, new NciSession.Control() {
                @Override public void beforeWrite() throws IOException { throw new IOException("prepare failed"); }
                @Override public void noWriteAttempted() throws IOException { throw new IOException("disarm failed"); }
            }), "prepare failed");
            check(f.writes == 0 && error.getSuppressed().length == 1);
            check(error.getSuppressed()[0].getMessage().equals("disarm failed"));
        });
        test("owner changed during durable preparation prevents SET", () -> {
            Fake f = new Fake();
            fails(() -> f.session().temporary(true, new NciSession.Control() {
                @Override public void beforeWrite() { f.slot = FOREIGN.clone(); }
            }), "Slot owner");
            check(f.writes == 0 && f.resumes == 1 && Arrays.equals(f.slot, FOREIGN));
        });
        test("preparation cannot claim another actor's identical temporary image", () -> {
            Fake f = new Fake();
            fails(() -> f.session().temporary(true, new NciSession.Control() {
                @Override public void beforeWrite() { f.slot = NciSession.temporary(); }
            }), "Slot owner");
            check(f.writes == 0 && Arrays.equals(f.slot, NciSession.temporary()));
        });
        test("slow durable preparation exhausts pause budget", () -> {
            Fake f = new Fake();
            fails(() -> f.session().temporary(true, new NciSession.Control() {
                @Override public void beforeWrite() { f.now += 16000; }
            }), "deadline");
            check(f.writes == 0 && f.resumes == 0);
        });
        test("last pre-write read consumes reserve", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("read") && f.slotReads == 3) f.now += 13000; };
            fails(() -> f.session().temporary(true), "budget");
            check(f.writes == 0 && f.resumes == 1);
        });
        test("failed resume is not success", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("resume")) f.resumeResult = f.resumes == 1 ? 0 : 1; };
            fails(() -> f.session().temporary(true), "resume not confirmed");
            check(f.writes == 2 && Arrays.equals(f.slot, NciSession.baseline()));
        });
        test("owner changed by resume", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("resume")) f.slot = FOREIGN.clone(); };
            fails(() -> f.session().temporary(true), "Slot owner"); check(f.writes == 1);
        });
        test("framework resets during window", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("sleep")) f.slot = NciSession.baseline(); };
            fails(() -> f.session().temporary(true), "Slot owner"); check(f.writes == 1 && f.sleeps == 1);
        });
        test("competing service during window", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("sleep")) f.slot = FOREIGN.clone(); };
            Exception error = fails(() -> f.session().temporary(true), "Slot owner");
            check(error.getSuppressed().length == 1 && f.writes == 1 && Arrays.equals(f.slot, FOREIGN));
        });
        test("disabled slot is not reactivated by restore", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("sleep")) f.flags[0] = 0; };
            fails(() -> f.session().temporary(true), "not enabled"); check(f.writes == 1);
        });
        test("service loss does not trigger blind writes", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("sleep")) f.ready = false; };
            Exception error = fails(() -> f.session().temporary(true), "service unavailable");
            check(error.getSuppressed().length == 1 && f.writes == 1);
        });
        test("ownership rechecked during recovery pause", () -> {
            Fake f = new Fake(); f.event = e -> { if (e.equals("pause") && f.pauses == 2) f.slot = FOREIGN.clone(); };
            fails(() -> f.session().temporary(true), "Slot owner");
            check(f.writes == 1 && f.resumes == 2 && Arrays.equals(f.slot, FOREIGN));
        });
        test("already restored", () -> {
            Fake f = new Fake(); f.session().restore(true); check(f.writes == 0 && f.pauses == 0);
        });
        test("explicit restore", () -> {
            Fake f = new Fake(); f.slot = NciSession.temporary(); f.session().restore(true);
            check(f.writes == 1 && Arrays.equals(f.slot, NciSession.baseline()));
        });
        test("explicit restore rejects foreign owner", () -> {
            Fake f = new Fake(); f.slot = FOREIGN.clone();
            fails(() -> f.session().restore(true), "RESTORE SKIPPED"); check(f.writes == 0 && f.pauses == 0);
        });
        test("shell UID boundary", () -> {
            NciLab.requireShellUid(2000);
            fails(() -> NciLab.requireShellUid(0), "requires shell");
            fails(() -> NciLab.requireShellUid(10394), "requires shell");
        });
        test("defensive identity copies", () -> {
            byte[] value = NciSession.baseline(); value[0] = 0;
            check(NciSession.baseline()[0] == 0x40);
            value = NciSession.temporary(); value[0] = 0;
            check(NciSession.temporary()[0] == (byte) 0x88);
        });
        System.out.println("PASS NciSession " + cases + " scenarios");
    }
}
