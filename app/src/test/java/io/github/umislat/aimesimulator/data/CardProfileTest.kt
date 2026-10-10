package io.github.umislat.aimesimulator.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardProfileTest {
    @Test fun retainsTheOriginalIdentifier() {
        val profile = requireNotNull(CardProfile.create("Test", "4D494D494245414D"))
        assertEquals("4D494D494245414D", profile.idm)
    }

    @Test fun acceptsAnOriginal02feIdentifierWithoutTransformingIt() {
        val profile = requireNotNull(CardProfile.create("Test", "02FE123456789ABC"))
        assertEquals("02FE123456789ABC", profile.idm)
    }

    @Test fun validatesCapturedBlocks() {
        assertNull(CardProfile.create("Invalid", "02FE123456789ABC", spad0 = "00"))
    }

    @Test fun originalIdentityDoesNotDependOnProfileMetadataOrUuid() {
        val profile = requireNotNull(CardProfile.create("Original", "012E112233445566"))
        val imported = profile.copy(label = "Renamed", profileId = "new-import-id",
            accessCode = "12345678901234567890", spad0 = "00".repeat(16))
        assertEquals(profile.idm, imported.idm)
    }

    @Test fun normalizesAndFormatsPrintedAccessCode() {
        val profile = requireNotNull(CardProfile.create(
            "Test",
            "02FE123456789ABC",
            accessCode = "1234 5678-9012 3456 7890"
        ))
        assertEquals("12345678901234567890", profile.accessCode)
        assertEquals("1234 5678 9012 3456 7890", profile.formattedAccessCode())
    }

    @Test fun rejectsInvalidPrintedAccessCode() {
        assertNull(CardProfile.create(
            "Invalid",
            "02FE123456789ABC",
            accessCode = "1234567890123456789X"
        ))
    }

    @Test fun retainsRawBlocksWithManuallyEnteredAccessCode() {
        val profile = requireNotNull(CardProfile.create(
            label = "",
            idm = "012E112233445566",
            spad0 = "00112233445566778899AABBCCDDEEFF",
            accessCode = "5012 3456 7890 1234 5678"
        ))

        assertEquals("IDm 5566", profile.label)
        assertEquals("012E112233445566", profile.idm)
        assertEquals("00112233445566778899AABBCCDDEEFF", profile.spad0)
        assertEquals("50123456789012345678", profile.accessCode)
    }
}
