package io.github.umislat.aimesimulator.provisioning

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.CardStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class ShizukuPlanRecoveryTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        PosixAtomicFileShadow.reset()
        for (name in listOf("shizuku_nfc_provision", "shizuku_nfc_snapshot", "shizuku_restore_probe")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(app.noBackupFilesDir, "nfc-provision-plan.json").delete()
        File(app.noBackupFilesDir, "nfc-snapshot.json").delete()
        app.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE).edit().clear().putBoolean("storage_migrated", true).commit()
    }

    @Test fun explicitResumeRebuildsSessionAndRetainsOriginalWithoutRestoring() {
        val original = NfcProvisionStoreTest.savedPlan(app.applicationInfo.uid)
        NfcProvisionStore(app.noBackupFilesDir).save(original, app.applicationInfo.uid, 185)
        val remote = Remote()
        withModel(remote) { model ->
            assertTrue(model.screen.value!!.savedPlanAvailable)
            assertEquals(0, remote.resumes)
            ready(model)
            assertTrue(model.screen.value!!.canResumePlan)
            model.resumePreviousPlan()
            drain(model)
            assertEquals(1, remote.resumes)
            assertFalse(model.screen.value!!.provisionPending)
            model.refresh()
            drain(model)
            val screen = model.screen.value!!
            assertEquals(NfcProvisionWorkflow.State.VERIFIED, screen.provision.state)
            assertTrue(screen.planSaved)
            assertTrue(screen.canRollback)
            assertFalse(screen.canResumePlan)
            assertFalse(screen.canActivate)
            val saved = NfcProvisionStore(app.noBackupFilesDir).read(app.applicationInfo.uid)!!
            assertArrayEquals(original.getByteArray("original"), saved.getByteArray("original"))
            assertEquals(screen.provision.token, saved.getString("token"))
            assertNotEquals(original.getString("token"), saved.getString("token"))
        }
    }

    @Test fun aPendingOperationCannotBeClearedByResumingSavedData() {
        NfcProvisionStore(app.noBackupFilesDir).save(NfcProvisionStoreTest.savedPlan(app.applicationInfo.uid), app.applicationInfo.uid, -1)
        app.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).edit()
            .putString("token", "a".repeat(32)).putInt("boot", -1).putBoolean("pending", true).commit()
        val remote = Remote()
        withModel(remote) { model ->
            ready(model)
            assertTrue(model.screen.value!!.savedPlanAvailable)
            assertTrue(model.screen.value!!.provisionPending)
            assertFalse(model.screen.value!!.canResumePlan)
            model.resumePreviousPlan()
            drain(model)
            assertEquals(0, remote.resumes)
            assertTrue(model.screen.value!!.provisionPending)
        }
    }

    @Test fun freshServiceInvalidatesStaleLiveStateButOffersTheSavedPlan() {
        NfcProvisionStore(app.noBackupFilesDir).save(NfcProvisionStoreTest.savedPlan(app.applicationInfo.uid), app.applicationInfo.uid, 185)
        withModel(Remote()) { model ->
            (model.screen as MutableLiveData).value = model.screen.value!!.copy(
                planSaved = true, canActivate = true, provisionIdm = "012E102030405060",
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.RELOADED, "a".repeat(32)))
            model.refresh()
            drain(model)
            val screen = model.screen.value!!
            assertEquals(NfcProvisionWorkflow.State.IDLE, screen.provision.state)
            assertFalse(screen.planSaved)
            assertFalse(screen.canActivate)
            assertFalse(screen.canRollback)
            assertFalse(screen.canReload)
            assertTrue(screen.canResumePlan)
            assertEquals("", screen.provisionIdm)
        }
    }

    @Test fun savedDeviceProtectedPlanKeepsItsDomainInTheRemoteSessionAndScreen() {
        val data = NfcProvisionStoreTest.savedPlan(app.applicationInfo.uid, NfcStorageDomain.DE)
        NfcProvisionStore(app.noBackupFilesDir).save(data, app.applicationInfo.uid, 185)
        val remote = Remote()
        withModel(remote) { model ->
            ready(model)
            assertTrue(model.screen.value!!.canResumePlan)
            model.resumePreviousPlan()
            drain(model)
            model.refresh()
            drain(model)
            assertEquals(NfcStorageDomain.DE, model.screen.value!!.provisionDomain)
            assertEquals("DE", NfcProvisionStore(app.noBackupFilesDir).read(app.applicationInfo.uid)!!.getString("domain"))
            assertEquals(0, remote.applies)
        }
    }

    @Test fun runtimeInspectionIsExplicitAndDoesNotWriteSnapshotOrProvisionJournal() {
        val remote = Remote()
        withModel(remote) { model ->
            ready(model)
            model.refresh()
            drain(model)
            assertEquals(0, remote.runtimeReads)
            model.inspectRuntime()
            drain(model)
            assertEquals(1, remote.runtimeReads)
            assertEquals(NfcRuntimeInventory.State.READ, model.screen.value!!.runtime.state)
            assertFalse(model.screen.value!!.working)
            assertTrue(app.getSharedPreferences("shizuku_nfc_snapshot", Context.MODE_PRIVATE).all.isEmpty())
            assertTrue(app.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).all.isEmpty())
            assertFalse(File(app.noBackupFilesDir, "nfc-snapshot.json").exists())
            assertFalse(File(app.noBackupFilesDir, "nfc-provision-plan.json").exists())
        }
    }

    @Test fun blockedRuntimeBinderDoesNotBlockLaterRefreshes() {
        val remote = Remote()
        val gate = NfcRuntimeReadGate(100)
        val release = CountDownLatch(1)
        remote.runtimeQuery = {
            NfcRuntimeInventory.inspect(object : NfcRuntimeInventory.Reader {
                override fun structured() = gate.read { release.await(); emptyList() }
                override fun dump() = "Registered HCE-F services for current user:\nUser 0\nPreferred services (in order of importance):\n"
            })
        }
        try {
            withModel(remote) { model ->
                ready(model)
                repeat(2) {
                    model.inspectRuntime()
                    drain(model)
                    assertEquals(NfcRuntimeInventory.Source.DUMPSYS, model.screen.value!!.runtime.source)
                    model.refresh()
                    drain(model)
                    assertEquals(ShizukuProbeModel.Connection.READY, model.screen.value!!.connection)
                    assertFalse(model.screen.value!!.working)
                }
            }
        } finally { release.countDown() }
    }

    @Test fun runtimeApplyPersistsIntentBeforeIpcAndReceiptBeforeClearingPending() {
        val store = NfcProvisionStore(app.noBackupFilesDir)
        val data = NfcProvisionStoreTest.savedPlan(app.applicationInfo.uid).apply { putString("source", "RUNTIME") }
        store.save(data, app.applicationInfo.uid, -1)
        val token = data.getString("token")!!
        val card = requireNotNull(CardProfile.create("Synthetic", data.getString("nfcid2")!!))
        CardStore(app).apply { put(card); select(card.profileId) }
        val remote = Remote().apply {
            resumeNfcProvision(data)
            phase = "PREPARED"
            beforeApply = {
                val saved = store.read(app.applicationInfo.uid)!!
                assertEquals("CONFIRMING", saved.getString("state"))
                assertTrue(app.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).getBoolean("pending", false))
            }
        }
        withModel(remote) { model ->
            ready(model)
            (model.screen as MutableLiveData).value = model.screen.value!!.copy(planSaved = true,
                provisionIdm = card.idm, provisionSource = NfcProvisionPlan.BaselineSource.RUNTIME,
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.PREPARED, token))
            model.applyCurrentPlan()
            drain(model)
            assertEquals(1, remote.applies)
            assertEquals("AWAITING_RELOAD", store.read(app.applicationInfo.uid)!!.getString("state"))
            assertFalse(model.screen.value!!.provisionPending)
            assertTrue(model.screen.value!!.canReload)
            assertFalse(model.screen.value!!.canApply)
            assertFalse(model.screen.value!!.canPrepare)
            assertFalse(model.screen.value!!.canActivate)
            model.applyCurrentPlan()
            drain(model)
            assertEquals(1, remote.applies)
        }
    }

    @Test fun aFreshServiceCannotBypassSavedUnresolvedProgressThroughPrepare() {
        for (phase in listOf("AWAITING_RELOAD", "CONFIRMING", "ROLLING_BACK", "RELOADING", "UNKNOWN")) {
            val data = NfcProvisionStoreTest.savedPlan(app.applicationInfo.uid).apply {
                putString("source", "RUNTIME"); putString("state", phase)
            }
            NfcProvisionStore(app.noBackupFilesDir).save(data, app.applicationInfo.uid, -1)
            withModel(Remote()) { model ->
                ready(model)
                assertTrue(model.screen.value!!.savedPlanRequiresResume)
                assertFalse(model.screen.value!!.canPrepare)
                assertFalse(model.screen.value!!.canCapture)
                assertTrue(model.screen.value!!.canResumePlan)
                model.prepareCurrentCard()
                model.refresh()
                drain(model)
                assertTrue(model.screen.value!!.savedPlanRequiresResume)
                assertFalse(model.screen.value!!.canPrepare)
                assertTrue(model.screen.value!!.canResumePlan)
            }
        }
    }

    @Test fun preparationUsesOriginalIdentityRegardlessOfObsoleteMode() {
        val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
        CardStore(app).apply { put(card); select(card.profileId) }
        val preferences = app.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE)
        for (mode in listOf("FIXED_COMPATIBILITY", "GENERATED_COMPATIBILITY", "PREFIX_COMPATIBILITY")) {
            preferences.edit().putString("idm_route_mode", mode).putBoolean("compatibility_mode", true).commit()
            val before = preferences.all
            val remote = Remote().apply {
                prepareReply = { token, id ->
                    assertEquals(card.idm, id)
                    Bundle().apply {
                        putString("state", "ALREADY_PRESENT"); putString("token", token)
                        putString("nfcid2", id); putString("source", "RUNTIME")
                    }
                }
            }
            withModel(remote) { model ->
                ready(model)
                model.prepareCurrentCard()
                drain(model)
                assertEquals(1, remote.prepares)
                assertEquals(0, remote.applies)
                assertEquals(card.idm, model.screen.value!!.provisionIdm)
                assertEquals(NfcProvisionWorkflow.State.ALREADY_PRESENT, model.screen.value!!.provision.state)
                assertFalse(model.screen.value!!.provisionPending)
                assertEquals(before, preferences.all)
            }
        }
    }

    @Test fun alternateIdentityPlanCannotBeAppliedForOriginalCard() {
        val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
        CardStore(app).apply { put(card); select(card.profileId) }
        val remote = Remote()
        withModel(remote) { model ->
            ready(model)
            (model.screen as MutableLiveData).value = model.screen.value!!.copy(
                planSaved = true, provisionIdm = "02FE112233445566",
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.PREPARED, "a".repeat(32)))
            model.applyCurrentPlan()
            drain(model)
            assertEquals(0, remote.applies)
            assertFalse(CardStore(app).persistedRouteOnlyMode())
            assertTrue(app.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).all.isEmpty())
        }
    }

    private fun ready(model: ShizukuProbeModel) {
        (model.screen as MutableLiveData).value = model.screen.value!!.copy(
            connection = ShizukuProbeModel.Connection.READY, uid = 2000, bu = true,
            snapshotCandidate = true, provisionCandidate = true)
    }

    private fun withModel(remote: Remote, body: (ShizukuProbeModel) -> Unit) {
        val model = ShizukuProbeModel(app)
        val owner = ViewModelStore().apply { put("model", model) }
        ShizukuProbeModel::class.java.getDeclaredField("service").apply { isAccessible = true }.set(model, remote)
        try { body(model) } finally { owner.clear() }
    }

    private fun drain(model: ShizukuProbeModel) {
        val worker = ShizukuProbeModel::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(model) as ExecutorService
        worker.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class Remote : IHceShellService.Stub() {
        var resumes = 0
        var runtimeReads = 0
        var runtimeQuery: (() -> NfcRuntimeInventory.Summary)? = null
        var phase = "VERIFIED"
        var applies = 0
        var beforeApply: (() -> Unit)? = null
        var prepares = 0
        var prepareReply: ((String, String) -> Bundle)? = null
        private var saved: Bundle? = null
        override fun inspect() = Bundle().apply {
            putInt("uid", 2000); putBoolean("bu", true)
            putBoolean("nfcSnapshotCandidate", true); putBoolean("nfcProvisioning", true)
        }
        override fun probeStatus() = Bundle().apply { putString("state", "IDLE") }
        override fun snapshotStatus() = probeStatus()
        override fun nfcProvisionStatus() = Bundle().apply {
            putString("state", if (saved == null) "IDLE" else phase)
            putString("token", saved?.getString("token").orEmpty())
            putString("nfcid2", saved?.getString("nfcid2").orEmpty())
            putBoolean("hasPlan", saved != null)
            putString("source", saved?.getString("source") ?: "FILE")
            putString("domain", saved?.getString("domain"))
        }
        override fun resumeNfcProvision(savedPlan: Bundle): Bundle {
            resumes++
            saved = Bundle(savedPlan)
            return nfcProvisionStatus()
        }
        override fun readNfcProvisionPlan(token: String) = Bundle(checkNotNull(saved)).apply { putString("state", phase) }
        override fun inspectNfcRuntime(): Bundle {
            runtimeReads++
            runtimeQuery?.let { return it().bundle() }
            return NfcRuntimeInventory.Summary(NfcRuntimeInventory.State.READ,
                NfcRuntimeInventory.Source.STRUCTURED, 1, 1, true, 1).bundle()
        }
        override fun startRestoreProbe(token: String): Bundle = error("Unexpected restore")
        override fun verifyPreviousProbe(token: String): Bundle = error("Unexpected probe")
        override fun startNfcSnapshot(token: String): Bundle = error("Unexpected snapshot")
        override fun readNfcSnapshot(token: String): Bundle = error("Unexpected snapshot")
        override fun prepareNfcProvision(token: String, nfcid2: String): Bundle {
            val reply = checkNotNull(prepareReply) { "Unexpected prepare" }
            prepares++
            return reply(token, nfcid2)
        }
        override fun applyNfcProvision(token: String): Bundle {
            checkNotNull(beforeApply).invoke()
            applies++
            phase = "AWAITING_RELOAD"
            return nfcProvisionStatus()
        }
        override fun reloadNfcProvision(token: String): Bundle = error("Unexpected reload")
        override fun rollbackNfcProvision(token: String, checkpoint: INfcRollbackCheckpoint): Bundle = error("Unexpected rollback")
        override fun destroy() = Unit
    }
}
