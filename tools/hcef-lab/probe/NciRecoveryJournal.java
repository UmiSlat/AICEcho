import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/** One write-ahead record; callers serialize state changes with the probe lock. */
public final class NciRecoveryJournal {
    public static final int PREPARED = 0, ARMED = 1, DONE = 2;
    public static final long MAX_AGE_MS = 300000;
    private final Path directory;
    private final Path record;

    public static final class Entry {
        public final String token, boot;
        public final long created;
        public final int phase;
        Entry(String token, String boot, long created, int phase) throws IOException {
            requireUuid(token); requireUuid(boot);
            if (created < 0 || created > Long.MAX_VALUE - MAX_AGE_MS || phase < 0 || phase > DONE) {
                throw new IOException("Invalid recovery record fields");
            }
            this.token = token; this.boot = boot; this.created = created; this.phase = phase;
        }
        public void validate(String expectedToken, String currentBoot, long now) throws IOException {
            if (!token.equals(expectedToken) || !boot.equals(currentBoot)) throw new IOException("Recovery session/boot changed");
            if (now < created || now - created >= MAX_AGE_MS) throw new IOException("Recovery lease expired");
        }
    }

    public NciRecoveryJournal(Path directory) throws IOException {
        this.directory = directory;
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory, attributes(directory, "rwx------"));
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid recovery directory");
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        record = directory.resolve("lease.bin");
    }

    public boolean exists() { return Files.exists(record, LinkOption.NOFOLLOW_LINKS); }
    public void requireClear() throws IOException {
        if (exists()) throw new IOException("Pending recovery record; new writes refused");
    }
    public Entry create(String boot, long now) throws IOException {
        requireClear();
        Entry entry = new Entry(UUID.randomUUID().toString(), boot, now, PREPARED);
        write(entry);
        return entry;
    }
    public Entry read() throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(record, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(257); }
        if (bytes.length < 33 || bytes.length > 256) throw new IOException("Invalid recovery record length");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(payload), Arrays.copyOfRange(bytes, payload.length, bytes.length))) {
            throw new IOException("Recovery record checksum mismatch");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != 0x48434631) throw new IOException("Unknown recovery format");
            Entry entry = new Entry(input.readUTF(), input.readUTF(), input.readLong(), input.readUnsignedByte());
            if (input.read() != -1) throw new IOException("Trailing recovery data");
            return entry;
        }
    }
    public void phase(Entry expected, int phase) throws IOException {
        Entry current = read();
        if (!current.token.equals(expected.token) || !current.boot.equals(expected.boot)
                || current.created != expected.created || phase < current.phase) {
            throw new IOException("Recovery record ownership/phase changed");
        }
        write(new Entry(current.token, current.boot, current.created, phase));
    }
    public void clear(Entry expected) throws IOException {
        Entry current = read();
        if (!current.token.equals(expected.token) || !current.boot.equals(expected.boot)
                || current.created != expected.created) throw new IOException("Recovery session changed");
        Files.delete(record);
        Files.deleteIfExists(signal("ready", current.token));
        Files.deleteIfExists(signal("stop", current.token));
    }
    public void signalReady(String token) throws IOException { createSignal(signal("ready", token)); }
    public boolean ready(String token) throws IOException { return hasSignal(signal("ready", token)); }
    public boolean stopped(String token) throws IOException { return hasSignal(signal("stop", token)); }
    public void stop(String token, String boot, long now) throws IOException {
        read().validate(token, boot, now);
        createSignal(signal("stop", token));
    }
    private boolean hasSignal(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != 0) {
            throw new IOException("Invalid recovery signal");
        }
        return true;
    }
    private void createSignal(Path path) throws IOException {
        if (hasSignal(path)) return;
        try { Files.createFile(path, attributes(path, "rw-------")); }
        catch (FileAlreadyExistsException concurrent) {
            if (!hasSignal(path)) throw concurrent;
        }
    }
    private Path signal(String type, String token) throws IOException {
        requireUuid(token);
        return directory.resolve(type + "-" + token);
    }
    public Path logPath(String token) throws IOException {
        requireUuid(token);
        Path path = directory.resolve("guard-" + token + ".log");
        Files.createFile(path, attributes(path, "rw-------"));
        return path;
    }
    private void write(Entry entry) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(0x48434631); out.writeUTF(entry.token); out.writeUTF(entry.boot);
            out.writeLong(entry.created); out.writeByte(entry.phase);
        }
        byte[] payload = buffer.toByteArray();
        buffer.writeBytes(digest(payload));
        Path temporary = directory.resolve("write-" + UUID.randomUUID() + ".tmp");
        try {
            Files.createFile(temporary, attributes(temporary, "rw-------"));
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(buffer.toByteArray());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void requireUuid(String value) throws IOException {
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new IOException("Invalid session/boot UUID", invalid); }
    }
    private static FileAttribute<?>[] attributes(Path path, String permissions) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))}
                : new FileAttribute<?>[0];
    }
}
