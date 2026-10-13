package io.github.umislat.aimesimulator.nfc

import android.content.Intent
import android.nfc.cardemulation.HostNfcFService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.umislat.aimesimulator.BuildConfig
import io.github.umislat.aimesimulator.data.CardBackupCodec
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.data.HexCodec

class AimeHostService : HostNfcFService() {
    private val store by lazy { CardStore(this) }
    private var imageCache: CachedImage? = null
    private val trafficHandler = Handler(Looper.getMainLooper())

    override fun processNfcFPacket(commandPacket: ByteArray, extras: Bundle?): ByteArray? {
        val request = FelicaCodec.decodeRequest(commandPacket) ?: return null
        val profile = store.selectedProfile() ?: return observed(null)
        if (HexCodec.encode(request.nfcid2) != profile.idm) return observed(null)
        val image = imageFor(profile)

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
        return observed(response)
    }

    private fun observed(response: ByteArray?): ByteArray? {
        if (store.persistedRouteOnlyMode()) {
            val elapsed = SystemClock.elapsedRealtime()
            val responded = response != null
            // Notify after returning the response; never include identity or block data.
            trafficHandler.post {
                runCatching {
                    sendBroadcast(Intent(NfcSessionEvent.ACTION).setPackage(packageName)
                        .putExtra(NfcSessionEvent.TIME, elapsed)
                        .putExtra(NfcSessionEvent.RESPONDED, responded))
                }
            }
        }
        return response
    }

    @Synchronized
    private fun imageFor(profile: CardProfile): CardImage {
        imageCache?.takeIf { it.profile == profile }?.let { return it.image }
        return CardImage(profile, HceSession.SYSTEM_CODE, CardBackupCodec.PMM).also { image ->
            imageCache = CachedImage(profile, image)
        }
    }

    override fun onDeactivated(reason: Int) {
        if (BuildConfig.DEBUG) Log.d(TAG, "deactivated reason=$reason")
    }

    companion object {
        private const val TAG = "AimeHostService"
    }

    private data class CachedImage(val profile: CardProfile, val image: CardImage)
}
