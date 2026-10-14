package io.github.umislat.aicecho.nfc

import io.github.umislat.aicecho.data.CardProfile
import io.github.umislat.aicecho.data.HexCodec
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalCardReaderTest {
    private val idm = hex("012E112233445566")
    private val systemCode = hex("88B4")
    private val spad0 = hex("00112233445566778899AABBCCDDEEFF")
    private val idBlock = hex("012E1122334455660078000000000000")

    @Test fun capturesRawBlocksWithoutDerivingAccessCode() {
        val requested = mutableListOf<List<Int>>()
        val capture = PhysicalCardReader.captureFelica(idm, systemCode) { blocks ->
            requested += blocks.toList()
            listOf(spad0, idBlock)
        }

        assertEquals(listOf(listOf(0x00, 0x82)), requested)
        assertEquals("00112233445566778899AABBCCDDEEFF", capture.spad0)
        assertEquals("012E1122334455660078000000000000", capture.idBlock)
        val profile = requireNotNull(CardProfile.create(
            "Captured", capture.idm, capture.spad0, capture.idBlock
        ))
        assertNull(profile.accessCode)
        assertEquals(capture.spad0, profile.spad0)
    }

    @Test fun retriesIndividualBlocksAfterCombinedReadThrows() {
        val requested = mutableListOf<List<Int>>()
        val capture = PhysicalCardReader.captureFelica(idm, systemCode) { blocks ->
            requested += blocks.toList()
            if (blocks.size > 1) throw IOException("Combined read failed")
            listOf(if (blocks.single() == 0x00) spad0 else idBlock)
        }

        assertEquals(listOf(listOf(0x00, 0x82), listOf(0x00), listOf(0x82)), requested)
        assertEquals(HexCodec.encode(spad0), capture.spad0)
        assertEquals(HexCodec.encode(idBlock), capture.idBlock)
    }

    @Test fun retainsIdBlockWhenSpad0CannotBeRead() {
        val capture = PhysicalCardReader.captureFelica(idm, systemCode) { blocks ->
            if (blocks.size > 1) null
            else if (blocks.single() == 0x00) throw IOException("Block unavailable")
            else listOf(idBlock)
        }

        assertNull(capture.spad0)
        assertEquals(HexCodec.encode(idBlock), capture.idBlock)
    }

    @Test fun retainsSpad0WhenIdBlockCannotBeRead() {
        val capture = PhysicalCardReader.captureFelica(idm, systemCode) { blocks ->
            if (blocks.size > 1 || blocks.single() != 0x00) null else listOf(spad0)
        }

        assertEquals(HexCodec.encode(spad0), capture.spad0)
        assertNull(capture.idBlock)
    }

    @Test fun retainsIdmWhenAllBlockReadsFail() {
        val capture = PhysicalCardReader.captureFelica(idm, systemCode) {
            throw IOException("Read failed")
        }

        assertEquals(HexCodec.encode(idm), capture.idm)
        assertEquals("88B4", capture.systemCode)
        assertNull(capture.spad0)
        assertNull(capture.idBlock)
    }

    @Test fun acceptsOtherIdmPrefixesAndAbsentSystemCode() {
        val capture = PhysicalCardReader.captureFelica(hex("02FE112233445566"), ByteArray(0)) {
            listOf(ByteArray(16), idBlock)
        }

        assertEquals("02FE112233445566", capture.idm)
        assertEquals("", capture.systemCode)
        assertEquals("00000000000000000000000000000000", capture.spad0)
    }

    @Test fun retriesWhenCombinedReadHasAnUnexpectedBlockCount() {
        val capture = PhysicalCardReader.captureFelica(idm, hex("4000")) { blocks ->
            if (blocks.size > 1) listOf(spad0)
            else listOf(if (blocks.single() == 0x00) spad0 else idBlock)
        }

        assertEquals("4000", capture.systemCode)
        assertEquals(HexCodec.encode(spad0), capture.spad0)
        assertEquals(HexCodec.encode(idBlock), capture.idBlock)
    }

    private fun hex(value: String): ByteArray = requireNotNull(HexCodec.decode(value))
}
