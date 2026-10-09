package io.github.umislat.aimesimulator.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import io.github.umislat.aimesimulator.provisioning.ShizukuProbeModel
import io.github.umislat.aimesimulator.provisioning.NfcProvisionWorkflow
import org.robolectric.shadows.ShadowDialog
import androidx.appcompat.app.AlertDialog
import io.github.umislat.aimesimulator.R
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.CardStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class, qualifiers = "w360dp-h640dp")
class ShizukuProvisioningActivityTest {
    @Before fun clear() {
        RuntimeEnvironment.getApplication().getSharedPreferences("aime_simulator", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("storage_migrated", true).commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("shizuku_restore_probe", Context.MODE_PRIVATE)
            .edit().clear().commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("shizuku_nfc_snapshot", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(RuntimeEnvironment.getApplication().noBackupFilesDir, "nfc-snapshot.json").delete()
        RuntimeEnvironment.getApplication().getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(RuntimeEnvironment.getApplication().noBackupFilesDir, "nfc-provision-plan.json").delete()
    }

    @Test fun openingAndRotatingDoNotAuthorizeOrRestore() {
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            repeat(2) {
                val activity = controller.get()
                val views = views(activity.findViewById(android.R.id.content)).toList()
                val buttons = views.filterIsInstance<MaterialButton>()
                assertFalse(buttons.single { it.text == activity.getString(R.string.shizuku_test) }.isEnabled)
                assertFalse(buttons.single { it.text == activity.getString(R.string.shizuku_verify) }.isEnabled)
                assertFalse(buttons.single { it.text == activity.getString(R.string.shizuku_snapshot_capture) }.isEnabled)
                assertFalse(buttons.single { it.text == activity.getString(R.string.shizuku_snapshot_delete) }.isEnabled)
                assertTrue(buttons.single { it.text == activity.getString(R.string.shizuku_connect) }.isEnabled)
                assertTrue(views.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.shizuku_idle) })
                assertTrue(activity.getSharedPreferences("shizuku_restore_probe", Context.MODE_PRIVATE).all.isEmpty())
                assertTrue(activity.getSharedPreferences("shizuku_nfc_snapshot", Context.MODE_PRIVATE).all.isEmpty())
                if (it == 0) controller.recreate()
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pendingProbeIsShownAndNotAutomaticallyRetried() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("shizuku_restore_probe", Context.MODE_PRIVATE).edit()
            .putBoolean("pending", true).putString("token", "a".repeat(32)).putInt("boot", -1).commit()
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val views = views(activity.findViewById(android.R.id.content)).toList()
            assertTrue(views.filterIsInstance<TextView>().joinToString("\n") { it.text }, views.filterIsInstance<TextView>().any {
                it.text.contains(activity.getString(R.string.shizuku_pending))
            })
            assertFalse(views.filterIsInstance<MaterialButton>().single {
                it.text == activity.getString(R.string.shizuku_test)
            }.isEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pendingSnapshotBlocksBothActionsAfterRecreation() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("shizuku_nfc_snapshot", Context.MODE_PRIVATE).edit()
            .putBoolean("pending", true).putString("token", "a".repeat(32)).putInt("boot", -1).commit()
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            controller.recreate()
            val activity = controller.get()
            val all = views(activity.findViewById(android.R.id.content)).toList()
            assertTrue(all.filterIsInstance<TextView>().any { it.text.contains(activity.getString(R.string.shizuku_snapshot_pending)) })
            for (id in listOf(R.string.shizuku_snapshot_capture, R.string.shizuku_test)) {
                assertFalse(all.filterIsInstance<MaterialButton>().single { it.text == activity.getString(id) }.isEnabled)
            }
            assertTrue(app.getSharedPreferences("shizuku_nfc_snapshot", Context.MODE_PRIVATE).getBoolean("pending", false))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun captureRequiresSeparateConfirmationAndCancelDoesNotJournal() {
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(
                connection = ShizukuProbeModel.Connection.READY, uid = 2000, snapshotCandidate = true)
            val capture = views(activity.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.shizuku_snapshot_capture) }
            assertTrue(capture.isEnabled)
            capture.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any {
                it.text == activity.getString(R.string.shizuku_snapshot_confirm)
            })
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(activity.getSharedPreferences("shizuku_nfc_snapshot", Context.MODE_PRIVATE).all.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun preparedPlanRequiresSavedOriginalBeforeWriteButtonIsEnabled() {
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
            CardStore(activity).apply { put(card); select(card.profileId) }
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            val state = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY, uid = 2000,
                bu = true, snapshotCandidate = true, provisionCandidate = true, provisionIdm = card.idm,
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.PREPARED, "a".repeat(32)))
            val write = views(activity.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.shizuku_card_apply) }
            (model.screen as MutableLiveData).value = state
            assertFalse(write.isEnabled)
            model.screen.value = state.copy(planSaved = true)
            assertTrue(write.isEnabled)
            write.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(activity.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).all.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun verifiedAndReloadedStatesExposeTheNextManualStep() {
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
            CardStore(activity).apply { put(card); select(card.profileId) }
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            val state = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY, uid = 2000,
                bu = true, snapshotCandidate = true, provisionCandidate = true, planSaved = true, provisionIdm = card.idm,
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.VERIFIED, "a".repeat(32)))
            val buttons = views(activity.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>().toList()
            (model.screen as MutableLiveData).value = state
            assertTrue(buttons.single { it.text == activity.getString(R.string.shizuku_card_reload) }.isEnabled)
            assertFalse(buttons.single { it.text == activity.getString(R.string.shizuku_card_activate) }.isEnabled)
            model.screen.value = state.copy(provision = state.provision.copy(state = NfcProvisionWorkflow.State.RELOADED), canActivate = true)
            assertTrue(buttons.single { it.text == activity.getString(R.string.shizuku_card_activate) }.isEnabled)
            assertTrue(buttons.single { it.text == activity.getString(R.string.shizuku_card_rollback) }.isEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun runtimeWaitingStateOffersReloadWithoutNewPreparationOrEmulation() {
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
            CardStore(activity).apply { put(card); select(card.profileId) }
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            val state = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY, uid = 2000,
                bu = true, snapshotCandidate = false, provisionCandidate = true, planSaved = true,
                provisionSource = io.github.umislat.aimesimulator.provisioning.NfcProvisionPlan.BaselineSource.RUNTIME,
                provisionIdm = card.idm,
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.AWAITING_RELOAD, "a".repeat(32)))
            (model.screen as MutableLiveData).value = state
            val all = views(activity.findViewById(android.R.id.content)).toList()
            val buttons = all.filterIsInstance<MaterialButton>()
            val reload = buttons.single { it.text == activity.getString(R.string.shizuku_card_reload) }
            assertTrue(reload.isEnabled)
            assertEquals(View.VISIBLE, reload.visibility)
            for (id in listOf(R.string.shizuku_card_prepare, R.string.shizuku_card_apply,
                R.string.shizuku_card_activate, R.string.shizuku_card_rollback, R.string.shizuku_snapshot_capture)) {
                assertFalse(buttons.single { it.text == activity.getString(id) }.isEnabled)
            }
            assertTrue(all.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.shizuku_card_awaiting_reload) })
            assertTrue(all.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.shizuku_card_source_runtime) })
            model.screen.value = state.copy(planSaved = false)
            assertFalse(reload.isEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun activationLaunchesPrivateSessionWithoutLosingPreparedPlan() {
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
            val cards = CardStore(activity).apply { put(card); select(card.profileId) }
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            val state = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY, uid = 2000,
                bu = true, snapshotCandidate = true, provisionCandidate = true, planSaved = true,
                canActivate = true, provisionIdm = card.idm,
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.RELOADED, "a".repeat(32)))
            (model.screen as MutableLiveData).value = state
            val activate = views(activity.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.shizuku_card_activate) }
            activate.performClick()
            activate.performClick()
            val launched = shadowOf(activity).nextStartedActivity
            assertEquals(PersistedEmulationActivity::class.java.name, launched.component?.className)
            assertEquals(card.idm, launched.getStringExtra(PersistedEmulationActivity.EXTRA_NFCID2))
            assertEquals(setOf("label", "nfcid2"), launched.extras!!.keySet())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(cards.persistedRouteOnlyMode())
            controller.pause().stop().restart().start().resume()
            assertSame(model, ViewModelProvider(activity)[ShizukuProbeModel::class.java])
            assertEquals(state, model.screen.value)
            assertTrue(state.canRollback)
            assertEquals(card, cards.selectedProfile())
            assertTrue(activity.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).all.isEmpty())
            activate.performClick()
            assertNotNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun oldCompatibilityPlanCannotLaunchInsteadOfOriginalIdentity() {
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
            val cards = CardStore(activity).apply {
                put(card); select(card.profileId)
            }
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(canActivate = true, provisionIdm = "02FE112233445566")
            views(activity.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.shizuku_card_activate) }.performClick()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(cards.persistedRouteOnlyMode())
            assertTrue(views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                .any { it.text == activity.getString(R.string.shizuku_card_selection_changed) })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun savedPlanRecoveryHasItsOwnConfirmationAndCancelDoesNothing() {
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(
                connection = ShizukuProbeModel.Connection.READY, uid = 2000, bu = true,
                snapshotCandidate = true, provisionCandidate = true, savedPlanAvailable = true)
            val button = views(activity.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.shizuku_card_resume) }
            assertEquals(View.VISIBLE, button.visibility)
            assertTrue(button.isEnabled)
            button.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text == activity.getString(R.string.shizuku_card_resume_confirm) })
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(activity.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).all.isEmpty())
            model.screen.value = model.screen.value!!.copy(provisionPending = true)
            assertFalse(button.isEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ordinarySetupHidesLabAndIdentityButKeepsCurrentStepVisible() {
        val app = RuntimeEnvironment.getApplication()
        val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
        CardStore(app).apply { put(card); select(card.profileId) }
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(
                connection = ShizukuProbeModel.Connection.READY, uid = 2000, bu = true,
                snapshotCandidate = true, provisionCandidate = true, canActivate = true, provisionIdm = card.idm,
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.ALREADY_PRESENT, "a".repeat(32)))
            val visible = views(activity.findViewById(android.R.id.content)).filter {
                generateSequence(it) { view -> view.parent as? View }.all { view -> view.visibility == View.VISIBLE }
            }.filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertFalse(visible.any { it.contains(card.idm) })
            assertFalse(visible.contains(activity.getString(R.string.shizuku_test)))
            assertFalse(visible.contains(activity.getString(R.string.shizuku_snapshot_capture)))
            assertFalse(visible.contains(activity.getString(R.string.shizuku_card_apply)))
            assertTrue(visible.contains(activity.getString(R.string.shizuku_card_activate)))
            assertTrue(visible.contains(activity.getString(R.string.shizuku_card_reload)))
            assertTrue(visible.contains(activity.getString(R.string.device_capabilities)))
        } finally { controller.pause().stop().destroy() }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
