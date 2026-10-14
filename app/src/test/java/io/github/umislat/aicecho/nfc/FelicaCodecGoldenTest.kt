package io.github.umislat.aicecho.nfc

import io.github.umislat.aicecho.data.HexCodec
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FelicaCodecGoldenTest {
    private val idm = hex("02FE123456789ABC")

    @Test fun readCommandMatchesIndependentGoldenVector() {
        val frame = hex(
            """
            12 06 02 FE 12 34 56 78 9A BC
            01 0B 00
            02 80 00 80 82
            """
        )

        assertArrayEquals(frame, FelicaCodec.readRequest(idm, intArrayOf(0x00, 0x82)))

        val request = requireNotNull(FelicaCodec.decodeRequest(frame))
        assertEquals(FelicaCodec.READ_COMMAND, request.command)
        assertArrayEquals(idm, request.nfcid2)
        assertArrayEquals(intArrayOf(0x000B), request.services)
        assertEquals(
            listOf(
                FelicaCodec.BlockAddress(serviceIndex = 0, blockNumber = 0x00),
                FelicaCodec.BlockAddress(serviceIndex = 0, blockNumber = 0x82)
            ),
            request.blocks
        )
    }

    @Test fun decoderHandlesBothBlockListElementWidths() {
        val frame = hex(
            """
            15 06 02 FE 12 34 56 78 9A BC
            02 0B 00 0B 10
            02 80 82 01 34 12
            """
        )

        val request = requireNotNull(FelicaCodec.decodeRequest(frame))
        assertArrayEquals(intArrayOf(0x000B, 0x100B), request.services)
        assertEquals(
            listOf(
                FelicaCodec.BlockAddress(serviceIndex = 0, blockNumber = 0x82),
                FelicaCodec.BlockAddress(serviceIndex = 1, blockNumber = 0x1234)
            ),
            request.blocks
        )
    }

    @Test fun decoderAcceptsCompleteWriteCommandVector() {
        val frame = hex(
            """
            20 08 02 FE 12 34 56 78 9A BC
            01 09 00
            01 80 00
            00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF
            """
        )

        val request = requireNotNull(FelicaCodec.decodeRequest(frame))
        assertEquals(FelicaCodec.WRITE_COMMAND, request.command)
        assertArrayEquals(intArrayOf(0x0009), request.services)
        assertEquals(
            listOf(FelicaCodec.BlockAddress(serviceIndex = 0, blockNumber = 0x00)),
            request.blocks
        )
    }

    @Test fun responsesMatchIndependentGoldenVectors() {
        val firstBlock = hex("00112233445566778899AABBCCDDEEFF")
        val secondBlock = hex("FFEEDDCCBBAA99887766554433221100")
        val readFrame = hex(
            """
            2D 07 02 FE 12 34 56 78 9A BC 00 00 02
            00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF
            FF EE DD CC BB AA 99 88 77 66 55 44 33 22 11 00
            """
        )
        val writeFrame = hex("0C 09 02 FE 12 34 56 78 9A BC 00 00")

        assertArrayEquals(readFrame, FelicaCodec.readResponse(idm, listOf(firstBlock, secondBlock)))
        assertArrayEquals(writeFrame, FelicaCodec.writeResponse(idm))

        val decoded = requireNotNull(FelicaCodec.decodeReadResponse(readFrame))
        assertArrayEquals(idm, decoded.nfcid2)
        assertEquals(0, decoded.status1)
        assertEquals(0, decoded.status2)
        assertTrue(decoded.succeeded)
        assertArrayEquals(firstBlock, decoded.blocks[0])
        assertArrayEquals(secondBlock, decoded.blocks[1])
    }

    @Test fun decoderRejectsStructurallyMalformedCommandFrames() {
        val malformedFrames = mapOf(
            "declared length mismatch" to """
                11 06 02 FE 12 34 56 78 9A BC 01 0B 00 02 80 00 80 82
            """,
            "zero services" to """
                0C 06 02 FE 12 34 56 78 9A BC 00 00
            """,
            "truncated service table" to """
                0E 06 02 FE 12 34 56 78 9A BC 02 0B 00 00
            """,
            "missing block count" to """
                0D 06 02 FE 12 34 56 78 9A BC 01 0B 00
            """,
            "invalid service index" to """
                10 06 02 FE 12 34 56 78 9A BC 01 0B 00 01 81 00
            """,
            "truncated two-byte descriptor" to """
                0F 06 02 FE 12 34 56 78 9A BC 01 0B 00 01 80
            """,
            "truncated three-byte descriptor" to """
                10 06 02 FE 12 34 56 78 9A BC 01 0B 00 01 00 34
            """,
            "trailing read data" to """
                11 06 02 FE 12 34 56 78 9A BC 01 0B 00 01 80 00 00
            """,
            "missing write payload" to """
                10 08 02 FE 12 34 56 78 9A BC 01 09 00 01 80 00
            """
        )

        malformedFrames.forEach { (name, value) ->
            assertNull(name, FelicaCodec.decodeRequest(hex(value)))
        }
    }

    @Test fun decoderRejectsStructurallyMalformedResponseFrames() {
        val malformedFrames = mapOf(
            "response too short" to """
                0C 07 02 FE 12 34 56 78 9A BC 00 00
            """,
            "wrong response command" to """
                0D 09 02 FE 12 34 56 78 9A BC 00 00 00
            """,
            "missing declared block" to """
                0D 07 02 FE 12 34 56 78 9A BC 00 00 01
            """,
            "trailing response data" to """
                0E 07 02 FE 12 34 56 78 9A BC 00 00 00 00
            """
        )

        malformedFrames.forEach { (name, value) ->
            assertNull(name, FelicaCodec.decodeReadResponse(hex(value)))
        }
    }

    @Test fun deterministicFuzzInputsNeverCrashOrMutateInput() {
        val random = Random(0xA1CEF11CL)

        repeat(4096) { sample ->
            val frame = ByteArray(random.nextInt(256))
            random.nextBytes(frame)
            if (frame.isNotEmpty() && sample % 3 != 0) frame[0] = frame.size.toByte()
            if (frame.size > 1) {
                frame[1] = when (sample % 4) {
                    0 -> FelicaCodec.READ_COMMAND
                    1 -> FelicaCodec.WRITE_COMMAND
                    2 -> FelicaCodec.READ_RESPONSE
                    else -> frame[1]
                }
            }
            val original = frame.copyOf()

            FelicaCodec.decodeRequest(frame)
            FelicaCodec.decodeReadResponse(frame)

            assertArrayEquals("sample $sample mutated its input", original, frame)
        }
    }

    private fun hex(value: String): ByteArray = requireNotNull(HexCodec.decode(value))
}
