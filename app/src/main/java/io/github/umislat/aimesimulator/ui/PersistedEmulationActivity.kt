package io.github.umislat.aimesimulator.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.umislat.aimesimulator.R
import io.github.umislat.aimesimulator.data.HexCodec
import io.github.umislat.aimesimulator.nfc.HceSession
import io.github.umislat.aimesimulator.nfc.PersistedHceRouteWorkflow

class PersistedEmulationActivity : AppCompatActivity() {
    private var session: HceSession? = null
    private var nfcid2: String? = null
    private lateinit var status: TextView
    private lateinit var configure: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveActivities++
        nfcid2 = intent.getStringExtra(EXTRA_NFCID2)?.let { HexCodec.normalize(it, 8) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.persisted_emulation_title)
            setTitleTextAppearance(this@PersistedEmulationActivity, R.style.TextAppearance_AimeSimulator_Toolbar)
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
            setText(R.string.persisted_emulation_stop)
            setIconResource(android.R.drawable.ic_media_pause)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setInsetAwareContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        val expected = nfcid2 ?: return
        val activeSession = session ?: HceSession(this).also { session = it }
        val outcome = activeSession.activatePersistedRoute(this, expected)
        status.text = when (outcome.outcome) {
            PersistedHceRouteWorkflow.Outcome.READY -> getString(R.string.shizuku_card_enabled)
            PersistedHceRouteWorkflow.Outcome.SYSTEM_CODE_MISMATCH,
            PersistedHceRouteWorkflow.Outcome.NFCID2_MISMATCH -> getString(R.string.emulation_setup_required)
            PersistedHceRouteWorkflow.Outcome.NFC_DISABLED -> getString(R.string.nfc_disabled)
            PersistedHceRouteWorkflow.Outcome.UNSUPPORTED -> getString(R.string.hce_unsupported)
            else -> getString(R.string.shizuku_card_enable_failed, outcome.outcome.name)
        }
        configure.visibility = if (outcome.outcome in setOf(PersistedHceRouteWorkflow.Outcome.SYSTEM_CODE_MISMATCH,
            PersistedHceRouteWorkflow.Outcome.NFCID2_MISMATCH)) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        session?.deactivate(this)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
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
