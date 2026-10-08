import java.util.Arrays;
import java.util.function.Consumer;

/** Bounded transitions for the synthetic lab identity, independent of Binder. */
public final class NciSession {
    public interface Controller {
        void checkReady() throws Exception;
        byte[] readSlot() throws Exception;
        byte[] readFlags() throws Exception;
        void writeSlot(byte[] value) throws Exception;
        long maxPauseMillis() throws Exception;
        int pause(long millis) throws Exception;
        int resume() throws Exception;
    }

    public interface Time {
        long millis();
        void sleep(long millis) throws Exception;
    }

    public interface Control {
        default void beforeWrite() throws Exception { }
        default boolean keepRunning() throws Exception { return true; }
        default void noWriteAttempted() throws Exception { }
    }

    private interface Preparation { void run() throws Exception; }

    private static final byte[] BASELINE = NciTrace.unhex("400002FE6C616232303100F1000000014300");
    private static final byte[] TEMPORARY = NciTrace.unhex("88B402FE6C616232303100F1000000014300");
    private static final long WINDOW_MS = 120000;
    private static final long WRITE_RESERVE_MS = 3000;
    private final Controller controller;
    private final Time time;
    private final Consumer<String> log;

    public NciSession(Controller controller, Time time, Consumer<String> log) {
        this.controller = controller;
        this.time = time;
        this.log = log;
    }

    public static byte[] baseline() { return BASELINE.clone(); }
    public static byte[] temporary() { return TEMPORARY.clone(); }

    public void sameValueControl() throws Exception {
        transition(BASELINE, BASELINE, false, () -> {}, () -> {});
        log.accept("SAME_VALUE_SET accepted");
    }

    public void temporary(boolean paused) throws Exception {
        temporary(paused, new Control() {});
    }

    public void temporary(boolean paused, Control control) throws Exception {
        boolean[] attempted = {false};
        Exception failure = null;
        try {
            if (!control.keepRunning()) { log.accept("STOPPED before write"); return; }
            transition(BASELINE, TEMPORARY, paused, control::beforeWrite, () -> attempted[0] = true);
            log.accept("SC_CHANGED_READBACK_OK; RF test window 120 seconds; automatic restore follows");
            long deadline = time.millis() + WINDOW_MS;
            while (true) {
                if (!control.keepRunning()) { log.accept("STOP_REQUESTED"); break; }
                long remaining = deadline - time.millis();
                if (remaining <= 0) break;
                time.sleep(Math.min(5000, remaining));
                if (time.millis() < deadline) requireSlot(TEMPORARY);
            }
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            // A lost SET response can hide an accepted write. Inspect before recovery,
            // but never claim a temporary slot if no write was attempted by this session.
            try {
                if (attempted[0]) restore(paused);
                else control.noWriteAttempted();
            } catch (Exception cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    public void restore(boolean paused) throws Exception {
        controller.checkReady();
        byte[] current = controller.readSlot();
        if (Arrays.equals(current, BASELINE)) {
            log.accept("RESTORE not needed: baseline unchanged or restored by framework");
            return;
        }
        if (!Arrays.equals(current, TEMPORARY)) {
            throw new IllegalStateException("RESTORE SKIPPED: different slot owner/config");
        }
        transition(TEMPORARY, BASELINE, paused, () -> {}, () -> {});
        log.accept("RESTORED exact baseline");
    }

    private void requireSlot(byte[] expected) throws Exception {
        controller.checkReady();
        byte[] flags = controller.readFlags();
        if (flags.length != 2 || (flags[0] & 1) == 0) {
            throw new IllegalStateException("ID1 not enabled");
        }
        if (!Arrays.equals(controller.readSlot(), expected)) {
            throw new IllegalStateException("Slot owner/config changed");
        }
    }

    private void transition(byte[] expected, byte[] target, boolean paused,
            Preparation prepare, Runnable beforeWrite) throws Exception {
        requireSlot(expected);
        if (!paused) {
            prepare.run();
            beforeWrite.run();
            controller.writeSlot(target.clone());
            requireSlot(target);
            return;
        }
        long pauseMs = Math.min(15000L, controller.maxPauseMillis());
        if (pauseMs < 5000) throw new IllegalStateException("Polling pause timeout too short");
        // Count the Binder call itself against the pause timeout. A timeout is not
        // an ownership token, so do not resume once our possible ownership expires.
        long deadline = time.millis() + pauseMs;
        int pausedResult = controller.pause(pauseMs);
        log.accept("pausePolling=" + pausedResult + " timeoutMs=" + pauseMs);
        if (pausedResult != 1) throw new IllegalStateException("Polling pause not started by probe");
        Exception failure = null;
        try {
            requireTime(deadline, 0);
            requireSlot(expected);
            requireTime(deadline, WRITE_RESERVE_MS);
            prepare.run();
            requireTime(deadline, WRITE_RESERVE_MS);
            requireSlot(expected);
            requireTime(deadline, WRITE_RESERVE_MS);
            beforeWrite.run();
            controller.writeSlot(target.clone());
            requireTime(deadline, 0);
            requireSlot(target);
            requireTime(deadline, 0);
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            try {
                requireTime(deadline, 0);
                int resumed = controller.resume();
                log.accept("resumePolling=" + resumed);
                if (resumed != 1) throw new IllegalStateException("Polling resume not confirmed");
            } catch (Exception cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
        requireSlot(target);
    }

    private void requireTime(long deadline, long reserve) {
        long remaining = deadline - time.millis();
        if (remaining <= 0) {
            throw new IllegalStateException("Polling pause deadline exhausted; no further write/resume");
        }
        if (remaining <= reserve) throw new IllegalStateException("Insufficient pause budget for write");
    }
}
