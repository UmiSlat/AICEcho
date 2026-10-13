package io.github.umislat.aimesimulator.nfc

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.NfcFCardEmulation

/** A live, read-only observation. It is never saved as proof of successful emulation. */
internal data class NfcCardRegistration(
    val availability: Availability,
    val systemCode: String? = null,
    val nfcid2: String? = null
) {
    enum class Availability { AVAILABLE, NFC_OFF, UNSUPPORTED, UNKNOWN }

    fun matches(idm: String) = availability == Availability.AVAILABLE &&
        systemCode.equals(HceSession.SYSTEM_CODE, true) && nfcid2.equals(idm, true)

    companion object {
        fun read(context: Context): NfcCardRegistration {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF))
                return NfcCardRegistration(Availability.UNSUPPORTED)
            return runCatching {
                val adapter = NfcAdapter.getDefaultAdapter(context)
                    ?: return@runCatching NfcCardRegistration(Availability.UNKNOWN)
                if (!adapter.isEnabled) return@runCatching NfcCardRegistration(Availability.NFC_OFF)
                val manager = NfcFCardEmulation.getInstance(adapter)
                val component = ComponentName(context, AimeHostService::class.java)
                val code = manager.getSystemCodeForService(component)
                val idm = manager.getNfcid2ForService(component)
                NfcCardRegistration(if (code == null || idm == null) Availability.UNKNOWN else Availability.AVAILABLE,
                    code, idm)
            }.getOrDefault(NfcCardRegistration(Availability.UNKNOWN))
        }
    }
}
