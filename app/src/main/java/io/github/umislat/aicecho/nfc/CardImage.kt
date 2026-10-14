package io.github.umislat.aicecho.nfc

import io.github.umislat.aicecho.data.CardProfile
import io.github.umislat.aicecho.data.HexCodec

internal class CardImage(
    profile: CardProfile,
    systemCode: String = "88B4",
    pmm: String = "00F1000000014300"
) {
    private val blocks = HashMap<Int, ByteArray>()

    init {
        val deviceId = requireNotNull(HexCodec.decode(profile.idm, 8))
        val performance = requireNotNull(HexCodec.decode(pmm, 8))
        val system = requireNotNull(HexCodec.decode(systemCode, 2))

        for (number in 0x00..0x0D) blocks[number] = ByteArray(16)
        blocks[0x0E] = ByteArray(16) { 0xFF.toByte() }
        blocks[0x00] = profile.spad0?.let { requireNotNull(HexCodec.decode(it, 16)) }
            ?: ByteArray(16)
        // Preserve captured bytes, including issuer data beyond the IDm.
        blocks[0x82] = profile.idBlock?.let { requireNotNull(HexCodec.decode(it, 16)) }
            ?: ByteArray(16).also { requireNotNull(HexCodec.decode(profile.idm, 8)).copyInto(it) }
        blocks[0x83] = deviceId + performance
        blocks[0x84] = ByteArray(16)
        blocks[0x85] = ByteArray(16).also { system.copyInto(it) }
        blocks[0x88] = readOnlyConfiguration()
    }

    fun read(number: Int): ByteArray? = blocks[number]?.copyOf()

    private fun readOnlyConfiguration(): ByteArray = ByteArray(16).also {
        // No writable blocks, NDEF, or authentication restrictions; RF parameter per Lite-S.
        it[4] = 0x07
    }
}
