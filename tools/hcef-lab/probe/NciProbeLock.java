import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Serializes cooperating probe processes; the lock file must never be unlinked. */
public final class NciProbeLock implements AutoCloseable {
    public static final class BusyException extends IOException {
        private static final long serialVersionUID = 1L;
        BusyException() { super("Another lab probe holds the lock"); }
    }
    private final FileChannel channel;
    private final FileLock lock;

    private NciProbeLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static NciProbeLock acquire(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new BusyException();
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            }
            return new NciProbeLock(channel, lock);
        } catch (IOException | RuntimeException error) {
            try { channel.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            if (error instanceof OverlappingFileLockException) {
                throw new BusyException();
            }
            throw error;
        }
    }

    @Override public void close() throws IOException {
        try { lock.release(); }
        finally { channel.close(); }
    }
}
