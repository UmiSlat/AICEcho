package io.github.umislat.aimesimulator.provisioning

import android.app.Application
import android.os.Bundle
import android.util.Base64
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

@RunWith(BundledArchiveTestRunner::class)
@Config(sdk = [29, 36], application = Application::class)
class NfcProvisionWorkflowTest {
    @get:Rule val folder = TemporaryFolder()
    private val token = "a".repeat(32)
    private val idm = "012E102030405060"
    private val original = ("<services><service component=\"${NfcProvisionPlan.Target.AIME.component}\" " +
        "uid=\"10366\" system-code=\"4000\" nfcid2=\"02FE010203040506\"/>" +
        "<service component=\"example.wallet/.Host\" uid=\"10500\" system-code=\"4001\" nfcid2=\"02FE111111111111\"/></services>")
        .toByteArray()
    private inner class Backend : NfcProvisionWorkflow.Backend {
        var current = original.copyOf()
        val calls = mutableListOf<String>()
        var cancelled = false
        var uncertain = false
        var snapshotUnknown = false
        override fun snapshot(): ByteArray {
            calls += "snapshot"
            if (snapshotUnknown) throw NfcProvisionWorkflow.UncertainOperation()
            return current.copyOf()
        }
        override fun metadata() = NfcBackupFixtures.metadata()
        override fun restore(archive: ByteArray): ProbeProcess.Result {
            calls += "restore"
            if (!cancelled && !uncertain) {
                val header = "ANDROID BACKUP\n5\n1\nnone\n".toByteArray()
                TarArchiveInputStream(InflaterInputStream(ByteArrayInputStream(archive, header.size, archive.size - header.size))).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.name.endsWith("/dynamic_systemcode_nfcid2.xml")) current = tar.readBytes()
                    }
                }
            }
            return ProbeProcess.Result(0, byteArrayOf(), uncertain)
        }
        override fun reload(): Boolean { calls += "reload"; return true }
    }

    @Test fun prepareApplyReadbackReloadAndActivateAreSeparateSteps() {
        val backend = Backend()
        val workflow = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.PREPARED, workflow.prepare(token, idm) {}.state)
        assertEquals(listOf("snapshot"), backend.calls)
        assertFalse(workflow.canActivate())
        assertEquals(NfcProvisionWorkflow.State.VERIFIED, workflow.apply(token) {}.state)
        assertEquals(listOf("snapshot", "snapshot", "restore", "snapshot"), backend.calls)
        assertArrayEquals(workflow.plan!!.proposedXml(), backend.current)
        assertFalse(workflow.canActivate())
        assertEquals(NfcProvisionWorkflow.State.RELOADED, workflow.reload(token) {}.state)
        assertTrue(workflow.canActivate())
        assertEquals("reload", backend.calls.last())
    }

    @Test fun alreadySavedCardDoesNotRestoreAgain() {
        val backend = Backend()
        val first = NfcProvisionWorkflow(backend, 10366)
        first.prepare(token, idm) {}
        backend.current = first.plan!!.proposedXml()
        val workflow = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.ALREADY_PRESENT, workflow.prepare(token, idm) {}.state)
        assertTrue(workflow.canActivate())
        assertNull(workflow.plan)
        assertFalse(backend.calls.contains("restore"))
    }

    @Test fun missingRegistrationAsksForLegalInitialization() {
        val backend = Backend().apply { current = "<services/>".toByteArray() }
        val workflow = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.NEEDS_BASELINE, workflow.prepare(token, idm) {}.state)
        assertNull(workflow.plan)
        assertEquals(listOf("snapshot"), backend.calls)
    }

    @Test fun placeholderRegistrationAlsoNeedsInitialization() {
        val backend = Backend().apply { current = original.toString(Charsets.UTF_8)
            .replace("02FE010203040506", "RANDOM").toByteArray() }
        val workflow = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.NEEDS_BASELINE, workflow.prepare(token, idm) {}.state)
    }

    @Test fun originalTableAndProposedTableAreSavedBeforeWriting() {
        val backend = Backend()
        val workflow = NfcProvisionWorkflow(backend, 10366)
        workflow.prepare(token, idm) {}
        val plan = checkNotNull(workflow.plan)
        val data = Bundle().apply {
            putString("token", token); putInt("uid", 10366); putString("nfcid2", idm)
            putString("oldSystemCode", plan.before.systemCode); putString("oldNfcid2", plan.before.nfcid2)
            putString("originalHash", plan.originalHash); putString("proposedHash", plan.proposedHash)
            putByteArray("original", plan.originalXml()); putByteArray("proposed", plan.proposedXml())
        }
        NfcProvisionStore(folder.root).save(data, 10366, 185)
        val saved = JSONObject(File(folder.root, "nfc-provision-plan.json").readText())
        assertArrayEquals(original, Base64.decode(saved.getString("original"), Base64.NO_WRAP))
        assertArrayEquals(plan.proposedXml(), Base64.decode(saved.getString("proposed"), Base64.NO_WRAP))
        assertEquals(token, saved.getString("token"))
        assertEquals(185, saved.getInt("boot"))
        assertFalse(backend.calls.contains("restore"))
    }

    @Test fun rollbackRestoresTheOldRecordAndRequiresItsOwnReload() {
        val backend = Backend()
        val workflow = NfcProvisionWorkflow(backend, 10366)
        workflow.prepare(token, idm) {}
        workflow.apply(token) {}
        workflow.reload(token) {}
        assertEquals(NfcProvisionWorkflow.State.ROLLED_BACK, workflow.rollback(token) {}.state)
        assertArrayEquals(original, backend.current)
        assertFalse(workflow.canActivate())
        assertEquals(NfcProvisionWorkflow.State.RELOADED, workflow.reload(token) {}.state)
        assertFalse(workflow.canActivate())
        assertEquals(2, backend.calls.count { it == "restore" })
    }

    @Test fun cancelledRestoreIsUnchangedAndNeverAutoReloaded() {
        val backend = Backend().apply { cancelled = true }
        val workflow = NfcProvisionWorkflow(backend, 10366)
        workflow.prepare(token, idm) {}
        assertEquals(NfcProvisionWorkflow.State.UNCHANGED, workflow.apply(token) {}.state)
        assertEquals(1, backend.calls.count { it == "restore" })
        assertFalse(backend.calls.contains("reload"))
    }

    @Test fun changedBaselineIsNotSubmitted() {
        val backend = Backend()
        val workflow = NfcProvisionWorkflow(backend, 10366)
        workflow.prepare(token, idm) {}
        backend.current += "\n".toByteArray()
        assertEquals(NfcProvisionWorkflow.State.CONFLICT, workflow.apply(token) {}.state)
        assertFalse(backend.calls.contains("restore"))
    }

    @Test fun unknownSystemOperationCannotBeInterpretedAsNotStarted() {
        val backend = Backend().apply { snapshotUnknown = true }
        val workflow = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.UNKNOWN, workflow.prepare(token, idm) {}.state)
        assertFalse(workflow.canActivate())
    }

    @Test fun uncertainRestoreDoesNotExportOrReloadAgain() {
        val backend = Backend().apply { uncertain = true }
        val workflow = NfcProvisionWorkflow(backend, 10366)
        workflow.prepare(token, idm) {}
        assertEquals(NfcProvisionWorkflow.State.UNKNOWN, workflow.apply(token) {}.state)
        assertEquals(listOf("snapshot", "snapshot", "restore"), backend.calls)
    }

    @Test fun resumedAppliedPlanRetainsOriginalAndCanBeManuallyRolledBack() {
        val backend = Backend()
        val previous = NfcProvisionWorkflow(backend, 10366)
        previous.prepare(token, idm) {}
        val plan = previous.plan!!
        previous.apply(token) {}
        backend.calls.clear()
        val resumed = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.VERIFIED, resumed.resume(token, plan) {}.state)
        assertEquals(listOf("snapshot"), backend.calls)
        assertFalse(resumed.canActivate())
        assertArrayEquals(original, resumed.plan!!.originalXml())
        assertEquals(idm, resumed.requestedIdm)
        assertEquals(NfcProvisionWorkflow.State.ROLLED_BACK, resumed.rollback(token) {}.state)
        assertArrayEquals(original, backend.current)
        assertFalse(backend.calls.contains("reload"))
    }

    @Test fun resumedUnappliedPlanNeedsAnotherExplicitWrite() {
        val backend = Backend()
        val previous = NfcProvisionWorkflow(backend, 10366)
        previous.prepare(token, idm) {}
        backend.calls.clear()
        val resumed = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.PREPARED, resumed.resume(token, previous.plan!!) {}.state)
        assertEquals(listOf("snapshot"), backend.calls)
        assertFalse(resumed.canActivate())
        assertEquals(NfcProvisionWorkflow.State.VERIFIED, resumed.apply(token) {}.state)
        assertEquals(1, backend.calls.count { it == "restore" })
    }

    @Test fun resumedPlanNeverOverwritesAChangedExternalRecord() {
        val backend = Backend()
        val previous = NfcProvisionWorkflow(backend, 10366)
        previous.prepare(token, idm) {}
        val plan = previous.plan!!
        backend.current = plan.proposedXml().toString(Charsets.UTF_8).replace("4001", "4002").toByteArray()
        backend.calls.clear()
        val resumed = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.CONFLICT, resumed.resume(token, plan) {}.state)
        assertEquals(listOf("snapshot"), backend.calls)
        assertThrows(IllegalArgumentException::class.java) { resumed.apply(token) {} }
        assertEquals(NfcProvisionWorkflow.State.ROLLED_BACK, resumed.rollback(token) {}.state)
        assertTrue(backend.current.toString(Charsets.UTF_8).contains("4002"))
        assertEquals("02FE010203040506", NfcProvisionPlan.currentIdentity(backend.current, NfcProvisionPlan.Target.AIME, 10366)!!.nfcid2)
    }

    @Test fun resumedRevertedRecordDoesNotEnableTheProposedCard() {
        val backend = Backend()
        val previous = NfcProvisionWorkflow(backend, 10366)
        previous.prepare(token, idm) {}
        backend.current = original.toString(Charsets.UTF_8).replace("4001", "4002").toByteArray()
        backend.calls.clear()
        val resumed = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.ROLLED_BACK, resumed.resume(token, previous.plan!!) {}.state)
        assertEquals(listOf("snapshot"), backend.calls)
        resumed.reload(token) {}
        assertFalse(resumed.canActivate())
    }

    @Test fun unknownReadbackOrWrongOwnerCannotBecomeAResumedReadyPlan() {
        val backend = Backend()
        val previous = NfcProvisionWorkflow(backend, 10366)
        previous.prepare(token, idm) {}
        val plan = previous.plan!!
        backend.calls.clear()
        assertThrows(IllegalArgumentException::class.java) {
            NfcProvisionWorkflow(backend, 10367).resume(token, plan) {}
        }
        assertTrue(backend.calls.isEmpty())
        backend.snapshotUnknown = true
        val resumed = NfcProvisionWorkflow(backend, 10366)
        assertEquals(NfcProvisionWorkflow.State.UNKNOWN, resumed.resume(token, plan) {}.state)
        assertFalse(resumed.canActivate())
        assertThrows(IllegalArgumentException::class.java) { resumed.resume(token, plan) {} }
        assertEquals(listOf("snapshot"), backend.calls)
    }

    @Test fun lostFileCompletionIsReadOnlyRecoverableAfterConfirmedReboot() {
        for (applied in listOf(false, true)) {
            val backend = Backend()
            val first = NfcProvisionWorkflow(backend, 10366)
            first.prepare(token, idm) {}
            if (applied) first.apply(token) {}
            backend.calls.clear()
            val next = NfcProvisionWorkflow(backend, 10366)
            assertEquals(if (applied) NfcProvisionWorkflow.State.VERIFIED else NfcProvisionWorkflow.State.PREPARED,
                next.resume(token, first.plan!!, NfcProvisionWorkflow.Checkpoint(NfcProvisionWorkflow.State.CONFIRMING),
                    bootChanged = true) {}.state)
            assertEquals(listOf("snapshot"), backend.calls)
            assertFalse(next.canActivate())
        }
    }
}
