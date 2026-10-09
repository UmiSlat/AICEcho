package io.github.umislat.aimesimulator.ui

import android.os.Bundle
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
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
import io.github.umislat.aimesimulator.R
import io.github.umislat.aimesimulator.provisioning.NfcSnapshotWorkflow
import io.github.umislat.aimesimulator.provisioning.NfcProvisionWorkflow
import io.github.umislat.aimesimulator.provisioning.NfcProvisionPlan
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.provisioning.RestoreProbeWorkflow.State
import io.github.umislat.aimesimulator.provisioning.ShizukuProbeModel
import io.github.umislat.aimesimulator.provisioning.ShizukuProbeModel.Connection

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
    private var launchingEmulation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[ShizukuProbeModel::class.java]
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.shizuku_title)
            setTitleTextAppearance(this@ShizukuProvisioningActivity, R.style.TextAppearance_AimeSimulator_Toolbar)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { leave() }
        })
        val content = verticalLayout(this)
        val diagnostics = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        connectionStatus = label(content)
        capabilities = label(diagnostics)
        connect = button(content, R.string.shizuku_connect) { model.connect() }
        deviceSummary = label(content)
        deviceCheck = button(content, R.string.device_capabilities) {
            startActivity(Intent(this, DeviceCapabilitiesActivity::class.java))
        }.apply { setIconResource(R.drawable.ic_nav_status) }
        refresh = button(diagnostics, R.string.shizuku_refresh) { model.refresh() }
        label(content).setText(R.string.shizuku_nfc_blocked)
        selectedCard = label(content)
        provisionStatus = label(content)
        provisionSource = label(content)
        preparedIdentity = label(content)
        resumePlan = button(content, R.string.shizuku_card_resume) {
            confirm(R.string.shizuku_card_resume, R.string.shizuku_card_resume_confirm) { model.resumePreviousPlan() }
        }
        prepareCard = button(content, R.string.shizuku_card_prepare) {
            confirm(R.string.shizuku_card_prepare, R.string.shizuku_card_prepare_confirm) { model.prepareCurrentCard() }
        }.apply { setIconResource(R.drawable.ic_read_nfc) }
        initializeCard = button(content, R.string.shizuku_card_initialize) {
            confirm(R.string.shizuku_card_initialize, R.string.shizuku_card_initialize_confirm) { model.initializeBaseline() }
        }
        applyCard = button(content, R.string.shizuku_card_apply) {
            confirm(R.string.shizuku_card_apply, R.string.shizuku_card_apply_confirm) { model.applyCurrentPlan() }
        }
        reloadNfc = button(content, R.string.shizuku_card_reload) {
            confirm(R.string.shizuku_card_reload, R.string.shizuku_card_reload_confirm) { model.reloadNfc() }
        }.apply { setIconResource(android.R.drawable.ic_popup_sync) }
        activateCard = button(content, R.string.shizuku_card_activate) { activatePreparedCard() }
        activationStatus = label(content)
        rollbackCard = button(content, R.string.shizuku_card_rollback) {
            confirm(R.string.shizuku_card_rollback, R.string.shizuku_card_rollback_confirm) { model.rollbackNfc() }
        }
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
        if (CardStore(this).advancedMode()) {
            button(content, R.string.provision_diagnostics) {
                diagnostics.isVisible = !diagnostics.isVisible
            }.apply { setIconResource(R.drawable.ic_nav_status) }
        }
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
        val matches = profile != null && profile.idm == screen.provisionIdm
        selectedCard.text = profile?.let { if (advanced) getString(R.string.shizuku_card_selected, it.label,
            it.idm) else getString(R.string.current_card_summary, it.label) }
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
        if (screen.provisionPending && !screen.provision.busy) provisionStatus.append("\n" + getString(R.string.shizuku_pending))
        if (screen.connection == Connection.READY && !screen.provisionCandidate &&
            screen.provision.state == NfcProvisionWorkflow.State.IDLE) {
            provisionStatus.setText(CapabilityText.summary(screen))
        }
        CapabilityText.failure(screen.provision.detail)?.let { provisionStatus.append("\n" + getString(it)) }
        if (screen.savedPlanRequiresResume && !screen.planSaved && !screen.provisionPending && !screen.provision.busy)
            provisionStatus.setText(R.string.shizuku_card_resume_required)
        provisionSource.setText(if (screen.provisionSource == NfcProvisionPlan.BaselineSource.RUNTIME)
            R.string.shizuku_card_source_runtime else R.string.shizuku_card_source_file)
        provisionSource.isVisible = screen.planSaved || screen.canActivate
        preparedIdentity.text = if (screen.provisionIdm.isEmpty()) ""
            else getString(R.string.shizuku_card_plan_identity, screen.provisionIdm)
        preparedIdentity.visibility = if (advanced && screen.provisionIdm.isNotEmpty()) View.VISIBLE else View.GONE
        activationStatus.text = if (profile != null && screen.provisionIdm.isNotEmpty() && !matches)
            getString(R.string.shizuku_card_selection_changed) else ""
        resumePlan.visibility = if (screen.savedPlanAvailable && !screen.planSaved) View.VISIBLE else View.GONE
        resumePlan.isEnabled = screen.canResumePlan
        prepareCard.isEnabled = screen.canPrepare && profile != null
        prepareCard.visibility = if (advanced || !matches || screen.provision.state in setOf(
            NfcProvisionWorkflow.State.IDLE, NfcProvisionWorkflow.State.FAILED, NfcProvisionWorkflow.State.CONFLICT
        )) View.VISIBLE else View.GONE
        initializeCard.visibility = if (screen.provision.state == NfcProvisionWorkflow.State.NEEDS_BASELINE) View.VISIBLE else View.GONE
        initializeCard.isEnabled = screen.canPrepare
        applyCard.isEnabled = screen.canApply && matches
        applyCard.visibility = if (advanced || screen.provision.state in setOf(NfcProvisionWorkflow.State.PREPARED,
            NfcProvisionWorkflow.State.UNCHANGED)) View.VISIBLE else View.GONE
        reloadNfc.isEnabled = screen.canReload
        reloadNfc.visibility = if (advanced || screen.provision.state in setOf(NfcProvisionWorkflow.State.VERIFIED,
            NfcProvisionWorkflow.State.ALREADY_PRESENT, NfcProvisionWorkflow.State.ROLLED_BACK,
            NfcProvisionWorkflow.State.AWAITING_RELOAD)) View.VISIBLE else View.GONE
        activateCard.isEnabled = screen.canActivate && matches && !screen.busy && !screen.provisionPending
        activateCard.visibility = if (advanced || screen.canActivate) View.VISIBLE else View.GONE
        rollbackCard.isEnabled = screen.canRollback
        rollbackCard.visibility = if (advanced || screen.canRollback) View.VISIBLE else View.GONE
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
        launchingEmulation = false
        model.screen.value?.let(::render)
        model.refresh()
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

    private fun button(parent: LinearLayout, title: Int, action: () -> Unit) = MaterialButton(
        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        setText(title)
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}
