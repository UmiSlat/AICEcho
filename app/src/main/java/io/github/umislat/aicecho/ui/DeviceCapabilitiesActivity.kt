package io.github.umislat.aicecho.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.umislat.aicecho.R
import io.github.umislat.aicecho.data.CardStore
import io.github.umislat.aicecho.nfc.NfcCardRegistration
import io.github.umislat.aicecho.provisioning.AospBackupCapabilities
import io.github.umislat.aicecho.provisioning.NfcProvisionWorkflow
import io.github.umislat.aicecho.provisioning.NfcRuntimeInventory
import io.github.umislat.aicecho.provisioning.ShizukuProbeModel

class DeviceCapabilitiesActivity : AppCompatActivity() {
    private lateinit var model: ShizukuProbeModel
    private lateinit var content: LinearLayout
    private lateinit var diagnostics: LinearLayout
    private lateinit var device: TextView
    private lateinit var hardware: TextView
    private lateinit var hcef: TextView
    private lateinit var registration: TextView
    private lateinit var connection: TextView
    private lateinit var service: TextView
    private lateinit var target: TextView
    private lateinit var snapshot: TextView
    private lateinit var runtime: TextView
    private lateinit var restore: TextView
    private lateinit var conclusion: TextView
    private lateinit var connect: MaterialButton
    private lateinit var refresh: MaterialButton
    private lateinit var verify: MaterialButton
    private lateinit var inspectRuntime: MaterialButton
    private lateinit var setup: MaterialButton
    private val nfcChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { renderHardware() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[ShizukuProbeModel::class.java]
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.device_capabilities)
            setTitleTextAppearance(this@DeviceCapabilitiesActivity, R.style.TextAppearance_AICEcho_Toolbar)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { leave() }
        })
        content = verticalLayout(this)
        device = row(R.string.capability_device)
        hardware = row(R.string.capability_nfc)
        hcef = row(R.string.capability_hcef)
        registration = row(R.string.capability_registration)
        connection = row(R.string.emulation_method_shizuku)
        connect = button(R.string.shizuku_connect, R.drawable.ic_nav_status) { ShizukuConnectionUi.act(this, model) }
        conclusion = row(R.string.capability_conclusion)
        refresh = button(R.string.capability_refresh, android.R.drawable.ic_popup_sync) {
            renderHardware()
            model.refresh()
        }
        setup = button(R.string.emulation_setup, R.drawable.ic_nav_status) {
            startActivity(Intent(this, ShizukuProvisioningActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        diagnostics = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diagnostics.addView(sectionTitle(R.string.provision_diagnostics))
        service = row(R.string.capability_service, diagnostics)
        target = row(R.string.capability_target, diagnostics)
        snapshot = row(R.string.capability_snapshot, diagnostics)
        runtime = row(R.string.capability_runtime, diagnostics)
        inspectRuntime = button(R.string.capability_runtime_read, R.drawable.ic_read_nfc, diagnostics) { model.inspectRuntime() }
        restore = row(R.string.capability_restore, diagnostics)
        verify = button(R.string.capability_verify, R.drawable.ic_read_nfc, diagnostics) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.capability_verify)
                .setMessage(R.string.shizuku_snapshot_confirm).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.capability_verify) { _, _ -> model.captureSnapshot() }.show()
        }
        content.addView(diagnostics)
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setInsetAwareContentView(root)
        model.screen.observe(this, ::render)
        ContextCompat.registerReceiver(this, nfcChanged, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    override fun onResume() {
        super.onResume()
        renderHardware()
        model.onForeground()
    }

    override fun onPause() {
        model.onBackground()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(nfcChanged)
        super.onDestroy()
    }

    private fun renderHardware() {
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(this) }.getOrNull()
        hardware.setText(when {
            !packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) || adapter == null -> R.string.capability_nfc_absent
            runCatching { adapter.isEnabled }.getOrDefault(false) -> R.string.capability_nfc_on
            else -> R.string.nfc_disabled
        })
        val supported = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF)
        hcef.setText(if (supported) R.string.capability_hcef_present else R.string.hce_unsupported)
        val store = CardStore(this)
        val selected = store.selectedProfile()
        registration.setText(when {
            applicationInfo.uid / 100000 != 0 -> R.string.capability_user_blocked
            selected == null -> R.string.shizuku_card_missing
            !supported || adapter == null -> R.string.hce_unsupported
            else -> NfcCardRegistration.read(this).let { observation ->
                when {
                    observation.matches(selected.idm) -> R.string.capability_registration_match
                    observation.availability == NfcCardRegistration.Availability.AVAILABLE -> R.string.emulation_setup_required
                    observation.availability == NfcCardRegistration.Availability.NFC_OFF -> R.string.nfc_disabled
                    else -> R.string.capability_registration_unknown
                }
            }
        })
    }

    private fun render(screen: ShizukuProbeModel.Screen) {
        val advanced = CardStore(this).advancedMode()
        diagnostics.isVisible = advanced
        device.text = if (advanced) getString(R.string.capability_device_value,
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
            else getString(R.string.capability_device_basic, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE)
        connection.setText(CapabilityText.connection(screen.connection))
        connect.setText(ShizukuConnectionUi.label(screen.connection))
        connect.visibility = if (screen.connection == ShizukuProbeModel.Connection.READY) View.GONE else View.VISIBLE
        connect.isEnabled = applicationInfo.uid / 100000 == 0 && screen.connection !in setOf(
            ShizukuProbeModel.Connection.CONNECTING, ShizukuProbeModel.Connection.PERMISSION)
        val report = screen.aosp
        service.setText(when (report?.service) {
            AospBackupCapabilities.Service.ACTIVE -> R.string.capability_service_active
            AospBackupCapabilities.Service.INACTIVE -> R.string.capability_service_inactive
            else -> R.string.capability_service_unknown
        })
        target.text = report?.let {
            getString(CapabilityText.target(it.target)) + if (it.target == AospBackupCapabilities.Target.READY)
                "\n" + getString(if (it.keyValueOnly) R.string.capability_key_value else R.string.capability_export_candidate) else ""
        } ?: getString(R.string.shizuku_unchecked)
        snapshot.setText(CapabilityText.snapshot(screen.snapshot))
        if (screen.snapshotPending && !screen.snapshot.busy) snapshot.append("\n" + getString(R.string.shizuku_snapshot_pending))
        val observation = screen.runtime
        runtime.text = when (observation.state) {
            NfcRuntimeInventory.State.UNCHECKED -> getString(R.string.capability_runtime_unchecked)
            NfcRuntimeInventory.State.FAILED -> getString(when (observation.failure) {
                NfcRuntimeInventory.Failure.SHELL -> R.string.shizuku_root
                NfcRuntimeInventory.Failure.USER -> R.string.capability_user_blocked
                NfcRuntimeInventory.Failure.HARDWARE -> R.string.hce_unsupported
                else -> R.string.capability_runtime_failed
            })
            NfcRuntimeInventory.State.READ -> buildString {
                append(getString(if (observation.source == NfcRuntimeInventory.Source.STRUCTURED)
                    R.string.capability_runtime_structured else R.string.capability_runtime_dump,
                    observation.records, observation.users))
                append('\n')
                append(getString(if (observation.dynamicFieldsKnown) R.string.capability_runtime_dynamic_known
                    else R.string.capability_runtime_effective_only))
                if (observation.previewRecords >= 0) {
                    append('\n'); append(getString(R.string.capability_runtime_preview, observation.previewRecords))
                }
                append('\n'); append(getString(R.string.capability_runtime_boundary))
            }
        }
        restore.setText(when {
            report == null -> R.string.shizuku_unchecked
            !report.canExport -> R.string.capability_restore_blocked
            screen.provision.state in setOf(NfcProvisionWorkflow.State.VERIFIED, NfcProvisionWorkflow.State.ROLLED_BACK) ||
                (screen.planSaved && screen.provision.state == NfcProvisionWorkflow.State.RELOADED) -> R.string.capability_restore_verified
            else -> R.string.capability_restore_unverified
        })
        conclusion.setText(if (applicationInfo.uid / 100000 != 0) R.string.capability_user_blocked
            else if (advanced) CapabilityText.summary(screen) else CapabilityText.readiness(screen))
        refresh.isEnabled = !screen.busy
        verify.isEnabled = screen.canCapture && packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF)
        inspectRuntime.isEnabled = screen.canInspectRuntime && packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF)
        setup.isEnabled = !screen.busy && CardStore(this).selectedProfile() != null
    }

    private fun leave() {
        if (model.screen.value?.busy == true) MaterialAlertDialogBuilder(this).setTitle(R.string.shizuku_busy_title)
            .setMessage(R.string.shizuku_busy_body).setPositiveButton(android.R.string.ok, null).show()
        else finish()
    }

    private fun row(title: Int, parent: LinearLayout = content): TextView {
        parent.addView(TextView(this).apply {
            setText(title); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        }, LinearLayout.LayoutParams(-1, -2))
        return TextView(this).apply { textSize = 14f; parent.addView(this, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun button(title: Int, icon: Int, parent: LinearLayout = content, action: () -> Unit) = MaterialButton(this, null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setText(title); setIconResource(icon); setOnClickListener { action() }
        maxLines = 2
        minHeight = dp(48)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}
