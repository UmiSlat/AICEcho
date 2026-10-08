package io.github.umislat.aimesimulator.provisioning

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Export only. No restore, package-state setter, NFC reload or arbitrary target method. */
internal class MiuiNfcExporter(private val context: Context) : NfcSnapshotWorkflow.Backend {
    private val packages = context.packageManager
    private var boot = -1
    private var started = -1L
    private var wall = -1L

    @Suppress("DEPRECATION")
    fun candidate(): Boolean = runCatching {
        check(Process.myUid() == 2000 && Build.VERSION.SDK_INT == 36 && Build.VERSION.INCREMENTAL == ROM)
        val info = packages.getPackageInfo(NfcSnapshotArchive.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val app = checkNotNull(info.applicationInfo)
        val signers = checkNotNull(info.signingInfo).apkContentsSigners
        check(info.longVersionCode == 36L && app.uid == 1027 && app.sourceDir == APK && app.enabled)
        check(app.backupAgentName == "com.android.nfc.NfcBackupAgent")
        check(app.flags and ApplicationInfo.FLAG_SYSTEM != 0 && app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        check(app.flags and ApplicationInfo.FLAG_FULL_BACKUP_ONLY == 0 && signers.size == 1)
        check(RestoreProbeArchive.sha256(signers.single().toByteArray()) == NfcSnapshotArchive.SIGNER)
        check(ShellBootCount.read() >= 0)
        true
    }.getOrDefault(false)

    override fun preflight() {
        check(candidate())
        // Do not silently use an older vendor/boot copy of a bundled parser dependency.
        for (type in listOf(org.apache.commons.compress.archivers.tar.TarArchiveInputStream::class.java,
            org.apache.commons.io.IOUtils::class.java, org.apache.commons.lang3.ArrayUtils::class.java,
            org.apache.commons.codec.binary.Base64::class.java)) {
            check(type.classLoader === javaClass.classLoader)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        File(APK).inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        check(RestoreProbeArchive.hex(digest.digest()) == APK_HASH)
        boot = ShellBootCount.read()
        started = SystemClock.elapsedRealtime()
        wall = System.currentTimeMillis()
    }

    override fun export(): ByteArray {
        check(Process.myUid() == 2000 && boot >= 0)
        val type = Class.forName("miui.app.backup.IBackupManager")
        val observer = Class.forName("miui.app.backup.IBackupServiceStateObserver")
        val resultObserver = Class.forName("miui.app.backup.IPackageBackupRestoreObserver")
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "MiuiBackup") as? IBinder ?: error("Vendor backup unavailable")
        val service = Class.forName("miui.app.backup.IBackupManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        check(type.getMethod("isServiceIdle").invoke(service) == true)
        val backup = type.getMethod("backupPackage", ParcelFileDescriptor::class.java,
            ParcelFileDescriptor::class.java, String::class.java, Int::class.javaPrimitiveType,
            String::class.java, String::class.java, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, resultObserver)
        val executor = Executors.newCachedThreadPool { task -> Thread(task, "nfc-snapshot").apply { isDaemon = true } }
        val before = packages.getApplicationEnabledSetting(NfcSnapshotArchive.PACKAGE)
        val pipe = ParcelFileDescriptor.createPipe()
        var acquired = false
        try {
            acquired = type.getMethod("acquire", observer, IBinder::class.java).invoke(service, null, Binder()) == true
            check(acquired)
            val read = executor.submit<ByteArray> {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        check(size <= NfcSnapshotArchive.MAX_ARCHIVE - out.size())
                        out.write(buffer, 0, size)
                    }
                    out.toByteArray()
                }
            }
            executor.submit<Unit> { backup.invoke(service, pipe[1], null, NfcSnapshotArchive.PACKAGE, 0,
                "", "", false, false, false, false, null) }.get(90, TimeUnit.SECONDS)
            pipe[1].close()
            return read.get(10, TimeUnit.SECONDS)
        } finally {
            try {
                if (acquired) executor.submit<Unit> { type.getMethod("release", observer).invoke(service, null) }
                    .get(15, TimeUnit.SECONDS)
                check(before == packages.getApplicationEnabledSetting(NfcSnapshotArchive.PACKAGE))
            } finally { pipe.forEach { runCatching { it.close() } }; executor.shutdownNow() }
        }
    }

    override fun stamp(parsed: NfcSnapshotArchive.Parsed): NfcSnapshotWorkflow.Capture {
        check(boot >= 0 && boot == ShellBootCount.read())
        check(SystemClock.elapsedRealtime() >= started)
        return NfcSnapshotWorkflow.Capture(parsed, boot, started, wall, Build.FINGERPRINT)
    }

    companion object {
        const val ROM = "OS3.0.308.0.WOBCNXM"
        private const val APK = "/system/app/Nfc_st/Nfc_st.apk"
        private const val APK_HASH = "6f6e8ff4c3dbb447200b78a8cf8a1843dd6841d11e404f3eae05f22fe683a62f"
    }
}
