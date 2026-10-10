package io.github.umislat.aimesimulator.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcF
import io.github.umislat.aimesimulator.data.HexCodec
import java.io.IOException

internal object PhysicalCardReader {
    data class Capture(
        val idm: String,
        val systemCode: String,
        val spad0: String? = null,
        val idBlock: String? = null
    )

    sealed interface ReadResult {
        data class Felica(val capture: Capture) : ReadResult
        data class MifareAime(val uid: String, val accessCode: String) : ReadResult
        object FelicaReadFailed : ReadResult
        object MifareAuthenticationFailed : ReadResult
        object MifareInvalidAccessCode : ReadResult
        object MifareReadFailed : ReadResult
        object UnsupportedCardTechnology : ReadResult
    }

    fun readCard(tag: Tag): ReadResult {
        NfcF.get(tag)?.let {
            return capture(tag)?.let { capture -> ReadResult.Felica(capture) }
                ?: ReadResult.FelicaReadFailed
        }
        MifareClassic.get(tag)?.let { return recognizeMifare(tag, it) }
        return ReadResult.UnsupportedCardTechnology
    }

    fun capture(tag: Tag): Capture? {
        val technology = NfcF.get(tag) ?: return null
        val idmBytes = tag.id ?: return null
        val systemCode = technology.systemCode?.copyOf() ?: ByteArray(0)
        var result = Capture(HexCodec.encode(idmBytes), HexCodec.encode(systemCode))
        try {
            technology.connect()
            result = captureFelica(idmBytes, systemCode) { blocks ->
                read(technology, idmBytes, blocks)
            }
        } catch (_: IOException) {
            // IDm-only capture remains useful.
        } finally {
            runCatching { technology.close() }
        }

        return result
    }

    private fun read(technology: NfcF, idm: ByteArray, blocks: IntArray): List<ByteArray>? {
        val result = FelicaCodec.decodeReadResponse(
            technology.transceive(FelicaCodec.readRequest(idm, blocks))
        ) ?: return null
        if (!result.succeeded || !result.nfcid2.contentEquals(idm) || result.blocks.size != blocks.size) {
            return null
        }
        return result.blocks
    }

    internal fun captureFelica(
        idm: ByteArray,
        systemCode: ByteArray,
        readBlocks: (IntArray) -> List<ByteArray>?
    ): Capture {
        val found = HashMap<Int, ByteArray>()
        val requested = intArrayOf(0x00, 0x82)
        val combined = runCatching { readBlocks(requested) }.getOrNull()
        if (combined != null && combined.size == requested.size) {
            requested.forEachIndexed { index, block -> found[block] = combined[index] }
        } else {
            requested.forEach { block ->
                runCatching { readBlocks(intArrayOf(block))?.singleOrNull() }
                    .getOrNull()?.let { found[block] = it }
            }
        }
        return Capture(
            idm = HexCodec.encode(idm),
            systemCode = HexCodec.encode(systemCode),
            spad0 = found[0x00]?.let(HexCodec::encode),
            idBlock = found[0x82]?.let(HexCodec::encode)
        )
    }

    private fun recognizeMifare(tag: Tag, technology: MifareClassic): ReadResult {
        return try {
            technology.connect()
            val sector = technology.blockToSector(AIME_DATA_BLOCK)
            val authenticated = technology.authenticateSectorWithKeyB(sector, AIME_KEY)
            if (!authenticated) {
                ReadResult.MifareAuthenticationFailed
            } else {
                val accessCode = MifareAccessCodeCodec.decodeBlock2(
                    technology.readBlock(AIME_DATA_BLOCK)
                )
                if (accessCode == null) {
                    ReadResult.MifareInvalidAccessCode
                } else {
                    ReadResult.MifareAime(
                        uid = HexCodec.encode(tag.id ?: ByteArray(0)),
                        accessCode = accessCode
                    )
                }
            }
        } catch (_: IOException) {
            ReadResult.MifareReadFailed
        } finally {
            runCatching { technology.close() }
        }
    }

    private const val AIME_DATA_BLOCK = 2
    private val AIME_KEY = byteArrayOf(0x57, 0x43, 0x43, 0x46, 0x76, 0x32)
}
