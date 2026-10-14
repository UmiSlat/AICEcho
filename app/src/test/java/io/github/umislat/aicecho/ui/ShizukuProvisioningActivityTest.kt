package io.github.umislat.aicecho.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import io.github.umislat.aicecho.provisioning.ShizukuProbeModel
import io.github.umislat.aicecho.provisioning.NfcProvisionWorkflow
import io.github.umislat.aicecho.provisioning.NfcStorageDomain
import org.robolectric.shadows.ShadowDialog
import androidx.appcompat.app.AlertDialog
import io.github.umislat.aicecho.R
import io.github.umislat.aicecho.data.CardProfile
import io.github.umislat.aicecho.data.CardStore
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
        RuntimeEnvironment.getApplication().getSharedPreferences("aicecho", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("storage_migrated", true).commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("shizuku_restore_probe", Context.MODE_PRIVATE)
            .edit().clear().commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("shizuku_nfc_snapshot", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(RuntimeEnvironment.getApplication().noBackupFilesDir, "nfc-snapshot.json").delete()
        RuntimeEnvironment.getApplication().getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(RuntimeEnvironment.getApplication().noBackupFilesDir, "nfc-provision-plan.json").delete()
        CardStore(RuntimeEnvironment.getApplication()).setAdvancedMode(true)
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
                assertTrue(buttons.single { it.text == activity.getString(R.string.shizuku_open) }.isEnabled)
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
                provisionSource = io.github.umislat.aicecho.provisioning.NfcProvisionPlan.BaselineSource.RUNTIME,
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
            assertEquals(setOf("label", "nfcid2", "details"), launched.extras!!.keySet().toSet())
            assertTrue(launched.getBooleanExtra("details", false))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(cards.persistedRouteOnlyMode())
            controller.pause().stop().restart().start().resume()
            assertSame(model, ViewModelProvider(activity)[ShizukuProbeModel::class.java])
            assertEquals(state.copy(connection = ShizukuProbeModel.Connection.MISSING), model.screen.value)
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
                snapshotCandidate = true, provisionCandidate = true, savedPlanAvailable = true,
                savedPlanRequiresResume = true)
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
        CardStore(app).setAdvancedMode(false)
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
            assertFalse(visible.contains(activity.getString(R.string.shizuku_card_reload)))
            assertFalse(visible.contains(activity.getString(R.string.device_capabilities)))
            assertFalse(visible.any { it.startsWith(activity.getString(R.string.shizuku_card_source_file)) })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ordinarySetupCanOpenDeviceChecksAfterFailureButNotWhileBusy() {
        CardStore(RuntimeEnvironment.getApplication()).setAdvancedMode(false)
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            val failed = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY,
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.FAILED))
            val screen = model.screen as MutableLiveData<ShizukuProbeModel.Screen>
            screen.value = failed
            val check = views(activity.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.device_capabilities) }
            assertTrue(visible(check))
            assertTrue(check.isEnabled)
            screen.value = failed.copy(working = true)
            assertTrue(visible(check))
            assertFalse(check.isEnabled)
            screen.value = failed
            assertTrue(check.isEnabled)
            check.performClick()
            assertEquals(DeviceCapabilitiesActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
            assertTrue(activity.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).all.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun storageDomainIsVisibleOnlyInAdvancedMode() {
        val app = RuntimeEnvironment.getApplication()
        for (advanced in listOf(false, true)) {
            CardStore(app).setAdvancedMode(advanced)
            val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
            try {
                val activity = controller.get()
                val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
                (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(
                    planSaved = true, provisionDomain = NfcStorageDomain.DE,
                    provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.PREPARED, "a".repeat(32)))
                val source = views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                    .single { it.text.startsWith(activity.getString(R.string.shizuku_card_source_file)) }
                assertEquals(advanced, source.text.contains(activity.getString(R.string.shizuku_card_storage_de)))
                assertEquals(advanced, visible(source))
                assertFalse(source.text.contains(activity.getString(R.string.shizuku_card_storage_ce)))
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun ordinarySetupShowsTheCurrentStepInsteadOfAllActions() {
        CardStore(RuntimeEnvironment.getApplication()).setAdvancedMode(false)
        val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
        CardStore(RuntimeEnvironment.getApplication()).apply { put(card); select(card.profileId) }
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            val steps = linkedMapOf(
                NfcProvisionWorkflow.State.IDLE to R.string.card_configure_and_use,
                NfcProvisionWorkflow.State.NEEDS_BASELINE to R.string.card_continue_setup,
                NfcProvisionWorkflow.State.PREPARED to R.string.setup_apply,
                NfcProvisionWorkflow.State.AWAITING_RELOAD to R.string.setup_load,
                NfcProvisionWorkflow.State.RELOADED to R.string.shizuku_card_activate,
                NfcProvisionWorkflow.State.ALREADY_PRESENT to R.string.shizuku_card_activate
            )
            val primaryLabels = (steps.values + R.string.shizuku_open).toSet().map(activity::getString)
            for ((state, next) in steps) {
                (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(
                    connection = ShizukuProbeModel.Connection.READY, uid = 2000, bu = true,
                    provisionCandidate = true, planSaved = state != NfcProvisionWorkflow.State.IDLE,
                    provisionIdm = card.idm,
                    canActivate = state in setOf(NfcProvisionWorkflow.State.RELOADED, NfcProvisionWorkflow.State.ALREADY_PRESENT),
                    provision = NfcProvisionWorkflow.Result(state, "a".repeat(32)))
                val buttons = views(activity.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
                    .filter { visible(it) && it.text.toString() in primaryLabels }.toList()
                assertEquals("Primary actions for $state", listOf(activity.getString(next)), buttons.map { it.text.toString() })
                assertTrue("Next action for $state", buttons.single().isEnabled)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun advancedDiagnosticsAreVisibleWithoutAnotherDisclosureButton() {
        CardStore(RuntimeEnvironment.getApplication()).setAdvancedMode(true)
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val all = views(activity.findViewById(android.R.id.content)).filter(::visible).toList()
            assertTrue(all.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.provision_diagnostics) })
            assertFalse(all.filterIsInstance<MaterialButton>().any { it.text == activity.getString(R.string.provision_diagnostics) })
            assertTrue(all.filterIsInstance<MaterialButton>().any { it.text == activity.getString(R.string.device_capabilities) })
            assertTrue(all.filterIsInstance<MaterialButton>().any { it.text == activity.getString(R.string.shizuku_test) })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ordinarySetupDoesNotHideBlockingDiagnosticOperations() {
        CardStore(RuntimeEnvironment.getApplication()).setAdvancedMode(false)
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            for (state in listOf(ShizukuProbeModel.Screen(pending = true), ShizukuProbeModel.Screen(snapshotPending = true))) {
                (model.screen as MutableLiveData).value = state
                assertTrue(views(activity.findViewById(android.R.id.content)).filter(::visible).filterIsInstance<TextView>()
                    .any { it.text == activity.getString(R.string.shizuku_pending) })
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ordinaryWizardStillRequiresRestoreAndReloadConfirmations() {
        val app = RuntimeEnvironment.getApplication()
        val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
        CardStore(app).apply { put(card); select(card.profileId); setAdvancedMode(false) }
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            val screen = model.screen as MutableLiveData<ShizukuProbeModel.Screen>
            val prepared = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY,
                uid = 2000, bu = true, provisionCandidate = true, planSaved = true, provisionIdm = card.idm,
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.PREPARED))
            screen.value = prepared
            val buttons = views(activity.findViewById(android.R.id.content)).filter(::visible).filterIsInstance<MaterialButton>()
            buttons.single { it.text == activity.getString(R.string.setup_apply) }.performClick()
            var dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any {
                it.text == activity.getString(R.string.setup_apply_confirm)
            })
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(app.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).all.isEmpty())
            screen.value = prepared.copy(provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.AWAITING_RELOAD))
            buttons.single { it.text == activity.getString(R.string.setup_load) }.performClick()
            dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(app.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE).all.isEmpty())
            assertEquals(NfcProvisionWorkflow.State.AWAITING_RELOAD, model.screen.value!!.provision.state)
        } finally { controller.pause().stop().destroy() }
    }

    private fun visible(view: View) = generateSequence(view) { it.parent as? View }
        .all { it.visibility == View.VISIBLE }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
