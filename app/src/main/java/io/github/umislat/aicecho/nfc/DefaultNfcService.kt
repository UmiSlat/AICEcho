package io.github.umislat.aicecho.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Minimal HCE-A service used only so Android can offer AICEcho as the default NFC app.
 * Aime card emulation remains implemented by [AicHostService] through HCE-F.
 */
class DefaultNfcService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
        FUNCTION_NOT_SUPPORTED

    override fun onDeactivated(reason: Int) = Unit

    private companion object {
        val FUNCTION_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    }
}
