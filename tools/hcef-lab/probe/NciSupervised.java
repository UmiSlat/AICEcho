import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Synthetic session with an independent guardian, not a production IPC backend. */
public final class NciSupervised {
    public interface Launcher { Process start(String token, Path log) throws Exception; }

    public static void run(Path lockPath, NciRecoveryJournal journal, String boot,
            NciSession.Controller controller, NciSession.Time time, Launcher launcher,
            Consumer<String> log) throws Exception {
        Process guardian = null;
        Exception failure = null;
        try (NciProbeLock ignored = NciProbeLock.acquire(lockPath)) {
            journal.requireClear();
            controller.checkReady();
            NciRecoveryJournal.Entry entry = journal.create(boot, time.millis());
            log.accept("SESSION " + entry.token);
            try {
                guardian = launcher.start(entry.token, journal.logPath(entry.token));
            } catch (Exception launchFailure) {
                try { journal.clear(entry); }
                catch (Exception cleanup) { launchFailure.addSuppressed(cleanup); }
                throw launchFailure;
            }
            Process guard = guardian;
            long readyDeadline = time.millis() + 10000;
            while (!journal.ready(entry.token)) {
                if (!guard.isAlive()) throw new IOException("Recovery guardian exited before ready");
                if (time.millis() >= readyDeadline) throw new IOException("Recovery guardian readiness timeout");
                time.sleep(50);
            }
            new NciSession(controller, time, log).temporary(true, new NciSession.Control() {
                @Override public boolean keepRunning() throws Exception {
                    entry.validate(entry.token, boot, time.millis());
                    if (!guard.isAlive()) throw new IOException("Recovery guardian exited; ending session");
                    return !journal.stopped(entry.token);
                }
                @Override public void beforeWrite() throws Exception {
                    if (!keepRunning()) throw new IOException("Stop requested before write");
                    journal.phase(entry, NciRecoveryJournal.ARMED);
                }
                @Override public void noWriteAttempted() throws Exception {
                    // A live worker can disarm an intent that never reached SET.
                    journal.phase(entry, NciRecoveryJournal.DONE);
                }
            });
            journal.phase(entry, NciRecoveryJournal.DONE);
        } catch (Exception error) { failure = error; }

        // Release the worker lock before waiting: the guardian must acquire it to recover.
        if (guardian != null) {
            try {
                if (!guardian.waitFor(30, TimeUnit.SECONDS)) {
                    throw new IOException("Guardian remains live; recovery pending, inspect its log");
                }
                if (guardian.exitValue() != 0) throw new IOException("Guardian failed; recovery record retained");
            } catch (Exception cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }
}
