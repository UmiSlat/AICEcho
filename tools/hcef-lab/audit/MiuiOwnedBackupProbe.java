import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Supervised, shell-only export of a pinned owned fixture. Never restores data. */
public final class MiuiOwnedBackupProbe {
    private static final String PACKAGE = "io.github.umislat.restorepathlab";
    private static final String SIGNER =
            "3bf1577c690a4a57534afe7338b9534fdba659deedce1e6f3ed408ae750545dc";
    private static final Path OUTPUT = Path.of("/data/local/tmp/aimesim-miui-owned-backup.ab");
    private static final int MAX_BYTES = 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"--backup-owned-lab".equals(args[0])) {
            throw new IllegalArgumentException("Explicit --backup-owned-lab required");
        }
        if (android.os.Process.myUid() != 2000) {
            throw new SecurityException("Shell UID 2000 only; Root is not accepted");
        }
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        ApplicationInfo app = info.applicationInfo;
        Signature[] signers = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        if (info.getLongVersionCode() != 1 || app == null || app.uid < 10000 || app.uid >= 100000
                || !PACKAGE.equals(app.packageName)
                || !((PACKAGE + ".MarkerBackupAgent").equals(app.backupAgentName))
                || (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0
                || (app.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) == 0
                || (app.flags & ApplicationInfo.FLAG_FULL_BACKUP_ONLY) != 0
                || signers == null || signers.length != 1
                || !SIGNER.equals(sha256(signers[0].toByteArray()))) {
            throw new SecurityException("Owned Lab package, agent, flags or signer mismatch");
        }
        int enabledBefore = pm.getApplicationEnabledSetting(PACKAGE);
        if (!app.enabled || (enabledBefore != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && enabledBefore != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
            throw new IllegalStateException("Lab must already be enabled");
        }
        if (Files.exists(OUTPUT)) throw new IllegalStateException("Prior output exists; do not retry blindly");
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
            throw new IllegalStateException("Vendor backup service is busy");
        }
        // Reserve a private, non-overwriting result before acquiring the vendor service.
        Files.createFile(OUTPUT, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        System.out.println("PREFLIGHT_OK uid=2000 ownedLab=true keyValueAgent=true");
        ExecutorService workers = Executors.newCachedThreadPool(task -> {
            Thread worker = new Thread(task, "owned-backup-probe");
            worker.setDaemon(true);
            return worker;
        });
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        boolean acquired = false;
        boolean completed = false;
        try {
            acquired = Boolean.TRUE.equals(acquire.invoke(service, null, new Binder()));
            if (!acquired) throw new IllegalStateException("Acquire refused; no backup submitted");
            Future<byte[]> reader = workers.submit(() -> readBounded(pipe[0]));
            Future<?> operation = workers.submit(() -> {
                // No APK, no skip-data, no XSpace, no observer and no forced-backup argument.
                backup.invoke(service, pipe[1], null, PACKAGE, 0, "", "",
                        false, false, false, false, null);
                return null;
            });
            operation.get(90, TimeUnit.SECONDS);
            completed = true;
            pipe[1].close();
            byte[] archive = reader.get(10, TimeUnit.SECONDS);
            Files.write(OUTPUT, archive);
            System.out.println("CALL_RETURNED bytes=" + archive.length + " sha256=" + sha256(archive));
            System.out.println("ARCHIVE_CONTENT_NOT_YET_VERIFIED");
        } catch (Exception failure) {
            System.err.println("EXPORT_UNVERIFIED callCompleted=" + completed + " error=" + failure);
            throw failure;
        } finally {
            try {
                if (acquired) {
                    // Release may cancel a still-running vendor task; it is not proof of completion.
                    workers.submit(() -> { release.invoke(service, new Object[] {null}); return null; })
                            .get(15, TimeUnit.SECONDS);
                    System.out.println("VENDOR_RELEASE_RETURNED");
                }
                int enabledAfter = pm.getApplicationEnabledSetting(PACKAGE);
                System.out.println("LAB_ENABLED_STATE_UNCHANGED=" + (enabledBefore == enabledAfter));
            } finally {
                pipe[0].close();
                pipe[1].close();
                workers.shutdownNow();
            }
        }
    }

    private static byte[] readBounded(ParcelFileDescriptor source) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(source);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count > MAX_BYTES - out.size()) throw new IllegalStateException("Archive exceeds 1 MiB");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
