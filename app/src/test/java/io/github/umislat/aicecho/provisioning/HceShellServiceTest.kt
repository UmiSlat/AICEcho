package io.github.umislat.aicecho.provisioning

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Bundle
import io.github.umislat.aicecho.BuildConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class)
class HceShellServiceTest {
    private val token = "a".repeat(32)
    private fun context(uid: Int = 12345) = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName() = BuildConfig.APPLICATION_ID
        override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply { this.uid = uid }
    }

    @Before fun caller() { ShadowBinder.setCallingUid(12345); ShadowProcess.setUid(2000) }

    @Test fun secondaryUserIsRejectedBeforeAnyCommand() {
        assertThrows(IllegalArgumentException::class.java) { HceShellService(context(112345)) }
    }

    @Test fun anotherAppCannotUseAnyDataOrRestoreEndpoint() {
        val service = HceShellService(context())
        ShadowBinder.setCallingUid(12346)
        assertThrows(IllegalStateException::class.java) { service.inspect() }
        assertThrows(IllegalStateException::class.java) { service.probeStatus() }
        assertThrows(IllegalStateException::class.java) { service.startRestoreProbe(token) }
        assertThrows(IllegalStateException::class.java) { service.verifyPreviousProbe(token) }
        assertThrows(IllegalStateException::class.java) { service.startNfcSnapshot(token) }
        assertThrows(IllegalStateException::class.java) { service.snapshotStatus() }
        assertThrows(IllegalStateException::class.java) { service.readNfcSnapshot(token) }
        assertThrows(IllegalStateException::class.java) { service.resumeNfcProvision(Bundle()) }
        assertThrows(IllegalStateException::class.java) { service.inspectNfcRuntime() }
    }

    @Test fun rootBackendCannotSubmitOrVerifyRestore() {
        val service = HceShellService(context())
        ShadowProcess.setUid(0)
        assertThrows(IllegalStateException::class.java) { service.startRestoreProbe(token) }
        assertThrows(IllegalStateException::class.java) { service.verifyPreviousProbe(token) }
        assertFalse(service.inspect().getBoolean("markerApp"))
        assertFalse(service.inspect().getBoolean("nfcSnapshotCandidate"))
        assertThrows(IllegalStateException::class.java) { service.startNfcSnapshot(token) }
        assertThrows(IllegalStateException::class.java) { service.readNfcSnapshot(token) }
        assertThrows(IllegalStateException::class.java) { service.resumeNfcProvision(Bundle()) }
        assertThrows(IllegalStateException::class.java) { service.inspectNfcRuntime() }
    }

    @Test fun inspectionDoesNotEnableNfcProvisioningOrChangeProbeState() {
        val service = HceShellService(context())
        assertEquals(2000, service.inspect().getInt("uid"))
        assertFalse(service.inspect().getBoolean("nfcProvisioning"))
        assertEquals("IDLE", service.probeStatus().getString("state"))
        assertFalse(service.inspect().getBoolean("nfcSnapshotCandidate"))
        assertEquals("IDLE", service.snapshotStatus().getString("state"))
        assertThrows(IllegalArgumentException::class.java) { service.startNfcSnapshot("invalid") }
        assertThrows(IllegalStateException::class.java) { service.readNfcSnapshot(token) }
        assertEquals("IDLE", service.snapshotStatus().getString("state"))
        assertThrows(IllegalArgumentException::class.java) { service.startRestoreProbe("invalid") }
        assertEquals("IDLE", service.probeStatus().getString("state"))
    }

    @Test fun serviceRejectsBothOperationsWhileSnapshotIsUnresolved() {
        val service = HceShellService(context())
        for (state in listOf(NfcSnapshotWorkflow.State.EXPORTING, NfcSnapshotWorkflow.State.UNKNOWN)) {
            HceShellService::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
                .set(service, NfcSnapshotWorkflow.Result(state, token))
            assertEquals(state.name, service.startNfcSnapshot(token).getString("state"))
            assertThrows(IllegalStateException::class.java) { service.startNfcSnapshot("b".repeat(32)) }
            assertThrows(IllegalStateException::class.java) { service.startRestoreProbe("b".repeat(32)) }
            assertThrows(IllegalStateException::class.java) { service.verifyPreviousProbe("b".repeat(32)) }
            assertThrows(IllegalStateException::class.java) { service.inspectNfcRuntime() }
        }
    }

    @Test fun unresolvedRestoreBlocksSnapshotWithoutStartingWorker() {
        val service = HceShellService(context())
        HceShellService::class.java.getDeclaredField("result").apply { isAccessible = true }
            .set(service, RestoreProbeWorkflow.Result(RestoreProbeWorkflow.State.UNKNOWN, token))
        assertThrows(IllegalStateException::class.java) { service.startNfcSnapshot("b".repeat(32)) }
        assertThrows(IllegalStateException::class.java) { service.inspectNfcRuntime() }
        assertEquals("IDLE", service.snapshotStatus().getString("state"))
    }

    @Test fun unresolvedProvisionBlocksRuntimeInspectionBeforeAnyCommand() {
        val service = HceShellService(context())
        for (state in listOf(NfcProvisionWorkflow.State.CONFIRMING, NfcProvisionWorkflow.State.UNKNOWN)) {
            HceShellService::class.java.getDeclaredField("provision").apply { isAccessible = true }
                .set(service, NfcProvisionWorkflow.Result(state, token))
            assertThrows(IllegalStateException::class.java) { service.inspectNfcRuntime() }
            assertEquals("IDLE", service.snapshotStatus().getString("state"))
        }
    }
}
