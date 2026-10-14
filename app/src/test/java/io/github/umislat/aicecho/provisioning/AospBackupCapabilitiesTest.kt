package io.github.umislat.aicecho.provisioning

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class)
class AospBackupCapabilitiesTest {
    private val ready = AospBackupCapabilities(true, 0, 0, true, AospBackupCapabilities.Service.ACTIVE,
        AospBackupCapabilities.Target.READY, false, 1234, "example.BackupAgent", true)

    @Test fun presenceOfBuAloneDoesNotEstablishSupport() {
        for (report in listOf(ready.copy(shell = false), ready.copy(ownerUser = 10), ready.copy(foregroundUser = 10),
            ready.copy(foregroundUser = -1), ready.copy(hcef = false), ready.copy(bu = false),
            ready.copy(service = AospBackupCapabilities.Service.UNKNOWN), ready.copy(service = AospBackupCapabilities.Service.INACTIVE))) {
            assertFalse(report.toString(), report.canExport)
            assertFalse(report.canPrepare(completeSnapshot = true))
        }
        for (target in AospBackupCapabilities.Target.entries.filter { it != AospBackupCapabilities.Target.READY }) {
            assertFalse(ready.copy(target = target).canExport)
        }
    }

    @Test fun keyValueAgentRequiresObservedFullSnapshotButCanBeProbed() {
        val kv = ready.copy(keyValueOnly = true)
        assertTrue(kv.canExport)
        assertFalse(kv.canPrepare(completeSnapshot = false))
        assertTrue(kv.canPrepare(completeSnapshot = true))
        assertTrue(ready.canPrepare(completeSnapshot = false))
    }

    @Test fun serviceOutputIsParsedWithoutMistakingDeactivatedForActivated() {
        fun state(text: String, code: Int = 0, uncertain: Boolean = false) = AospBackupCapabilities.serviceState(
            ProbeProcess.Result(code, text.toByteArray(), uncertain))
        assertEquals(AospBackupCapabilities.Service.ACTIVE, state("Backup Manager currently activated\r\n"))
        assertEquals(AospBackupCapabilities.Service.INACTIVE, state("Backup Manager currently deactivated\n"))
        for (output in listOf("", "Exception: not allowed", "activated", "Backup Manager currently enabled")) {
            assertEquals(AospBackupCapabilities.Service.UNKNOWN, state(output))
        }
        assertEquals(AospBackupCapabilities.Service.UNKNOWN, state("Backup Manager currently activated", code = 1))
        assertEquals(AospBackupCapabilities.Service.UNKNOWN, state("Backup Manager currently activated", uncertain = true))
    }

    @Test fun capabilityBundleKeepsSeparateFacts() {
        assertEquals(ready, AospBackupCapabilities.decode(ready.bundle()))
        val kv = ready.copy(keyValueOnly = true, service = AospBackupCapabilities.Service.UNKNOWN)
        assertEquals(kv, AospBackupCapabilities.decode(kv.bundle()))
    }

    @Test fun standardExportNeverRequestsApksKeyValueOrOtherPackages() {
        assertEquals(listOf("/system/bin/bu", "backup", "-user", "0", "-noapk", "-noobb", "-noshared",
            "-nowidgets", "-nocompress", "-nokeyvalue", "com.android.nfc"), AospNfcExporter.command)
    }
}
