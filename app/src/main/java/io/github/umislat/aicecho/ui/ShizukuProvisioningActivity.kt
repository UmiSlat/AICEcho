package io.github.umislat.aicecho.ui

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.view.Gravity
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.umislat.aicecho.R
import io.github.umislat.aicecho.provisioning.NfcSnapshotWorkflow
import io.github.umislat.aicecho.provisioning.NfcProvisionWorkflow
import io.github.umislat.aicecho.provisioning.NfcProvisionPlan
import io.github.umislat.aicecho.provisioning.NfcStorageDomain
import io.github.umislat.aicecho.data.CardStore
import io.github.umislat.aicecho.provisioning.RestoreProbeWorkflow.State
import io.github.umislat.aicecho.provisioning.ShizukuProbeModel
import io.github.umislat.aicecho.provisioning.ShizukuProbeModel.Connection

class ShizukuProvisioningActivity : AppCompatActivity() {
    private lateinit var model: ShizukuProbeModel
    private lateinit var connectionStatus: TextView
    private lateinit var capabilities: TextView
    private lateinit var deviceCheck: MaterialButton
    private lateinit var deviceSummary: TextView
    private lateinit var result: TextView
    private lateinit var connect: MaterialButton
    private lateinit var refresh: MaterialButton
    private lateinit var start: MaterialButton
    private lateinit var verify: MaterialButton
    private lateinit var snapshotStatus: TextView
    private lateinit var snapshotSummary: TextView
    private lateinit var capture: MaterialButton
    private lateinit var delete: MaterialButton
    private lateinit var provisionStatus: TextView
    private lateinit var preparedIdentity: TextView
    private lateinit var provisionSource: TextView
    private lateinit var selectedCard: TextView
    private lateinit var activationStatus: TextView
    private lateinit var prepareCard: MaterialButton
    private lateinit var resumePlan: MaterialButton
    private lateinit var initializeCard: MaterialButton
    private lateinit var applyCard: MaterialButton
    private lateinit var reloadNfc: MaterialButton
    private lateinit var activateCard: MaterialButton
    private lateinit var rollbackCard: MaterialButton
    private lateinit var diagnostics: LinearLayout
    private lateinit var wizardAction: MaterialButton
    private lateinit var wizardProgress: LinearProgressIndicator
    private val stages = mutableListOf<TextView>()
    private var launchingEmulation = false
    private var resumed = false
    private var startRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startRequested = savedInstanceState?.getBoolean(EXTRA_BEGIN_SETUP)
            ?: intent.getBooleanExtra(EXTRA_BEGIN_SETUP, false)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[ShizukuProbeModel::class.java]
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.shizuku_title)
            setTitleTextAppearance(this@ShizukuProvisioningActivity, R.style.TextAppearance_AICEcho_Toolbar)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { leave() }
        })
        val content = verticalLayout(this)
        diagnostics = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        diagnostics.addView(sectionTitle(R.string.provision_diagnostics))
        selectedCard = label(content).apply { textSize = 18f }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((index, title) in listOf(R.string.setup_stage_check, R.string.setup_stage_apply,
                R.string.setup_stage_load, R.string.setup_stage_done).withIndex()) {
                val stage = TextView(this@ShizukuProvisioningActivity).apply {
                    text = getString(R.string.setup_stage_label, index + 1, getString(title))
                    gravity = Gravity.CENTER
                    textSize = 13f
                    minHeight = dp(64)
                    setPadding(dp(2), dp(8), dp(2), dp(8))
                }
                stages.add(stage)
                addView(stage, LinearLayout.LayoutParams(0, -2, 1f))
            }
        })
        wizardProgress = LinearProgressIndicator(this).apply { isIndeterminate = true }
        content.addView(wizardProgress, LinearLayout.LayoutParams(-1, dp(4)))
        connectionStatus = label(content)
        capabilities = label(diagnostics)
        connect = button(diagnostics, R.string.shizuku_connect, primary = true) { ShizukuConnectionUi.act(this, model) }
        deviceSummary = label(diagnostics)
        refresh = button(diagnostics, R.string.shizuku_refresh) { model.refresh() }
        provisionStatus = label(content)
        wizardAction = button(content, R.string.card_configure_and_use, primary = true) { advanceSetup() }
            .apply { setIconResource(R.drawable.ic_read_nfc) }
        provisionSource = label(diagnostics)
        preparedIdentity = label(diagnostics)
        resumePlan = button(diagnostics, R.string.shizuku_card_resume) {
            confirm(R.string.shizuku_card_resume, R.string.shizuku_card_resume_confirm) { model.resumePreviousPlan() }
        }
        prepareCard = button(diagnostics, R.string.shizuku_card_prepare, primary = true) {
            confirm(R.string.shizuku_card_prepare, R.string.shizuku_card_prepare_confirm) { model.prepareCurrentCard() }
        }.apply { setIconResource(R.drawable.ic_read_nfc) }
        initializeCard = button(diagnostics, R.string.shizuku_card_initialize, primary = true) {
            confirm(R.string.shizuku_card_initialize, R.string.shizuku_card_initialize_confirm) { model.initializeBaseline() }
        }
        applyCard = button(diagnostics, R.string.shizuku_card_apply, primary = true) {
            confirm(R.string.shizuku_card_apply, R.string.shizuku_card_apply_confirm) { model.applyCurrentPlan() }
        }
        reloadNfc = button(diagnostics, R.string.shizuku_card_reload, primary = true) {
            confirm(R.string.shizuku_card_reload, R.string.shizuku_card_reload_confirm) { model.reloadNfc() }
        }.apply { setIconResource(android.R.drawable.ic_popup_sync) }
        activateCard = button(diagnostics, R.string.shizuku_card_activate, primary = true) { activatePreparedCard() }
        activationStatus = label(content)
        rollbackCard = button(diagnostics, R.string.shizuku_card_rollback) {
            confirm(R.string.shizuku_card_rollback, R.string.shizuku_card_rollback_confirm) { model.rollbackNfc() }
        }
        deviceCheck = button(content, R.string.device_capabilities) {
            startActivity(Intent(this, DeviceCapabilitiesActivity::class.java))
        }.apply { setIconResource(R.drawable.ic_nav_status) }
        label(diagnostics).setText(R.string.shizuku_snapshot_capture)
        snapshotStatus = label(diagnostics)
        snapshotSummary = label(diagnostics)
        capture = button(diagnostics, R.string.shizuku_snapshot_capture) {
            if (model.screen.value?.canCapture == true) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.shizuku_snapshot_capture)
                    .setMessage(R.string.shizuku_snapshot_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.shizuku_snapshot_capture) { _, _ -> model.captureSnapshot() }
                    .show()
            }
        }.apply { setIconResource(R.drawable.ic_read_nfc) }
        delete = button(diagnostics, R.string.shizuku_snapshot_delete) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.shizuku_snapshot_delete)
                .setMessage(R.string.shizuku_snapshot_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.shizuku_snapshot_delete) { _, _ -> model.deleteSnapshot() }
                .show()
        }.apply { setIconResource(android.R.drawable.ic_menu_delete) }
        label(diagnostics).setText(R.string.shizuku_lab_heading)
        result = label(diagnostics)
        start = button(diagnostics, R.string.shizuku_test) {
            if (model.screen.value?.canStart == true) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.shizuku_test)
                    .setMessage(R.string.shizuku_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.shizuku_test) { _, _ -> model.startProbe() }
                    .show()
            }
        }
        verify = button(diagnostics, R.string.shizuku_verify) { model.verifyPrevious() }
        content.addView(diagnostics)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setInsetAwareContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        model.screen.observe(this) { render(it) }
    }

    private fun render(screen: ShizukuProbeModel.Screen) {
        connectionStatus.setText(CapabilityText.connection(screen.connection))
        connect.setText(ShizukuConnectionUi.label(screen.connection))
        deviceSummary.setText(CapabilityText.summary(screen))
        deviceCheck.isEnabled = !screen.busy
        capabilities.text = if (screen.connection == Connection.READY) {
            getString(R.string.shizuku_capabilities, screen.uid, yesNo(screen.bu), yesNo(screen.markerApp),
                yesNo(screen.identityReadable))
        } else getString(R.string.shizuku_unchecked)
        result.setText(when (screen.probe.state) {
            State.IDLE -> R.string.shizuku_idle
            State.PREPARING -> R.string.shizuku_preparing
            State.CONFIRMING -> R.string.shizuku_confirming
            State.VERIFYING -> R.string.shizuku_verifying
            State.VERIFIED -> R.string.shizuku_verified
            State.NOT_VERIFIED -> R.string.shizuku_not_verified
            State.UNKNOWN -> R.string.shizuku_unknown
            State.FAILED -> R.string.shizuku_failed
            State.MARKERS_MATCH -> R.string.shizuku_markers_match
        })
        if (screen.pending && !screen.probe.busy) result.append("\n\n" + getString(R.string.shizuku_pending))
        connect.isEnabled = screen.connection !in setOf(Connection.READY, Connection.CONNECTING, Connection.PERMISSION)
        connect.visibility = if (screen.connection == Connection.READY) View.GONE else View.VISIBLE
        refresh.isEnabled = screen.connection == Connection.READY && !screen.busy
        start.isEnabled = screen.canStart
        verify.isEnabled = screen.canVerify
        renderSnapshot(screen)
        renderProvision(screen)
        if (startRequested && resumed && screen.canPrepare &&
            CardSetupStep.resolve(screen, CardStore(this).selectedProfile()?.idm == screen.provisionIdm) == CardSetupStep.CHECK) {
            startRequested = false
            model.beginSetup()
        }
    }

    private fun renderSnapshot(screen: ShizukuProbeModel.Screen) {
        snapshotStatus.setText(when (screen.snapshot.state) {
            NfcSnapshotWorkflow.State.IDLE -> if (screen.snapshotCandidate) R.string.shizuku_snapshot_candidate
                else if (screen.connection == Connection.READY) R.string.shizuku_snapshot_unsupported
                else R.string.shizuku_snapshot_idle
            NfcSnapshotWorkflow.State.PREPARING -> R.string.shizuku_snapshot_preparing
            NfcSnapshotWorkflow.State.EXPORTING -> R.string.shizuku_snapshot_exporting
            NfcSnapshotWorkflow.State.VERIFYING -> R.string.shizuku_snapshot_verifying
            NfcSnapshotWorkflow.State.CAPTURED -> R.string.shizuku_snapshot_captured
            NfcSnapshotWorkflow.State.REJECTED -> R.string.shizuku_snapshot_rejected
            NfcSnapshotWorkflow.State.FAILED -> R.string.shizuku_snapshot_failed
            NfcSnapshotWorkflow.State.UNKNOWN -> R.string.shizuku_snapshot_unknown
        })
        if (screen.snapshotPending && !screen.snapshot.busy) {
            snapshotStatus.append("\n\n" + getString(R.string.shizuku_snapshot_pending))
        }
        CapabilityText.failure(screen.snapshot.detail)?.let { snapshotStatus.append("\n" + getString(it)) }
        snapshotSummary.text = screen.savedSnapshot?.let { saved ->
            val recent = saved.recent(Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1),
                SystemClock.elapsedRealtime(), Build.FINGERPRINT)
            getString(R.string.shizuku_snapshot_summary, DateFormat.format("yyyy-MM-dd HH:mm:ss", saved.wall),
                saved.records, saved.bytes, saved.hash.take(12), getString(if (recent)
                    R.string.shizuku_snapshot_recent else R.string.shizuku_snapshot_stale))
        } ?: getString(R.string.shizuku_snapshot_none)
        capture.isEnabled = screen.canCapture
        delete.isEnabled = screen.savedSnapshot != null && !screen.busy && !screen.pending && !screen.snapshotPending && !screen.provisionPending
    }

    private fun renderProvision(screen: ShizukuProbeModel.Screen) {
        val cards = CardStore(this)
        val profile = cards.selectedProfile()
        val advanced = cards.advancedMode()
        diagnostics.isVisible = advanced
        val matches = profile != null && profile.idm == screen.provisionIdm
        selectedCard.text = profile?.let { getString(R.string.current_card_summary, it.label) }
            ?: getString(R.string.shizuku_card_missing)
        provisionStatus.setText(when (screen.provision.state) {
            NfcProvisionWorkflow.State.IDLE -> R.string.shizuku_card_idle
            NfcProvisionWorkflow.State.PREPARING -> R.string.shizuku_card_preparing
            NfcProvisionWorkflow.State.NEEDS_BASELINE -> R.string.shizuku_card_needs_baseline
            NfcProvisionWorkflow.State.PREPARED -> R.string.shizuku_card_prepared
            NfcProvisionWorkflow.State.ALREADY_PRESENT -> R.string.shizuku_card_already_present
            NfcProvisionWorkflow.State.CHECKING -> R.string.shizuku_card_checking
            NfcProvisionWorkflow.State.CONFIRMING -> R.string.shizuku_confirming
            NfcProvisionWorkflow.State.VERIFYING -> R.string.shizuku_card_verifying
            NfcProvisionWorkflow.State.VERIFIED -> if (screen.provisionSource == NfcProvisionPlan.BaselineSource.RUNTIME)
                R.string.shizuku_card_runtime_verified else R.string.shizuku_card_verified
            NfcProvisionWorkflow.State.AWAITING_RELOAD -> R.string.shizuku_card_awaiting_reload
            NfcProvisionWorkflow.State.UNCHANGED -> R.string.shizuku_card_unchanged
            NfcProvisionWorkflow.State.CONFLICT -> R.string.shizuku_card_conflict
            NfcProvisionWorkflow.State.RELOADING -> R.string.shizuku_card_reloading
            NfcProvisionWorkflow.State.RELOADED -> R.string.shizuku_card_reloaded
            NfcProvisionWorkflow.State.ROLLING_BACK -> R.string.shizuku_card_rolling_back
            NfcProvisionWorkflow.State.ROLLED_BACK -> R.string.shizuku_card_rolled_back
            NfcProvisionWorkflow.State.FAILED -> R.string.shizuku_card_failed
            NfcProvisionWorkflow.State.UNKNOWN -> R.string.shizuku_unknown
        })
        if (!advanced) {
            val summary = when (screen.provision.state) {
            NfcProvisionWorkflow.State.IDLE -> R.string.setup_ready_to_check
            NfcProvisionWorkflow.State.PREPARING, NfcProvisionWorkflow.State.NEEDS_BASELINE -> R.string.setup_checking
            NfcProvisionWorkflow.State.PREPARED, NfcProvisionWorkflow.State.UNCHANGED -> R.string.setup_prepared
            NfcProvisionWorkflow.State.CHECKING, NfcProvisionWorkflow.State.VERIFYING -> R.string.setup_checking_result
            NfcProvisionWorkflow.State.CONFIRMING -> R.string.setup_system_confirmation
            NfcProvisionWorkflow.State.VERIFIED, NfcProvisionWorkflow.State.AWAITING_RELOAD,
            NfcProvisionWorkflow.State.ROLLED_BACK -> R.string.setup_needs_load
            NfcProvisionWorkflow.State.RELOADING -> R.string.setup_loading
            NfcProvisionWorkflow.State.RELOADED, NfcProvisionWorkflow.State.ALREADY_PRESENT ->
                if (screen.canActivate && matches) R.string.card_configured else R.string.setup_checking_result
                else -> null
            }
            summary?.let(provisionStatus::setText)
        }
        if (screen.provisionPending && !screen.provision.busy) provisionStatus.append("\n" + getString(R.string.shizuku_pending))
        if ((screen.pending || screen.snapshotPending) && !screen.busy)
            provisionStatus.setText(R.string.shizuku_pending)
        if (screen.connection == Connection.READY && !screen.provisionCandidate &&
            screen.provision.state == NfcProvisionWorkflow.State.IDLE) {
            provisionStatus.setText(CapabilityText.readiness(screen))
        }
        CapabilityText.failure(screen.provision.detail)?.let { provisionStatus.append("\n" + getString(it)) }
        if (screen.savedPlanRequiresResume && !screen.planSaved && !screen.provisionPending && !screen.provision.busy)
            provisionStatus.setText(R.string.shizuku_card_resume_required)
        provisionStatus.isVisible = screen.connection == Connection.READY || screen.busy || screen.provisionPending ||
            screen.pending || screen.snapshotPending ||
            screen.savedPlanRequiresResume
        provisionSource.setText(if (screen.provisionSource == NfcProvisionPlan.BaselineSource.RUNTIME)
            R.string.shizuku_card_source_runtime else R.string.shizuku_card_source_file)
        if (advanced) screen.provisionDomain?.let {
            provisionSource.append("\n" + getString(if (it == NfcStorageDomain.DE)
                R.string.shizuku_card_storage_de else R.string.shizuku_card_storage_ce))
        }
        provisionSource.isVisible = screen.planSaved || screen.canActivate
        preparedIdentity.text = if (screen.provisionIdm.isEmpty()) ""
            else getString(R.string.shizuku_card_plan_identity, screen.provisionIdm)
        preparedIdentity.visibility = if (advanced && screen.provisionIdm.isNotEmpty()) View.VISIBLE else View.GONE
        activationStatus.text = if (profile != null && screen.provisionIdm.isNotEmpty() && !matches)
            getString(R.string.shizuku_card_selection_changed) else ""
        activationStatus.isVisible = activationStatus.text.isNotEmpty()
        resumePlan.isVisible = screen.savedPlanAvailable && !screen.planSaved &&
            (advanced || screen.savedPlanRequiresResume)
        resumePlan.isEnabled = screen.canResumePlan
        prepareCard.isEnabled = screen.canPrepare && profile != null
        prepareCard.isVisible = screen.connection == Connection.READY && !screen.busy && !screen.provisionPending &&
            !(screen.savedPlanRequiresResume && !screen.planSaved) &&
            screen.provision.state !in setOf(NfcProvisionWorkflow.State.AWAITING_RELOAD, NfcProvisionWorkflow.State.NEEDS_BASELINE) &&
            (!matches || screen.provision.state in setOf(NfcProvisionWorkflow.State.IDLE,
                NfcProvisionWorkflow.State.FAILED, NfcProvisionWorkflow.State.CONFLICT))
        initializeCard.visibility = if (screen.provision.state == NfcProvisionWorkflow.State.NEEDS_BASELINE) View.VISIBLE else View.GONE
        initializeCard.isEnabled = screen.canPrepare
        applyCard.isEnabled = screen.canApply && matches
        applyCard.isVisible = matches && screen.provision.state in setOf(NfcProvisionWorkflow.State.PREPARED,
            NfcProvisionWorkflow.State.UNCHANGED)
        reloadNfc.isEnabled = screen.canReload
        reloadNfc.isVisible = screen.provision.state in setOf(NfcProvisionWorkflow.State.VERIFIED,
            NfcProvisionWorkflow.State.ROLLED_BACK, NfcProvisionWorkflow.State.AWAITING_RELOAD) ||
            (advanced && screen.canReload)
        activateCard.isEnabled = screen.canActivate && matches && !screen.busy && !screen.provisionPending
        activateCard.isVisible = screen.canActivate && matches
        rollbackCard.isEnabled = screen.canRollback
        rollbackCard.isVisible = screen.canRollback
        renderWizard(screen, matches, profile != null, advanced)
    }

    private fun renderWizard(screen: ShizukuProbeModel.Screen, matches: Boolean, hasCard: Boolean, advanced: Boolean) {
        val step = CardSetupStep.resolve(screen, matches)
        val index = CardSetupStep.stage(screen, step)
        stages.forEachIndexed { position, view ->
            view.setTypeface(view.typeface, if (position == index) Typeface.BOLD else Typeface.NORMAL)
            view.setTextColor(MaterialColors.getColor(view, if (position == index)
                com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
            view.alpha = if (position > index) 0.55f else 1f
        }
        wizardProgress.visibility = if (screen.busy || screen.connection == Connection.CONNECTING) View.VISIBLE else View.INVISIBLE
        wizardAction.isVisible = !advanced
        if (advanced) wizardAction.text = ""
        else wizardAction.setText(if (step == CardSetupStep.CONNECT) ShizukuConnectionUi.label(screen.connection) else step.label)
        wizardAction.isEnabled = step.enabled(screen, matches, hasCard)
        wizardAction.setIconResource(if (step == CardSetupStep.RELOAD) android.R.drawable.ic_popup_sync else R.drawable.ic_read_nfc)
        connectionStatus.isVisible = advanced || screen.connection != Connection.READY
        deviceCheck.isVisible = advanced || screen.provision.state in setOf(NfcProvisionWorkflow.State.FAILED,
            NfcProvisionWorkflow.State.UNKNOWN, NfcProvisionWorkflow.State.CONFLICT) ||
            (screen.connection == Connection.READY && !screen.provisionCandidate)
    }

    private fun advanceSetup() {
        val screen = model.screen.value ?: return
        val profile = CardStore(this).selectedProfile()
        val matches = profile != null && profile.idm == screen.provisionIdm
        val step = CardSetupStep.resolve(screen, matches)
        if (!step.enabled(screen, matches, profile != null)) return
        when (step) {
            CardSetupStep.CONNECT -> ShizukuConnectionUi.act(this, model)
            CardSetupStep.CHECK -> model.beginSetup()
            CardSetupStep.RESUME -> model.resumePreviousPlan()
            CardSetupStep.INITIALIZE -> model.initializeBaseline()
            CardSetupStep.APPLY -> confirm(R.string.setup_apply, R.string.setup_apply_confirm) { model.applyCurrentPlan() }
            CardSetupStep.RELOAD -> confirm(R.string.setup_load, R.string.shizuku_card_reload_confirm) { model.reloadNfc() }
            CardSetupStep.START -> activatePreparedCard()
            CardSetupStep.BLOCKED, CardSetupStep.BUSY -> Unit
        }
    }

    private fun activatePreparedCard() {
        val screen = model.screen.value ?: return
        if (!screen.canActivate || screen.busy || screen.provisionPending || launchingEmulation) return
        val store = CardStore(this)
        val selected = store.selectedProfile() ?: return
        if (selected.idm != screen.provisionIdm) {
            activationStatus.setText(R.string.shizuku_card_selection_changed)
            return
        }
        store.setPersistedRouteOnlyMode(true)
        activationStatus.text = ""
        launchingEmulation = true
        startActivity(PersistedEmulationActivity.intent(this, selected.label, screen.provisionIdm, store.advancedMode()))
    }

    private fun confirm(title: Int, message: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(title) { _, _ -> action() }.show()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        launchingEmulation = false
        model.screen.value?.let(::render)
        model.onForeground()
    }

    override fun onPause() {
        resumed = false
        model.onBackground()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXTRA_BEGIN_SETUP, startRequested)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val EXTRA_BEGIN_SETUP = "begin_setup"
        fun intent(context: Context, beginSetup: Boolean) = Intent(context, ShizukuProvisioningActivity::class.java)
            .putExtra(EXTRA_BEGIN_SETUP, beginSetup)
    }

    private fun leave() {
        val state = model.screen.value
        if (state?.busy == true) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.shizuku_busy_title)
                .setMessage(R.string.shizuku_busy_body)
                .setPositiveButton(android.R.string.ok, null).show()
        } else finish()
    }

    private fun yesNo(value: Boolean) = getString(if (value) R.string.shizuku_yes else R.string.shizuku_no)

    private fun label(parent: LinearLayout) = TextView(this).apply {
        textSize = 14f
        setPadding(0, dp(12), 0, dp(8))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(parent: LinearLayout, title: Int, primary: Boolean = false, action: () -> Unit) = MaterialButton(
        this, null, if (primary) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        setText(title)
        maxLines = 2
        minHeight = dp(48)
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }
}
