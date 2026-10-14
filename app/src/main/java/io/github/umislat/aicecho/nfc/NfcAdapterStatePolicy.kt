package io.github.umislat.aicecho.nfc

import android.nfc.NfcAdapter

internal object NfcAdapterStatePolicy {
    enum class Action {
        IGNORE, MARK_DISABLED, MARK_ENABLING, ACTIVATE
    }

    fun actionFor(adapterState: Int, adapterEnabled: Boolean? = null): Action = when {
        adapterState == NfcAdapter.STATE_ON && adapterEnabled == false -> Action.MARK_DISABLED
        adapterState == NfcAdapter.STATE_OFF && adapterEnabled == true -> Action.ACTIVATE
        else -> actionForReportedState(adapterState)
    }

    private fun actionForReportedState(adapterState: Int): Action = when (adapterState) {
        NfcAdapter.STATE_OFF,
        NfcAdapter.STATE_TURNING_OFF -> Action.MARK_DISABLED
        NfcAdapter.STATE_TURNING_ON -> Action.MARK_ENABLING
        NfcAdapter.STATE_ON -> Action.ACTIVATE
        else -> Action.IGNORE
    }
}
