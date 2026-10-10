package io.github.umislat.aimesimulator.nfc

import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.HexCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CardImageTest {
    private val profile = requireNotNull(CardProfile.create(
        "Captured", "012E112233445566",
        spad0 = "00112233445566778899AABBCCDDEEFF",
        idBlock = "01020304050607081234567890ABCDEF"
    ))

    @Test fun preservesBothCapturedBlocksWithoutRewritingTheIdentity() {
        val image = CardImage(profile)
        assertEquals(profile.spad0, HexCodec.encode(requireNotNull(image.read(0x00))))
        assertEquals(profile.idBlock, HexCodec.encode(requireNotNull(image.read(0x82))))
    }

    @Test fun generatesDeviceMetadataFromOriginalIdentityAndConfiguredPmm() {
        val image = CardImage(profile, pmm = "0102030405060708")
        assertEquals(profile.idm + "0102030405060708", HexCodec.encode(requireNotNull(image.read(0x83))))
    }

    @Test fun generatesSystemMetadataFromTheSelectedService() {
        assertEquals("40000000000000000000000000000000",
            HexCodec.encode(requireNotNull(CardImage(profile, systemCode = "4000").read(0x85))))
    }

    @Test fun missingCaptureUsesOriginalIdentity() {
        val uncaptured = profile.copy(spad0 = null, idBlock = null)
        val image = CardImage(uncaptured)
        assertArrayEquals(ByteArray(16), image.read(0x00))
        assertEquals(profile.idm + "0000000000000000",
            HexCodec.encode(requireNotNull(image.read(0x82))))
    }

    @Test fun declaresReadOnlyConfigurationWithoutNdefOrAuthentication() {
        val expected = ByteArray(16).also { it[4] = 7 }
        assertArrayEquals(expected, CardImage(profile).read(0x88))
    }

    @Test fun doesNotFabricateUnimplementedAuthenticationBlocks() {
        val image = CardImage(profile)
        for (block in listOf(0x80, 0x81, 0x86, 0x87, 0x90, 0x91, 0x92, 0xFF)) {
            assertNull(image.read(block))
        }
    }

    @Test fun suppliesDocumentedDefaultsAndCopiesReturnedData() {
        val image = CardImage(profile)
        for (block in 0x01..0x0D) assertArrayEquals(ByteArray(16), image.read(block))
        assertArrayEquals(ByteArray(16) { 0xFF.toByte() }, image.read(0x0E))
        assertArrayEquals(ByteArray(16), image.read(0x84))
        requireNotNull(image.read(0x00)).fill(0)
        assertEquals(profile.spad0, HexCodec.encode(requireNotNull(image.read(0x00))))
    }

    @Test fun rejectsMalformedMetadataInsteadOfSilentlyReturningZeros() {
        assertThrows(IllegalArgumentException::class.java) { CardImage(profile.copy(idm = "00")) }
        assertThrows(IllegalArgumentException::class.java) { CardImage(profile, pmm = "00") }
        assertThrows(IllegalArgumentException::class.java) { CardImage(profile, systemCode = "GGGG") }
    }
}
