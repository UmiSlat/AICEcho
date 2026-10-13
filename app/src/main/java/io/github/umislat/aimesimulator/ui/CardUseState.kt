package io.github.umislat.aimesimulator.ui

import io.github.umislat.aimesimulator.nfc.NfcCardRegistration
import io.github.umislat.aimesimulator.R

internal enum class CardUseState(val action: Int, val status: Int) {
    NO_CARD(R.string.add_card_action, R.string.select_or_add_card),
    SETUP(R.string.card_configure_and_use, R.string.card_not_configured),
    CONTINUE(R.string.card_continue_setup, R.string.card_setup_incomplete),
    READY(R.string.emulation_start, R.string.card_configured),
    NFC_OFF(R.string.card_enable_nfc, R.string.nfc_disabled),
    UNSUPPORTED(R.string.device_capabilities, R.string.hce_unsupported),
    UNKNOWN(R.string.card_check_and_use, R.string.card_configuration_unknown);

    companion object {
        fun resolve(idm: String?, registration: NfcCardRegistration, unfinished: Boolean): CardUseState = when {
            unfinished -> CONTINUE
            idm == null -> NO_CARD
            registration.availability == NfcCardRegistration.Availability.NFC_OFF -> NFC_OFF
            registration.availability == NfcCardRegistration.Availability.UNSUPPORTED -> UNSUPPORTED
            registration.availability == NfcCardRegistration.Availability.UNKNOWN -> UNKNOWN
            registration.matches(idm) -> READY
            else -> SETUP
        }
    }
}
