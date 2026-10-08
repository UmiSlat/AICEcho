import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Recovery starts only after the worker releases the same OS lock. */
public final class NciRecovery {
    public static void watch(Path lockPath, NciRecoveryJournal journal, String token, String boot,
            NciSession.Controller controller, NciSession.Time time, Consumer<String> log) throws Exception {
        NciRecoveryJournal.Entry entry = journal.read();
        entry.validate(token, boot, time.millis());
        if (entry.phase != NciRecoveryJournal.PREPARED) {
            throw new IOException("Guardian must attach before any write; late recovery process refused");
        }
        controller.checkReady();
        journal.signalReady(token);
        log.accept("GUARD_READY " + token);
        long waitDeadline = entry.created + 240000;
        while (time.millis() < waitDeadline) {
            NciProbeLock lock;
            try { lock = NciProbeLock.acquire(lockPath); }
            catch (NciProbeLock.BusyException busy) { time.sleep(250); continue; }
            try (NciProbeLock ignored = lock) {
                entry = journal.read();
                entry.validate(token, boot, time.millis());
                if (entry.phase != NciRecoveryJournal.DONE) {
                    // Allow the worker's maximum polling pause to expire after handoff.
                    time.sleep(15000);
                    entry.validate(token, boot, time.millis());
                    if (entry.phase == NciRecoveryJournal.ARMED) {
                        controller.checkReady();
                        new NciSession(controller, time, log).restore(true);
                    } else {
                        log.accept("GUARD_UNARMED: no configuration write to recover");
                    }
                }
                journal.clear(entry);
                log.accept("GUARD_COMPLETE " + token);
                return;
            }
        }
        throw new IOException("Worker still owns probe lock; recovery pending, lock never stolen");
    }
}
