package io.github.umislat.aimesimulator.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexCodecTest {
    @Test fun normalizesWhitespaceAndCase() {
        assertEquals("02FE112233445566", HexCodec.normalize("02fe 1122 3344 5566", 8))
    }

    @Test fun rejectsWrongLengthAndNonHex() {
        assertNull(HexCodec.normalize("02FE", 8))
        assertNull(HexCodec.decode("00XY"))
    }

    @Test fun roundTripsBytes() {
        val bytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("007F80FF", HexCodec.encode(bytes))
        assertArrayEquals(bytes, HexCodec.decode("007F80FF"))
    }
}
