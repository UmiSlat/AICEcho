package io.github.umislat.aicecho.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.github.umislat.aicecho.R
import io.github.umislat.aicecho.data.CardStore
import io.github.umislat.aicecho.nfc.PhysicalCardReader
import java.util.concurrent.atomic.AtomicBoolean

class CardReaderActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    private var adapter: NfcAdapter? = null
    private lateinit var progress: CircularProgressIndicator
    private lateinit var status: android.widget.TextView
    private lateinit var useResultButton: MaterialButton
    private val reading = AtomicBoolean(false)
    private var recognitionComplete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val titleText = R.string.read_physical_card
        title = getString(titleText)
        adapter = NfcAdapter.getDefaultAdapter(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(titleText)
            setTitleTextAppearance(this@CardReaderActivity, R.style.TextAppearance_AICEcho_Toolbar)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        })
        val content = verticalLayout(this).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            progress = CircularProgressIndicator(this@CardReaderActivity).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(56) }
            }
            addView(progress)
            status = bodyText(R.string.hold_card_near_phone).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            }
            addView(status)
            useResultButton = MaterialButton(this@CardReaderActivity).apply {
                setText(R.string.use_in_card_profile)
                visibility = View.GONE
            }
            addView(useResultButton, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(24)
            })
        }
        root.addView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setInsetAwareContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (recognitionComplete) return
        val nfc = adapter
        if (nfc == null) {
            status.setText(R.string.nfc_unavailable)
            return
        }
        if (!nfc.isEnabled) {
            status.setText(R.string.nfc_disabled)
            return
        }
        nfc.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        )
    }

    override fun onPause() {
        adapter?.disableReaderMode(this)
        reading.set(false)
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag) {
        if (!reading.compareAndSet(false, true)) return
        when (val recognition = PhysicalCardReader.readCard(tag)) {
            is PhysicalCardReader.ReadResult.Felica -> showFelicaCapture(recognition.capture)
            is PhysicalCardReader.ReadResult.MifareAime -> showMifareRecognition(
                recognition.uid,
                recognition.accessCode
            )
            PhysicalCardReader.ReadResult.FelicaReadFailed -> showRetry(
                R.string.card_read_failed
            )
            PhysicalCardReader.ReadResult.MifareAuthenticationFailed -> showRetry(
                R.string.mifare_authentication_failed
            )
            PhysicalCardReader.ReadResult.MifareInvalidAccessCode -> showRetry(
                R.string.mifare_access_code_invalid
            )
            PhysicalCardReader.ReadResult.MifareReadFailed -> showRetry(
                R.string.mifare_read_failed
            )
            PhysicalCardReader.ReadResult.UnsupportedCardTechnology -> showRetry(
                R.string.access_code_card_unsupported
            )
        }
    }

    private fun showMifareRecognition(uid: String, accessCode: String) {
        val formattedUid = uid.chunked(2).joinToString(" ")
        val formattedAccessCode = accessCode.chunked(4).joinToString(" ")
        runOnUiThread {
            recognitionComplete = true
            adapter?.disableReaderMode(this)
            progress.visibility = View.GONE
            status.text = if (CardStore(this).advancedMode()) getString(
                R.string.mifare_access_code_recognized,
                formattedUid,
                formattedAccessCode
            ) else getString(R.string.mifare_card_read_simple, formattedAccessCode)
            showUseResultButton(Intent().apply {
                putExtra(EXTRA_ACCESS_CODE, accessCode)
            })
        }
    }

    private fun showFelicaCapture(capture: PhysicalCardReader.Capture) {
        val formattedIdm = capture.idm.chunked(2).joinToString(" ")
        val formattedSystemCode = capture.systemCode.chunked(2).joinToString(" ").ifBlank { "--" }
        runOnUiThread {
            recognitionComplete = true
            adapter?.disableReaderMode(this)
            progress.visibility = View.GONE
            status.text = if (CardStore(this).advancedMode()) getString(
                R.string.felica_card_read,
                formattedIdm,
                formattedSystemCode,
                capture.spad0?.chunked(4)?.joinToString(" ")
                    ?: getString(R.string.card_details_not_saved),
                capture.idBlock?.chunked(4)?.joinToString(" ")
                    ?: getString(R.string.card_details_not_saved)
            ) else getString(R.string.felica_card_read_simple)
            showUseResultButton(Intent().apply {
                putExtra(EXTRA_IDM, capture.idm)
                putExtra(EXTRA_SYSTEM_CODE, capture.systemCode)
                putExtra(EXTRA_SPAD0, capture.spad0)
                putExtra(EXTRA_ID_BLOCK, capture.idBlock)
            })
        }
    }

    private fun showUseResultButton(result: Intent) {
        useResultButton.visibility = View.VISIBLE
        useResultButton.setOnClickListener {
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    private fun showRetry(message: Int) {
        runOnUiThread {
            status.setText(message)
            reading.set(false)
        }
    }

    companion object {
        const val EXTRA_IDM = "capture.idm"
        const val EXTRA_SYSTEM_CODE = "capture.systemCode"
        const val EXTRA_SPAD0 = "capture.spad0"
        const val EXTRA_ID_BLOCK = "capture.idBlock"
        const val EXTRA_ACCESS_CODE = "capture.access_code"
    }
}
