package io.github.umislat.aimesimulator.provisioning

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock

/** Fixed system-NFC actions, called only by the app-owned Shizuku service. */
internal class NfcProvisionBackend(private val context: Context) : NfcProvisionWorkflow.Backend {
    private val exporter = MiuiNfcExporter(context)

    override fun snapshot(): ByteArray {
        check(Process.myUid() == 2000)
        val token = RestoreProbeArchive.hex(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
        val result = NfcSnapshotWorkflow(exporter).run(token) {}
        if (result.state == NfcSnapshotWorkflow.State.UNKNOWN) throw NfcProvisionWorkflow.UncertainOperation()
        check(result.state == NfcSnapshotWorkflow.State.CAPTURED)
        return checkNotNull(result.capture).parsed.xml.copyOf()
    }

    @Suppress("DEPRECATION")
    override fun certificate(): ByteArray {
        val info = context.packageManager.getPackageInfo(NfcSnapshotArchive.PACKAGE,
            PackageManager.GET_SIGNING_CERTIFICATES)
        return checkNotNull(info.signingInfo).apkContentsSigners.single().toByteArray().also {
            check(RestoreProbeArchive.sha256(it) == NfcSnapshotArchive.SIGNER)
        }
    }

    override fun restore(archive: ByteArray): ProbeProcess.Result {
        check(Process.myUid() == 2000)
        return ProbeProcess.run(listOf("/system/bin/bu", "restore", "-user", "0"), archive, 90)
    }

    override fun reload(): Boolean {
        check(Process.myUid() == 2000)
        val oldPid = pid() ?: return false
        val crash = ProbeProcess.run(listOf("/system/bin/am", "crash", "--user", "0", "com.android.nfc"))
        if (crash.uncertain || crash.exitCode != 0) return false
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(500)
            val current = pid()
            if (current != null && current != oldPid) return true
        }
        return false
    }

    private fun pid(): String? {
        val result = ProbeProcess.run(listOf("/system/bin/pidof", "com.android.nfc"))
        return result.output.toString(Charsets.US_ASCII).trim()
            .takeIf { !result.uncertain && result.exitCode == 0 && it.matches(Regex("[1-9][0-9]*")) }
    }
}
