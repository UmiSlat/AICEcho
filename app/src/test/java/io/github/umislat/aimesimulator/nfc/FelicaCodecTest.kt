package io.github.umislat.aimesimulator.nfc

import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.HexCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FelicaCodecTest {
    private val idm = requireNotNull(HexCodec.decode("02FE123456789ABC"))

    @Test fun readRequestRoundTripsThroughDecoder() {
        val request = FelicaCodec.decodeRequest(FelicaCodec.readRequest(idm, intArrayOf(0x00, 0x82)))
        assertNotNull(request)
        assertArrayEquals(idm, request!!.nfcid2)
        assertEquals(listOf(0x00, 0x82), request.blocks.map { it.blockNumber })
    }

    @Test fun responseContainsGeneratedProfileBlocks() {
        val profile = requireNotNull(CardProfile.create(
            "Captured", "02FE123456789ABC", spad0 = "00112233445566778899AABBCCDDEEFF"))
        val image = CardImage(profile)
        val response = FelicaCodec.readResponse(
            idm,
            listOf(requireNotNull(image.read(0x00)), requireNotNull(image.read(0x82)))
        )
        val decoded = requireNotNull(FelicaCodec.decodeReadResponse(response))
        assertEquals(2, decoded.blocks.size)
        assertEquals("00112233445566778899AABBCCDDEEFF", HexCodec.encode(decoded.blocks[0]))
        assertEquals("02FE123456789ABC", HexCodec.encode(decoded.blocks[1].copyOfRange(0, 8)))
    }

    @Test fun cardImageUsesAimeSystemCodeInBlock85() {
        val profile = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))

        assertEquals(
            "88B40000000000000000000000000000",
            HexCodec.encode(requireNotNull(CardImage(profile).read(0x85)))
        )
    }

    @Test fun rejectsReadFramesThatCannotFitInOneByteLength() {
        val blocks = List(FelicaCodec.MAX_READ_BLOCKS + 1) { ByteArray(16) }

        assertThrows(IllegalArgumentException::class.java) {
            FelicaCodec.readResponse(idm, blocks)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FelicaCodec.readRequest(idm, IntArray(FelicaCodec.MAX_READ_BLOCKS + 1))
        }
    }

    @Test fun missingImageBlocksAreNotReturnedAsZeroData() {
        val profile = requireNotNull(CardProfile.create("Captured", "02FE123456789ABC"))

        assertNull(CardImage(profile).read(0xFF))
    }

    @Test fun encodesReadOnlyWriteFailure() {
        val response = FelicaCodec.writeResponse(idm, 0x01, 0xA8)
        assertEquals(0x09, response[1].toInt())
        assertArrayEquals(idm, response.copyOfRange(2, 10))
        assertArrayEquals(byteArrayOf(0x01, 0xA8.toByte()), response.copyOfRange(10, 12))
    }

    @Test fun rejectsTruncatedFrame() {
        val request = FelicaCodec.readRequest(idm, intArrayOf(0x00)).copyOf(15)
        assertFalse(FelicaCodec.decodeRequest(request) != null)
    }

    @Test fun rejectsWriteFrameWithoutBlockPayload() {
        val malformed = FelicaCodec.readRequest(idm, intArrayOf(0x00)).apply {
            this[1] = FelicaCodec.WRITE_COMMAND
        }
        assertFalse(FelicaCodec.decodeRequest(malformed) != null)
    }
}
