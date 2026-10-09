package io.github.umislat.aimesimulator.provisioning

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock

/** Standard shell backup only. No OEM Binder calls, root fallback or transport configuration. */
internal class AospNfcExporter(private val context: Context) : NfcSnapshotWorkflow.Backend {
    private var expected: NfcBackupMetadata? = null
    private var boot = -1
    private var started = -1L
    private var wall = -1L

    override fun preflight() {
        if (!AospBackupCapabilities.inspect(context).canExport) {
            throw NfcBackupException(NfcBackupException.Reason.UNSUPPORTED)
        }
        for (type in listOf(org.apache.commons.compress.archivers.tar.TarArchiveInputStream::class.java,
            org.apache.commons.io.IOUtils::class.java, org.apache.commons.lang3.ArrayUtils::class.java,
            org.apache.commons.codec.binary.Base64::class.java)) {
            check(type.classLoader === javaClass.classLoader)
        }
        expected = NfcBackupMetadata.read(context)
        boot = ShellBootCount.read().also { check(it >= 0) }
        started = SystemClock.elapsedRealtime()
        wall = System.currentTimeMillis()
    }

    override fun metadata() = checkNotNull(expected)

    override fun export(): ByteArray {
        check(Process.myUid() == 2000 && boot >= 0 && expected != null)
        val result = ProbeProcess.run(command, seconds = 90, binaryOutputLimit = NfcSnapshotArchive.MAX_ARCHIVE)
        if (result.uncertain) throw NfcProvisionWorkflow.UncertainOperation()
        if (result.exitCode != 0) throw NfcBackupException(NfcBackupException.Reason.BACKUP_FAILED)
        return result.output
    }

    override fun stamp(parsed: NfcSnapshotArchive.Parsed): NfcSnapshotWorkflow.Capture {
        check(ShellBootCount.read() == boot)
        val current = NfcBackupMetadata.read(context)
        check(current.version == metadata().version && current.signerHashes == metadata().signerHashes)
        return NfcSnapshotWorkflow.Capture(parsed, boot, started, wall, Build.FINGERPRINT)
    }

    companion object {
        val command = listOf("/system/bin/bu", "backup", "-user", "0", "-noapk", "-noobb",
            "-noshared", "-nowidgets", "-nocompress", "-nokeyvalue", NfcSnapshotArchive.PACKAGE)
    }
}
