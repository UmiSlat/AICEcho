package io.github.umislat.aimesimulator.nfc

import android.nfc.cardemulation.HostNfcFService
import android.os.Bundle
import android.util.Log
import io.github.umislat.aimesimulator.BuildConfig
import io.github.umislat.aimesimulator.data.CardBackupCodec
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.data.HexCodec

class AimeHostService : HostNfcFService() {
    private val store by lazy { CardStore(this) }
    private var imageCache: CachedImage? = null

    override fun processNfcFPacket(commandPacket: ByteArray, extras: Bundle?): ByteArray? {
        val request = FelicaCodec.decodeRequest(commandPacket) ?: return null
        val profile = store.selectedProfile() ?: return null
        val rfIdm = profile.routedIdm(store.idmRouteMode())
        if (HexCodec.encode(request.nfcid2) != rfIdm) return null
        val image = imageFor(profile, rfIdm)

        val response = when (request.command) {
            FelicaCodec.READ_COMMAND -> {
                val validServices = request.blocks.all { block ->
                    request.services.getOrNull(block.serviceIndex) == FelicaCodec.READ_ONLY_SERVICE
                }
                if (!validServices || request.blocks.size > FelicaCodec.MAX_READ_BLOCKS) {
                    FelicaCodec.readResponse(request.nfcid2, emptyList(), 0x01, 0xA2)
                } else {
                    val blocks = request.blocks.mapNotNull { image.read(it.blockNumber) }
                    if (blocks.size != request.blocks.size) {
                        FelicaCodec.readResponse(request.nfcid2, emptyList(), 0x01, 0xA2)
                    } else {
                        FelicaCodec.readResponse(request.nfcid2, blocks)
                    }
                }
            }
            FelicaCodec.WRITE_COMMAND -> FelicaCodec.writeResponse(request.nfcid2, 0x01, 0xA8)
            else -> null
        }

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "command=%02X request=%d response=%d".format(
                    request.command,
                    commandPacket.size,
                    response?.size ?: 0
                )
            )
        }
        return response
    }

    @Synchronized
    private fun imageFor(profile: CardProfile, rfIdm: String): CardImage {
        imageCache?.takeIf { it.profile == profile && it.rfIdm == rfIdm }?.let { return it.image }
        return CardImage(profile, rfIdm, HceSession.SYSTEM_CODE, CardBackupCodec.PMM).also { image ->
            imageCache = CachedImage(profile, rfIdm, image)
        }
    }

    override fun onDeactivated(reason: Int) {
        if (BuildConfig.DEBUG) Log.d(TAG, "deactivated reason=$reason")
    }

    companion object {
        private const val TAG = "AimeHostService"
    }

    private data class CachedImage(val profile: CardProfile, val rfIdm: String, val image: CardImage)
}
