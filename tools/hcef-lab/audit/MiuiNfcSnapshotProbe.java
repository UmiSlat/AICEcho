import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Reviewed-ROM export only. This separate executable never restores or reloads NFC. */
public final class MiuiNfcSnapshotProbe {
    private static final String PACKAGE = "com.android.nfc";
    private static final String SIGNER =
            "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025";
    private static final String APK_SHA256 =
            "6f6e8ff4c3dbb447200b78a8cf8a1843dd6841d11e404f3eae05f22fe683a62f";
    private static final String APK = "/system/app/Nfc_st/Nfc_st.apk";
    private static final Path OUTPUT = Path.of("/data/local/tmp/aicecho-reviewed-nfc-snapshot.ab");
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"--export-reviewed-nfc".equals(args[0])) {
            throw new IllegalArgumentException("Explicit --export-reviewed-nfc required after approval");
        }
        if (android.os.Process.myUid() != 2000 || Build.VERSION.SDK_INT != 36
                || !"OS3.0.308.0.WOBCNXM".equals(Build.VERSION.INCREMENTAL)) {
            throw new SecurityException("Only shell UID 2000 on the reviewed ROM is accepted");
        }
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        ApplicationInfo app = info.applicationInfo;
        Signature[] signers = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        if (info.getLongVersionCode() != 36 || app == null || app.uid != 1027
                || !PACKAGE.equals(app.packageName) || !APK.equals(app.sourceDir)
                || !"com.android.nfc.NfcBackupAgent".equals(app.backupAgentName)
                || (app.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                || (app.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) == 0
                || (app.flags & ApplicationInfo.FLAG_FULL_BACKUP_ONLY) != 0
                || signers == null || signers.length != 1
                || !SIGNER.equals(hash(signers[0].toByteArray()))
                || !APK_SHA256.equals(hashFile(Path.of(APK)))) {
            throw new SecurityException("NFC package, agent, owner, flags, signer or APK mismatch");
        }
        int enabledBefore = pm.getApplicationEnabledSetting(PACKAGE);
        if (!app.enabled || (enabledBefore != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && enabledBefore != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
            throw new IllegalStateException("NFC package must already be enabled");
        }
        Class<?> iface = Class.forName("miui.app.backup.IBackupManager");
        Class<?> observer = Class.forName("miui.app.backup.IBackupServiceStateObserver");
        Class<?> resultObserver = Class.forName("miui.app.backup.IPackageBackupRestoreObserver");
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "MiuiBackup");
        if (binder == null) throw new IllegalStateException("MiuiBackup unavailable");
        Object service = Class.forName("miui.app.backup.IBackupManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Method backup = iface.getMethod("backupPackage", ParcelFileDescriptor.class,
                ParcelFileDescriptor.class, String.class, int.class, String.class, String.class,
                boolean.class, boolean.class, boolean.class, boolean.class, resultObserver);
        Method acquire = iface.getMethod("acquire", observer, IBinder.class);
        Method release = iface.getMethod("release", observer);
        if (!Boolean.TRUE.equals(iface.getMethod("isServiceIdle").invoke(service))) {
            throw new IllegalStateException("Vendor service is busy; no takeover");
        }
        ExecutorService workers = Executors.newCachedThreadPool(task -> {
            Thread worker = new Thread(task, "reviewed-nfc-export");
            worker.setDaemon(true);
            return worker;
        });
        boolean acquired = false;
        boolean completed = false;
        // Retain the CREATE_NEW descriptor; never reopen or overwrite a path after reserving it.
        try (FileChannel output = FileChannel.open(OUTPUT,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try {
                System.out.println("PREFLIGHT_OK uid=2000 reviewedNfc=true exportOnly=true");
                acquired = Boolean.TRUE.equals(acquire.invoke(service, null, new Binder()));
                if (!acquired) throw new IllegalStateException("Acquire refused; no backup submitted");
                Future<byte[]> reader = workers.submit(() -> readBounded(pipe[0]));
                Future<?> operation = workers.submit(() -> {
                    backup.invoke(service, pipe[1], null, PACKAGE, 0, "", "",
                            false, false, false, false, null);
                    return null;
                });
                operation.get(90, TimeUnit.SECONDS);
                completed = true;
                pipe[1].close();
                byte[] archive = reader.get(10, TimeUnit.SECONDS);
                ByteBuffer buffer = ByteBuffer.wrap(archive);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
                System.out.println("CALL_RETURNED bytes=" + archive.length + " sha256=" + hash(archive));
                System.out.println("IDENTITY_SNAPSHOT_NOT_YET_VERIFIED");
            } catch (Exception failure) {
                System.err.println("EXPORT_UNVERIFIED callCompleted=" + completed + " error=" + failure);
                throw failure;
            } finally {
                try {
                    if (acquired) {
                        workers.submit(() -> { release.invoke(service, new Object[] {null}); return null; })
                                .get(15, TimeUnit.SECONDS);
                        System.out.println("VENDOR_RELEASE_RETURNED");
                    }
                    System.out.println("NFC_PACKAGE_ENABLED_STATE_UNCHANGED="
                            + (enabledBefore == pm.getApplicationEnabledSetting(PACKAGE)));
                } finally {
                    pipe[0].close();
                    pipe[1].close();
                }
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static byte[] readBounded(ParcelFileDescriptor source) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(source);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count > MAX_BYTES - out.size()) throw new IllegalStateException("Export exceeds 8 MiB");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hashFile(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
