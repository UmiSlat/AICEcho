package io.github.umislat.aimesimulator.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import io.github.umislat.aimesimulator.R
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.provisioning.AospBackupCapabilities
import io.github.umislat.aimesimulator.provisioning.NfcSnapshotWorkflow
import io.github.umislat.aimesimulator.provisioning.NfcRuntimeInventory
import io.github.umislat.aimesimulator.provisioning.ShizukuProbeModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.shadows.ShadowProcess
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class, qualifiers = "w320dp-h480dp",
    shadows = [PersistedEmulationActivityTest.NfcFShadow::class])
class DeviceCapabilitiesActivityTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val capable = AospBackupCapabilities(true, 0, 0, true, AospBackupCapabilities.Service.ACTIVE,
        AospBackupCapabilities.Target.READY, true, hcef = true)

    @Before fun reset() {
        ShadowProcess.setUid(10000)
        app.applicationInfo.uid = 10000
        for (name in listOf("aime_simulator", "shizuku_restore_probe", "shizuku_nfc_snapshot", "shizuku_nfc_provision")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        for (name in listOf("nfc-snapshot.json", "nfc-provision-plan.json")) File(app.noBackupFilesDir, name).delete()
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF, true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        PersistedEmulationActivityTest.NfcFShadow.calls.clear()
    }

    @Test fun noCardAndRotationDoNotStartBackupOrEnableEmulation() {
        val controller = Robolectric.buildActivity(DeviceCapabilitiesActivity::class.java).setup()
        try {
            repeat(2) {
                val activity = controller.get()
                assertTrue(texts(activity).contains(activity.getString(R.string.shizuku_card_missing)))
                assertFalse(button(activity, R.string.capability_verify).isEnabled)
                assertFalse(button(activity, R.string.capability_runtime_read).isEnabled)
                assertFalse(button(activity, R.string.emulation_setup).isEnabled)
                button(activity, R.string.capability_refresh).performClick()
                assertTrue(app.getSharedPreferences("shizuku_nfc_snapshot", Context.MODE_PRIVATE).all.isEmpty())
                if (it == 0) controller.recreate()
            }
            assertTrue(PersistedEmulationActivityTest.NfcFShadow.calls.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun kvAgentShowsLimitationAndProbeHasSeparateConfirmation() {
        val controller = Robolectric.buildActivity(DeviceCapabilitiesActivity::class.java).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY,
                uid = 2000, bu = true, aosp = capable, snapshotCandidate = true)
            assertTrue(texts(activity).contains(activity.getString(R.string.capability_key_value)))
            assertTrue(texts(activity).contains(activity.getString(R.string.capability_restore_unverified)))
            assertFalse(model.screen.value!!.canPrepare)
            assertTrue(button(activity, R.string.capability_verify).isEnabled)
            button(activity, R.string.capability_verify).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(app.getSharedPreferences("shizuku_nfc_snapshot", Context.MODE_PRIVATE).all.isEmpty())
            model.screen.value = model.screen.value!!.copy(snapshot = NfcSnapshotWorkflow.Result(
                NfcSnapshotWorkflow.State.REJECTED, detail = "IDENTITY_MISSING"))
            assertTrue(texts(activity).contains(activity.getString(R.string.capability_identity_missing)))
            assertFalse(model.screen.value!!.canPrepare)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun registrationCheckOnlyReadsAndNeverDisplaysTheCardNumber() {
        val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
        CardStore(app).apply { put(card); select(card.profileId) }
        PersistedEmulationActivityTest.NfcFShadow.idm = card.idm
        PersistedEmulationActivityTest.NfcFShadow.systemCode = "88B4"
        val controller = Robolectric.buildActivity(DeviceCapabilitiesActivity::class.java).setup()
        try {
            val activity = controller.get()
            button(activity, R.string.capability_refresh).performClick()
            assertTrue(texts(activity).contains(activity.getString(R.string.capability_registration_match)))
            assertFalse(texts(activity).contains(card.idm))
            assertTrue(PersistedEmulationActivityTest.NfcFShadow.calls.isNotEmpty())
            assertTrue(PersistedEmulationActivityTest.NfcFShadow.calls.all { it in setOf("systemCode", "nfcid2") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nfcBroadcastRefreshesHardwareAndDisconnectionCannotKeepExportEnabled() {
        val controller = Robolectric.buildActivity(DeviceCapabilitiesActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(false)
            app.sendBroadcast(Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(texts(activity).contains(activity.getString(R.string.nfc_disabled)))
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.ERROR,
                uid = 2000, bu = true, snapshotCandidate = true)
            assertFalse(button(activity, R.string.capability_verify).isEnabled)
            assertFalse(button(activity, R.string.capability_runtime_read).isEnabled)
            assertTrue(texts(activity).contains(activity.getString(R.string.capability_service_unknown)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun runtimeReadDoesNotRequireFullBackupAndCannotEnableProvisioning() {
        val controller = Robolectric.buildActivity(DeviceCapabilitiesActivity::class.java).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(
                connection = ShizukuProbeModel.Connection.READY, uid = 2000, bu = false,
                aosp = capable.copy(service = AospBackupCapabilities.Service.INACTIVE),
                runtime = NfcRuntimeInventory.Summary(NfcRuntimeInventory.State.READ,
                    NfcRuntimeInventory.Source.STRUCTURED, 2, 1, true, 2))
            assertTrue(button(activity, R.string.capability_runtime_read).isEnabled)
            assertFalse(button(activity, R.string.capability_verify).isEnabled)
            assertFalse(model.screen.value!!.canPrepare)
            assertFalse(model.screen.value!!.canApply)
            assertFalse(model.screen.value!!.canReload)
            assertTrue(texts(activity).contains(activity.getString(R.string.capability_runtime_preview, 2)))
            assertTrue(texts(activity).contains(activity.getString(R.string.capability_runtime_boundary)))
            assertTrue(app.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).all.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun dumpFallbackReportsEffectiveOnlyAndDoesNotClaimXmlPreview() {
        val controller = Robolectric.buildActivity(DeviceCapabilitiesActivity::class.java).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(
                connection = ShizukuProbeModel.Connection.READY, uid = 2000,
                runtime = NfcRuntimeInventory.Summary(NfcRuntimeInventory.State.READ,
                    NfcRuntimeInventory.Source.DUMPSYS, 2, 1, fallback = true))
            assertTrue(texts(activity).contains(activity.getString(R.string.capability_runtime_dump, 2, 1)))
            assertTrue(texts(activity).contains(activity.getString(R.string.capability_runtime_effective_only)))
            assertFalse(texts(activity).contains(activity.getString(R.string.capability_runtime_preview, 2)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unresolvedOperationsDisableRuntimeReads() {
        val ready = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY, uid = 2000)
        assertTrue(ready.canInspectRuntime)
        assertFalse(ready.copy(working = true).canInspectRuntime)
        assertFalse(ready.copy(snapshotPending = true).canInspectRuntime)
        assertFalse(ready.copy(provisionPending = true).canInspectRuntime)
        assertFalse(ready.copy(pending = true).canInspectRuntime)
        assertFalse(ready.copy(snapshot = NfcSnapshotWorkflow.Result(NfcSnapshotWorkflow.State.UNKNOWN)).canInspectRuntime)
    }

    private fun button(activity: DeviceCapabilitiesActivity, title: Int) = views(activity.findViewById(android.R.id.content))
        .filterIsInstance<MaterialButton>().single { it.text == activity.getString(title) }
    private fun texts(activity: DeviceCapabilitiesActivity) = views(activity.findViewById(android.R.id.content))
        .filterIsInstance<TextView>().joinToString("\n") { it.text }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
