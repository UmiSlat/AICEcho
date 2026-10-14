package io.github.umislat.aicecho.provisioning

import android.content.Context
import android.os.Process
import android.os.SystemClock

/** Fixed system-NFC actions, called only by the app-owned Shizuku service. */
internal class NfcProvisionBackend(private val context: Context,
    private val keyValueExportVerified: () -> Boolean = { false }) : NfcProvisionWorkflow.Backend {
    private val exporter = AospNfcExporter(context)
    private var afterReload = false

    override fun baseline(): NfcProvisionWorkflow.Baseline {
        check(Process.myUid() == 2000)
        val capabilities = AospBackupCapabilities.inspect(context)
        check(capabilities.canExport)
        return try {
            runtimeSnapshot()
        } catch (failure: Exception) {
            if (failure !is NfcRuntimeBaseline.Failure && (failure !is NfcBackupException ||
                failure.reason != NfcBackupException.Reason.STORAGE_DOMAIN_UNAVAILABLE)) throw failure
            if (capabilities.keyValueOnly && !keyValueExportVerified()) throw failure
            snapshot()
        }
    }

    override fun runtimeSnapshot(): NfcProvisionWorkflow.Baseline {
        check(Process.myUid() == 2000)
        val deadline = SystemClock.elapsedRealtime() + if (afterReload) 10_000 else 0
        while (true) {
            try {
                val domain = NfcStorageDomain.inspect(context)
                val xml = NfcRuntimeBaseline.capture(context)
                if (NfcStorageDomain.inspect(context) != domain)
                    throw NfcBackupException(NfcBackupException.Reason.STORAGE_DOMAIN_CHANGED)
                afterReload = false
                return NfcProvisionWorkflow.Baseline(NfcProvisionPlan.BaselineSource.RUNTIME, xml, domain)
            } catch (failure: NfcRuntimeBaseline.Failure) {
                if (SystemClock.elapsedRealtime() >= deadline || failure.reason !in setOf(
                    NfcRuntimeBaseline.Reason.RUNTIME_BASELINE_CACHE_EMPTY,
                    NfcRuntimeBaseline.Reason.RUNTIME_BASELINE_CACHE_MISMATCH,
                    NfcRuntimeBaseline.Reason.RUNTIME_BASELINE_UNAVAILABLE)) throw failure
                // A new PID can precede Binder/cache readiness. Each capture acquires fresh interfaces.
                Thread.sleep(500)
            }
        }
    }

    override fun snapshot(): NfcProvisionWorkflow.Baseline {
        check(Process.myUid() == 2000)
        val token = RestoreProbeArchive.hex(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
        val result = NfcSnapshotWorkflow(exporter).run(token) {}
        if (result.state == NfcSnapshotWorkflow.State.UNKNOWN) throw NfcProvisionWorkflow.UncertainOperation()
        if (result.state != NfcSnapshotWorkflow.State.CAPTURED) throw NfcBackupException(
            runCatching { NfcBackupException.Reason.valueOf(result.detail) }.getOrDefault(NfcBackupException.Reason.BACKUP_FAILED))
        val parsed = checkNotNull(result.capture).parsed
        return NfcProvisionWorkflow.Baseline(NfcProvisionPlan.BaselineSource.FILE, parsed.xml.copyOf(), parsed.domain)
    }

    override fun metadata() = NfcBackupMetadata.read(context)

    override fun restore(archive: ByteArray): ProbeProcess.Result {
        check(Process.myUid() == 2000)
        check(AospBackupCapabilities.inspect(context).canExport)
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
            if (current != null && current != oldPid) {
                afterReload = true
                return true
            }
        }
        return false
    }

    private fun pid(): String? {
        val result = ProbeProcess.run(listOf("/system/bin/pidof", "com.android.nfc"))
        return result.output.toString(Charsets.US_ASCII).trim()
            .takeIf { !result.uncertain && result.exitCode == 0 && it.matches(Regex("[1-9][0-9]*")) }
    }
}
