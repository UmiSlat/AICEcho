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
        private var saved: Bundle? = null
        override fun inspect() = Bundle().apply {
            putInt("uid", 2000); putBoolean("bu", true)
            putBoolean("nfcSnapshotCandidate", true); putBoolean("nfcProvisioning", true)
        }
        override fun probeStatus() = Bundle().apply { putString("state", "IDLE") }
        override fun snapshotStatus() = probeStatus()
        override fun nfcProvisionStatus() = Bundle().apply {
            putString("state", if (saved == null) "IDLE" else "VERIFIED")
            putString("token", saved?.getString("token").orEmpty())
            putString("nfcid2", saved?.getString("nfcid2").orEmpty())
            putBoolean("hasPlan", saved != null)
        }
        override fun resumeNfcProvision(savedPlan: Bundle): Bundle {
            resumes++
            saved = Bundle(savedPlan)
            return nfcProvisionStatus()
        }
        override fun readNfcProvisionPlan(token: String) = Bundle(checkNotNull(saved))
        override fun startRestoreProbe(token: String): Bundle = error("Unexpected restore")
        override fun verifyPreviousProbe(token: String): Bundle = error("Unexpected probe")
        override fun startNfcSnapshot(token: String): Bundle = error("Unexpected snapshot")
        override fun readNfcSnapshot(token: String): Bundle = error("Unexpected snapshot")
        override fun prepareNfcProvision(token: String, nfcid2: String): Bundle = error("Unexpected prepare")
        override fun applyNfcProvision(token: String): Bundle = error("Unexpected write")
        override fun reloadNfcProvision(token: String): Bundle = error("Unexpected reload")
        override fun rollbackNfcProvision(token: String): Bundle = error("Unexpected rollback")
        override fun destroy() = Unit
    }
}
