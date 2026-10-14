package io.github.umislat.aicecho.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MifareAccessCodeCodecTest {
    @Test fun decodesPackedDecimalDigitsFromBlock2() {
        val block = ByteArray(16)
        "12345678901234567890".chunked(2).forEachIndexed { index, pair ->
            block[index + 6] = pair.toInt(16).toByte()
        }

        assertEquals(
            "12345678901234567890",
            MifareAccessCodeCodec.decodeBlock2(block)
        )
    }

    @Test fun rejectsNonDecimalNibbles() {
        val block = ByteArray(16)
        block[6] = 0xAB.toByte()

        assertNull(MifareAccessCodeCodec.decodeBlock2(block))
    }

    @Test fun rejectsUnexpectedBlockLength() {
        assertNull(MifareAccessCodeCodec.decodeBlock2(ByteArray(15)))
    }
}
