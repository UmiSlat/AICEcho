import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class NciProbeLockTest {
    private static int count;
    private static void check(boolean result) { count++; if (!result) throw new AssertionError(count); }
    private static Process child(String mode, Path path) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                NciProbeLockTest.class.getName(), mode, path.toString()).redirectErrorStream(true).start();
    }
    private static void blocked(Path path) throws IOException {
        boolean refused = false;
        try (NciProbeLock ignored = NciProbeLock.acquire(path)) { }
        catch (IOException expected) { refused = true; }
        check(refused);
    }
    private static int exit(Process child) throws Exception {
        try {
            if (!child.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Child timeout");
            return child.exitValue();
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            try (NciProbeLock ignored = NciProbeLock.acquire(Path.of(args[1]))) {
                if (args[0].equals("hold")) {
                    System.out.println("HELD"); System.out.flush(); Thread.sleep(30000);
                }
            } catch (IOException busy) { System.exit(23); }
            return;
        }
        Path path = Files.createTempFile("hcef-lab-lock-test-", ".lock");
        try {
            try (NciProbeLock ignored = NciProbeLock.acquire(path)) {
                blocked(path);
                check(exit(child("try", path)) == 23);
            }
            check(exit(child("try", path)) == 0);
            Process holder = child("hold", path);
            try {
                FutureTask<String> ready = new FutureTask<>(() -> new BufferedReader(
                        new InputStreamReader(holder.getInputStream())).readLine());
                Thread thread = new Thread(ready, "lock-test-handshake"); thread.setDaemon(true); thread.start();
                check("HELD".equals(ready.get(5, TimeUnit.SECONDS)));
                blocked(path);
            } finally {
                holder.destroyForcibly(); check(holder.waitFor(5, TimeUnit.SECONDS));
            }
            check(exit(child("try", path)) == 0);
        } finally { Files.deleteIfExists(path); }
        System.out.println("PASS NciProbeLock " + count + " checks (including subprocess death)");
    }
}
