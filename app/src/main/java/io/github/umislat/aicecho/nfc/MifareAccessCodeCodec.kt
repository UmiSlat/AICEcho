package io.github.umislat.aicecho.nfc

import io.github.umislat.aicecho.data.HexCodec

internal object MifareAccessCodeCodec {
    private const val BLOCK_SIZE = 16
    private const val ACCESS_CODE_OFFSET = 6
    private const val ACCESS_CODE_BYTES = 10

    fun decodeBlock2(block: ByteArray): String? {
        if (block.size != BLOCK_SIZE) return null
        return HexCodec.encode(
            block.copyOfRange(ACCESS_CODE_OFFSET, ACCESS_CODE_OFFSET + ACCESS_CODE_BYTES)
        ).takeIf { value -> value.length == 20 && value.all(Char::isDigit) }
    }
}
