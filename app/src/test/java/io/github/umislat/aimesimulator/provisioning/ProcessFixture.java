package io.github.umislat.aimesimulator.provisioning;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/** Runs in a separate host JVM; never deployed or invoked on Android. */
public final class ProcessFixture {
    private static final Class<?> HANDLE;
    static {
        try { HANDLE = Class.forName("java.lang.ProcessHandle"); }
        catch (ClassNotFoundException error) { throw new ExceptionInInitializerError(error); }
    }

    public static boolean isAlive(long pid) throws Exception {
        Optional<?> handle = (Optional<?>) HANDLE.getMethod("of", long.class).invoke(null, pid);
        return handle.isPresent() && (Boolean) HANDLE.getMethod("isAlive").invoke(handle.get());
    }

    public static void main(String[] args) throws Exception {
        Object self = HANDLE.getMethod("current").invoke(null);
        long pid = (Long) HANDLE.getMethod("pid").invoke(self);
        Files.write(new File(args[1]).toPath(), Long.toString(pid).getBytes(StandardCharsets.US_ASCII));
        switch (args[0]) {
            case "binary-stderr":
                System.err.write(new byte[16384]);
                byte[] bytes = new byte[16384];
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
                System.out.write(bytes);
                break;
            case "echo":
                System.in.transferTo(System.out);
                break;
            case "no-read":
                Thread.sleep(8000);
                break;
            case "flood":
                System.out.write(new byte[8192]);
                System.out.flush();
                Thread.sleep(8000);
                break;
            case "close-input":
                System.in.close();
                Thread.sleep(300);
                break;
            case "nonzero":
                System.exit(17);
                break;
            default:
                throw new IllegalArgumentException("Unknown test mode");
        }
    }
}
