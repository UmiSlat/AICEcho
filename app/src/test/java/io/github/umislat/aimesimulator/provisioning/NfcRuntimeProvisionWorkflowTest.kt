package io.github.umislat.aimesimulator.provisioning

import android.app.Application
import android.os.Bundle
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

@RunWith(BundledArchiveTestRunner::class)
@Config(sdk = [29, 36], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class NfcRuntimeProvisionWorkflowTest {
    @get:Rule val folder = TemporaryFolder()
    private val token = "a".repeat(32)
    private val idm = "012E102030405060"
    @Before fun before() { PosixAtomicFileShadow.reset() }
    @After fun after() { PosixAtomicFileShadow.reset() }
    private val original = ("<services><service component=\"${NfcProvisionPlan.Target.AIME.component}\" " +
        "uid=\"10366\" system-code=\"4000\" nfcid2=\"02FE010203040506\"/>" +
        "<service component=\"example.wallet/.Host\" uid=\"10500\" system-code=\"4001\" nfcid2=\"02FE111111111111\"/></services>").toByteArray()

    private inner class Backend : NfcProvisionWorkflow.Backend {
        var current = original.copyOf()
        var domain = NfcStorageDomain.CE
        val restorePaths = mutableListOf<String>()
        var disk = original.copyOf()
        var cancelled = false
        var uncertain = false
        var reloadSuccess = true
        var readFailure = false
        val calls = mutableListOf<String>()
        override fun snapshot(): NfcProvisionWorkflow.Baseline = error("File export must not be used for a runtime plan")
        override fun baseline() = runtimeSnapshot()
        override fun runtimeSnapshot(): NfcProvisionWorkflow.Baseline {
            calls += "read"
            check(!readFailure)
            return NfcProvisionWorkflow.Baseline(NfcProvisionPlan.BaselineSource.RUNTIME, current.copyOf(), domain)
        }
        override fun metadata() = NfcBackupFixtures.metadata()
        override fun restore(archive: ByteArray): ProbeProcess.Result {
            calls += "restore"
            if (!cancelled && !uncertain) {
                val header = "ANDROID BACKUP\n5\n1\nnone\n".toByteArray()
                TarArchiveInputStream(InflaterInputStream(ByteArrayInputStream(archive, header.size, archive.size - header.size))).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.name.endsWith("/dynamic_systemcode_nfcid2.xml")) {
                            restorePaths += entry.name
                            disk = tar.readBytes()
                        }
                    }
                }
            }
            return ProbeProcess.Result(0, byteArrayOf(), uncertain)
        }
        override fun reload(): Boolean {
            calls += "reload"
            if (reloadSuccess) current = (disk.toString(Charsets.UTF_8) + "\n").toByteArray()
            return reloadSuccess
        }
    }

    @Test fun restoreWaitsForExplicitReloadAndFreshSemanticRead() {
        val backend = Backend()
        val flow = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.PREPARED, flow.prepare(token, idm) {}.state)
        assertEquals(NfcProvisionPlan.BaselineSource.RUNTIME, flow.plan!!.source)
        assertEquals(NfcProvisionWorkflow.State.AWAITING_RELOAD, flow.apply(token) {}.state)
        assertEquals(listOf("read", "read", "restore"), backend.calls)
        assertArrayEquals(original, backend.current)
        assertFalse(flow.canActivate())
        assertThrows(IllegalArgumentException::class.java) { flow.apply(token) {} }
        assertThrows(IllegalArgumentException::class.java) { flow.prepare("b".repeat(32), idm) {} }
        assertThrows(IllegalArgumentException::class.java) { flow.rollback(token) {} }
        assertEquals(NfcProvisionWorkflow.State.RELOADED, flow.reload(token) {}.state)
        assertEquals(listOf("read", "read", "restore", "reload", "read"), backend.calls)
        assertTrue(flow.canActivate())
    }

    @Test fun cancelledRestoreIsOnlyDetectedAfterReloadAndNeverRetried() {
        val backend = Backend().apply { cancelled = true }
        val flow = NfcProvisionWorkflow(backend, 10366)
        flow.prepare(token, idm) {}
        assertEquals(NfcProvisionWorkflow.State.AWAITING_RELOAD, flow.apply(token) {}.state)
        assertEquals(NfcProvisionWorkflow.State.UNCHANGED, flow.reload(token) {}.state)
        assertFalse(flow.canActivate())
        assertEquals(1, backend.calls.count { it == "restore" })
    }

    @Test fun otherServiceChangesAfterRestoreAreNotAcceptedAsACompleteMatch() {
        val backend = Backend()
        val flow = NfcProvisionWorkflow(backend, 10366)
        flow.prepare(token, idm) {}; flow.apply(token) {}
        backend.disk = backend.disk.toString(Charsets.UTF_8).replace("4001", "4002").toByteArray()
        assertEquals(NfcProvisionWorkflow.State.CONFLICT, flow.reload(token) {}.state)
        assertFalse(flow.canActivate())
    }

    @Test fun deviceProtectedRuntimeCycleRetainsItsDomainAcrossSavedCheckpointsAndRollback() {
        val backend = Backend().apply { domain = NfcStorageDomain.DE }
        val flow = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.PREPARED, flow.prepare(token, idm) {}.state)
        assertEquals(NfcStorageDomain.DE, flow.plan!!.domain)
        assertEquals(NfcProvisionWorkflow.State.AWAITING_RELOAD, flow.apply(token) {}.state)
        val saved = save(flow)
        assertEquals("DE", saved.getString("domain"))
        val plan = NfcProvisionStore.validate(saved, 10366)
        val next = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.AWAITING_RELOAD,
            next.resume(token, plan, NfcProvisionStore.checkpoint(saved, plan)) {}.state)
        assertEquals(NfcProvisionWorkflow.State.RELOADED, next.reload(token) {}.state)
        assertTrue(next.canActivate())
        assertEquals(NfcProvisionWorkflow.State.AWAITING_RELOAD, next.rollback(token) {}.state)
        assertEquals(NfcStorageDomain.DE, next.checkpoint().rollback!!.domain)
        val rollbackSaved = save(next)
        val rollbackPlan = NfcProvisionStore.validate(rollbackSaved, 10366)
        assertEquals(NfcStorageDomain.DE, NfcProvisionStore.checkpoint(rollbackSaved, rollbackPlan).rollback!!.domain)
        assertEquals(NfcProvisionWorkflow.State.RELOADED, next.reload(token) {}.state)
        assertFalse(next.canActivate())
        assertEquals(listOf(NfcStorageDomain.DE.archivePath, NfcStorageDomain.DE.archivePath), backend.restorePaths)
        assertTrue(next.plan!!.matchesOriginal(backend.disk))
    }

    @Test fun runtimeDomainDriftBlocksApplyAndFreshSessionRecovery() {
        val backend = Backend()
        val flow = NfcProvisionWorkflow(backend, 10366)
        flow.prepare(token, idm) {}
        backend.domain = NfcStorageDomain.DE
        assertEquals(NfcProvisionWorkflow.State.FAILED, flow.apply(token) {}.state)
        assertEquals("STORAGE_DOMAIN_CHANGED", flow.result.detail)
        val next = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.FAILED, next.resume(token, flow.plan!!) {}.state)
        assertEquals("STORAGE_DOMAIN_CHANGED", next.result.detail)
        assertTrue(backend.restorePaths.isEmpty())
    }

    @Test fun runtimeDomainDriftAfterRestoreCannotEnableTheCard() {
        val backend = Backend()
        val flow = NfcProvisionWorkflow(backend, 10366)
        flow.prepare(token, idm) {}
        flow.apply(token) {}
        backend.domain = NfcStorageDomain.DE
        val result = flow.reload(token) {}
        assertEquals(NfcProvisionWorkflow.State.UNKNOWN, result.state)
        assertEquals("STORAGE_DOMAIN_CHANGED", result.detail)
        assertFalse(flow.canActivate())
        assertEquals(listOf(NfcStorageDomain.CE.archivePath), backend.restorePaths)
    }

    @Test fun reloadOrFreshReadFailureNeverEnablesEmulation() {
        for (failRead in listOf(false, true)) {
            val backend = Backend()
            val flow = NfcProvisionWorkflow(backend, 10366)
            flow.prepare(token, idm) {}; flow.apply(token) {}
            backend.readFailure = failRead
            backend.reloadSuccess = failRead
            assertEquals(NfcProvisionWorkflow.State.UNKNOWN, flow.reload(token) {}.state)
            assertFalse(flow.canActivate())
        }
    }

    @Test fun savedAwaitingReloadDoesNotInterpretOldCacheAsAnUnappliedPlan() {
        val backend = Backend()
        val flow = NfcProvisionWorkflow(backend, 10366)
        flow.prepare(token, idm) {}; flow.apply(token) {}
        val data = save(flow)
        backend.calls.clear()
        val next = NfcProvisionWorkflow(backend, 10366)
        val plan = NfcProvisionStore.validate(data, 10366)
        assertEquals(NfcProvisionWorkflow.State.AWAITING_RELOAD,
            next.resume(token, plan, NfcProvisionStore.checkpoint(data, plan)) {}.state)
        assertTrue(backend.calls.isEmpty())
        assertFalse(next.canActivate())
        assertEquals(NfcProvisionWorkflow.State.RELOADED, next.reload(token) {}.state)
        assertTrue(next.canActivate())
    }

    @Test fun rollbackCheckpointPreservesLatestOtherRecordAndDirectionAfterReopen() {
        val backend = Backend()
        val flow = NfcProvisionWorkflow(backend, 10366)
        flow.prepare(token, idm) {}; flow.apply(token) {}; flow.reload(token) {}
        backend.current = backend.current.toString(Charsets.UTF_8).replace("4001", "4002").toByteArray()
        assertEquals(NfcProvisionWorkflow.State.AWAITING_RELOAD, flow.rollback(token) {}.state)
        val data = save(flow)
        val plan = NfcProvisionStore.validate(data, 10366)
        val next = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.AWAITING_RELOAD,
            next.resume(token, plan, NfcProvisionStore.checkpoint(data, plan)) {}.state)
        assertEquals(NfcProvisionWorkflow.State.RELOADED, next.reload(token) {}.state)
        assertFalse(next.canActivate())
        assertTrue(backend.current.toString(Charsets.UTF_8).contains("4002"))
        assertEquals("02FE010203040506", NfcProvisionPlan.currentIdentity(backend.current, NfcProvisionPlan.Target.AIME, 10366)!!.nfcid2)
        val settled = save(next)
        val reopened = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.ROLLED_BACK,
            reopened.resume(token, plan, NfcProvisionStore.checkpoint(settled, plan)) {}.state)
        assertFalse(reopened.canActivate())
    }

    @Test fun lostCompletionRemainsUnknownRegardlessOfVisibleCache() {
        val backend = Backend()
        val first = NfcProvisionWorkflow(backend, 10366)
        first.prepare(token, idm) {}
        val saved = save(first)
        for (phase in listOf(NfcProvisionWorkflow.State.CONFIRMING, NfcProvisionWorkflow.State.ROLLING_BACK,
            NfcProvisionWorkflow.State.RELOADING, NfcProvisionWorkflow.State.UNKNOWN)) {
            val store = NfcProvisionStore(folder.root)
            store.save(saved, 10366, 185)
            store.mark(token, 10366, 185, phase)
            val data = store.read(10366)!!
            val plan = NfcProvisionStore.validate(data, 10366)
            backend.calls.clear()
            val next = NfcProvisionWorkflow(backend, 10366)
            assertEquals(NfcProvisionWorkflow.State.UNKNOWN,
                next.resume(token, plan, NfcProvisionStore.checkpoint(data, plan)) {}.state)
            assertTrue(backend.calls.isEmpty())
            assertFalse(next.canActivate())
        }
    }

    @Test fun uncertainRestoreDoesNotOfferReload() {
        val backend = Backend().apply { uncertain = true }
        val flow = NfcProvisionWorkflow(backend, 10366)
        flow.prepare(token, idm) {}
        assertEquals(NfcProvisionWorkflow.State.UNKNOWN, flow.apply(token) {}.state)
        assertThrows(IllegalArgumentException::class.java) { flow.reload(token) {} }
        assertFalse(flow.canActivate())
    }

    @Test fun inferredRollbackOnResumeRetainsDirectionThroughReload() {
        val backend = Backend()
        val first = NfcProvisionWorkflow(backend, 10366)
        first.prepare(token, idm) {}
        backend.current = original.toString(Charsets.UTF_8).replace("4001", "4002").toByteArray()
        backend.disk = backend.current.copyOf()
        val next = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.ROLLED_BACK, next.resume(token, first.plan!!) {}.state)
        assertEquals(NfcProvisionWorkflow.Direction.ROLLBACK, next.checkpoint().direction)
        assertEquals(NfcProvisionWorkflow.State.RELOADED, next.reload(token) {}.state)
        assertFalse(next.canActivate())
    }

    @Test fun rollbackRetainsActualCandidateBeforeSubmittingRestore() {
        val backend = Backend()
        val flow = NfcProvisionWorkflow(backend, 10366)
        flow.prepare(token, idm) {}; flow.apply(token) {}; flow.reload(token) {}
        backend.current = backend.current.toString(Charsets.UTF_8).replace("4001", "4002").toByteArray()
        save(flow)
        val store = NfcProvisionStore(folder.root)
        store.mark(token, 10366, 185, NfcProvisionWorkflow.State.ROLLING_BACK, NfcProvisionWorkflow.Direction.ROLLBACK)
        var retained = false
        val state = flow.rollback(token, { candidate ->
            assertEquals(1, backend.calls.count { it == "restore" })
            store.retainRollback(token, 10366, 185, candidate.observationXml(), candidate.xml())
            val saved = store.read(10366)!!
            assertEquals("ROLLING_BACK", saved.getString("state"))
            assertArrayEquals(candidate.xml(), saved.getByteArray("rollbackXml"))
            retained = true
        }) {}.state
        assertTrue(retained)
        assertEquals(NfcProvisionWorkflow.State.AWAITING_RELOAD, state)
        assertEquals(2, backend.calls.count { it == "restore" })
    }

    @Test fun failedRollbackPersistenceNeverSubmitsRestore() {
        val backend = Backend()
        val flow = NfcProvisionWorkflow(backend, 10366)
        flow.prepare(token, idm) {}; flow.apply(token) {}; flow.reload(token) {}
        assertEquals(NfcProvisionWorkflow.State.FAILED, flow.rollback(token, { error("Storage unavailable") }) {}.state)
        assertEquals(1, backend.calls.count { it == "restore" })
        assertFalse(flow.canActivate())
    }

    @Test fun aConfirmedRebootAllowsReadOnlyResolutionOfLostRuntimeCompletion() {
        val backend = Backend()
        val flow = NfcProvisionWorkflow(backend, 10366)
        flow.prepare(token, idm) {}; flow.apply(token) {}
        backend.reload()
        backend.calls.clear()
        val next = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.VERIFIED, next.resume(token, flow.plan!!,
            NfcProvisionWorkflow.Checkpoint(NfcProvisionWorkflow.State.CONFIRMING), bootChanged = true) {}.state)
        assertEquals(listOf("read"), backend.calls)
        assertFalse(next.canActivate())
    }

    private fun save(flow: NfcProvisionWorkflow): Bundle {
        val plan = checkNotNull(flow.plan)
        val checkpoint = flow.checkpoint()
        val data = Bundle().apply {
            putString("token", token); putInt("uid", 10366); putString("nfcid2", idm)
            putString("source", plan.source.name); putString("state", checkpoint.state.name)
            putString("domain", plan.domain.name)
            putString("direction", checkpoint.direction.name)
            putString("oldSystemCode", plan.before.systemCode); putString("oldNfcid2", plan.before.nfcid2)
            putString("originalHash", plan.originalHash); putString("proposedHash", plan.proposedHash)
            putByteArray("original", plan.originalXml()); putByteArray("proposed", plan.proposedXml())
            checkpoint.rollback?.let { putByteArray("rollbackObservation", it.observationXml()); putByteArray("rollbackXml", it.xml()) }
        }
        val store = NfcProvisionStore(folder.root)
        store.save(data, 10366, 185)
        return checkNotNull(store.read(10366))
    }
}
