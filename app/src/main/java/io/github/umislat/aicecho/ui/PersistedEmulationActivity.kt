package io.github.umislat.aicecho.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.umislat.aicecho.R
import io.github.umislat.aicecho.data.HexCodec
import io.github.umislat.aicecho.nfc.HceSession
import io.github.umislat.aicecho.nfc.NfcSessionEvent
import io.github.umislat.aicecho.nfc.PersistedHceRouteWorkflow

class PersistedEmulationActivity : AppCompatActivity() {
    private var session: HceSession? = null
    private var nfcid2: String? = null
    private lateinit var status: TextView
    private lateinit var configure: MaterialButton
    private lateinit var traffic: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var enabled = false
    private var enabledAt = -1L
    private var recoveryAttempts = 0
    private var requests = 0
    private var responses = 0
    private val recover = Runnable { activate() }
    private val inspect = Runnable { checkSession() }
    private val nfcChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!resumed || intent?.action != NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) return
            when (intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, -1)) {
                NfcAdapter.STATE_OFF, NfcAdapter.STATE_TURNING_OFF -> {
                    clearSession()
                    status.setText(R.string.nfc_disabled)
                    configure.visibility = View.GONE
                }
                NfcAdapter.STATE_TURNING_ON -> {
                    clearSession()
                    status.setText(R.string.persisted_emulation_recovering)
                }
                NfcAdapter.STATE_ON -> reconnect()
            }
        }
    }
    private val readerTraffic = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!resumed || !enabled || intent?.action != NfcSessionEvent.ACTION) return
            if (intent.getLongExtra(NfcSessionEvent.TIME, -1) < enabledAt) return
            requests++
            val responded = intent.getBooleanExtra(NfcSessionEvent.RESPONDED, false)
            if (responded) responses++
            status.setText(if (responded) R.string.persisted_emulation_reader_seen
                else R.string.persisted_emulation_reader_unanswered)
            renderTraffic()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveActivities++
        nfcid2 = intent.getStringExtra(EXTRA_NFCID2)?.let { HexCodec.normalize(it, 8) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.persisted_emulation_title)
            setTitleTextAppearance(this@PersistedEmulationActivity, R.style.TextAppearance_AICEcho_Toolbar)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        })
        val content = verticalLayout(this)
        nfcid2?.let { id ->
            content.addView(bodyText(R.string.shizuku_card_selected).apply {
                text = if (intent.getBooleanExtra(EXTRA_DETAILS, false)) getString(R.string.shizuku_card_selected,
                    intent.getStringExtra(EXTRA_LABEL).orEmpty(), id)
                    else getString(R.string.current_card_summary, intent.getStringExtra(EXTRA_LABEL).orEmpty())
                setPadding(0, dp(16), 0, dp(16))
            })
        }
        status = bodyText(R.string.persisted_emulation_invalid)
        content.addView(status)
        traffic = bodyText(R.string.persisted_emulation_traffic).apply {
            visibility = if (intent.getBooleanExtra(EXTRA_DETAILS, false)) View.VISIBLE else View.GONE
        }
        content.addView(traffic)
        renderTraffic()
        configure = MaterialButton(this).apply {
            setText(R.string.emulation_setup)
            setIconResource(R.drawable.ic_nav_status)
            visibility = View.GONE
            setOnClickListener {
                startActivity(Intent(this@PersistedEmulationActivity, ShizukuProvisioningActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }
        content.addView(configure, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.addView(MaterialButton(this).apply {
            setText(R.string.persisted_emulation_reconnect)
            setIconResource(android.R.drawable.ic_popup_sync)
            isEnabled = nfcid2 != null
            setOnClickListener { reconnect() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.addView(MaterialButton(this).apply {
            setText(R.string.persisted_emulation_stop)
            setIconResource(android.R.drawable.ic_media_pause)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setInsetAwareContentView(root)
        ContextCompat.registerReceiver(this, nfcChanged, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, readerTraffic, IntentFilter(NfcSessionEvent.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        recoveryAttempts = 0
        activate()
    }

    private fun reconnect() {
        if (!resumed || isFinishing) return
        clearSession()
        recoveryAttempts = 0
        session = HceSession(this)
        activate()
    }

    private fun activate() {
        if (!resumed || isFinishing) return
        val expected = nfcid2 ?: return
        clearSession()
        val activeSession = session ?: HceSession(this).also { session = it }
        val attemptStarted = SystemClock.elapsedRealtime()
        val outcome = activeSession.activatePersistedRoute(this, expected)
        render(outcome)
        if (outcome.succeeded) {
            enabled = true
            enabledAt = attemptStarted
            handler.postDelayed(inspect, INSPECT_DELAY_MS)
        } else if (outcome.outcome == PersistedHceRouteWorkflow.Outcome.SERVICE_RESTARTING) {
            scheduleRecovery()
        }
    }

    private fun checkSession() {
        if (!resumed || !enabled || isFinishing) return
        val expected = nfcid2 ?: return
        val outcome = session?.inspectPersistedRoute(this, expected) ?: return
        if (outcome.succeeded) {
            handler.postDelayed(inspect, INSPECT_DELAY_MS)
        } else {
            clearSession()
            session?.deactivate(this)
            render(outcome)
            if (outcome.outcome == PersistedHceRouteWorkflow.Outcome.SERVICE_RESTARTING) {
                recoveryAttempts = 0
                scheduleRecovery()
            }
        }
    }

    private fun scheduleRecovery() {
        if (recoveryAttempts < MAX_RECOVERY_ATTEMPTS) {
            recoveryAttempts++
            status.setText(R.string.persisted_emulation_recovering)
            handler.postDelayed(recover, RECOVERY_DELAY_MS)
        } else {
            status.setText(R.string.persisted_emulation_recovery_failed)
        }
    }

    private fun render(outcome: PersistedHceRouteWorkflow.Result) {
        status.text = when (outcome.outcome) {
            PersistedHceRouteWorkflow.Outcome.READY -> getString(R.string.shizuku_card_enabled)
            PersistedHceRouteWorkflow.Outcome.SYSTEM_CODE_MISMATCH,
            PersistedHceRouteWorkflow.Outcome.NFCID2_MISMATCH -> getString(R.string.emulation_setup_required)
            PersistedHceRouteWorkflow.Outcome.NFC_DISABLED -> getString(R.string.nfc_disabled)
            PersistedHceRouteWorkflow.Outcome.UNSUPPORTED -> getString(R.string.hce_unsupported)
            else -> if (intent.getBooleanExtra(EXTRA_DETAILS, false)) {
                getString(R.string.shizuku_card_enable_failed, outcome.outcome.name)
            } else {
                getString(R.string.simple_emulation_failed)
            }
        }
        configure.visibility = if (outcome.outcome in setOf(PersistedHceRouteWorkflow.Outcome.SYSTEM_CODE_MISMATCH,
            PersistedHceRouteWorkflow.Outcome.NFCID2_MISMATCH)) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        resumed = false
        clearSession()
        status.setText(R.string.emulation_stopped)
        session?.deactivate(this)
        super.onPause()
    }

    private fun clearSession() {
        handler.removeCallbacks(recover)
        handler.removeCallbacks(inspect)
        enabled = false
        enabledAt = -1
        requests = 0
        responses = 0
        renderTraffic()
    }

    private fun renderTraffic() {
        traffic.text = getString(R.string.persisted_emulation_traffic, requests, responses)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(nfcChanged)
        unregisterReceiver(readerTraffic)
        super.onDestroy()
        liveActivities--
        // Only recycle this foreground session process. The main process owns card data
        // and the live Shizuku plan; its NFC-F framework cache must not be reused here.
        if (liveActivities == 0 && isFinishing && !isChangingConfigurations &&
            Application.getProcessName() == packageName + PROCESS_SUFFIX
        ) {
            Process.killProcess(Process.myPid())
        }
    }

    companion object {
        internal const val INSPECT_DELAY_MS = 3_000L
        internal const val RECOVERY_DELAY_MS = 1_000L
        internal const val MAX_RECOVERY_ATTEMPTS = 30
        internal const val PROCESS_SUFFIX = ":nfc_session"
        internal const val EXTRA_NFCID2 = "nfcid2"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_DETAILS = "details"
        private var liveActivities = 0

        internal fun intent(context: Context, label: String, nfcid2: String, details: Boolean = false): Intent =
            Intent(context, PersistedEmulationActivity::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_NFCID2, nfcid2)
                .apply { if (details) putExtra(EXTRA_DETAILS, true) }
    }
}
