package io.github.umislat.aimesimulator.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardBackupCodecTest {
    @Test fun roundTripPreservesAllCardFieldsAndSelection() {
        val first = profile(
            id = "profile-one",
            label = "Primary",
            idm = "012E112233445566",
            spad0 = "00112233445566778899AABBCCDDEEFF",
            idBlock = "012E1122334455660078000000000000",
            accessCode = "50123456789012345678"
        )
        val second = profile("profile-two", "Backup", "02FE123456789ABC")
        val snapshot = CardBackupCodec.Snapshot(
            profiles = listOf(first, second),
            selectedProfileId = second.profileId,
            exportedAtEpochMillis = 1_700_000_000_000L
        )

        val decoded = CardBackupCodec.decode(CardBackupCodec.encode(snapshot).toByteArray())

        assertEquals(CardBackupCodec.DecodeResult.Success(snapshot), decoded)
    }

    @Test fun rejectsContentChangedWithoutUpdatingChecksum() {
        val snapshot = CardBackupCodec.Snapshot(
            profiles = listOf(profile("profile-one", "Primary", "012E112233445566")),
            selectedProfileId = null,
            exportedAtEpochMillis = 1_700_000_000_000L
        )
        val tampered = CardBackupCodec.encode(snapshot).replace("Primary", "Changed")

        assertEquals(
            CardBackupCodec.DecodeResult.Failure(
                CardBackupCodec.FailureReason.INTEGRITY_CHECK_FAILED
            ),
            CardBackupCodec.decode(tampered.toByteArray())
        )
    }

    @Test fun rejectsUnknownFutureVersion() {
        val snapshot = CardBackupCodec.Snapshot(
            profiles = emptyList(),
            selectedProfileId = null,
            exportedAtEpochMillis = 1_700_000_000_000L
        )
        val future = CardBackupCodec.encode(snapshot).replace(
            "\"version\": 1",
            "\"version\": 2"
        )

        assertEquals(
            CardBackupCodec.DecodeResult.Failure(
                CardBackupCodec.FailureReason.UNSUPPORTED_VERSION
            ),
            CardBackupCodec.decode(future.toByteArray())
        )
    }

    @Test fun rejectsDuplicateProfileIdentifiersEvenWithValidJson() {
        val profile = profile("same-id", "Primary", "012E112233445566")
        val snapshot = CardBackupCodec.Snapshot(
            profiles = listOf(profile, profile.copy(label = "Duplicate")),
            selectedProfileId = null,
            exportedAtEpochMillis = 1_700_000_000_000L
        )
        val encoded = runCatching { CardBackupCodec.encode(snapshot) }

        assertTrue(encoded.isFailure)
    }

    @Test fun rejectsOversizedInputBeforeParsing() {
        val oversized = ByteArray(CardBackupCodec.MAX_BACKUP_BYTES + 1) { 'x'.code.toByte() }

        assertEquals(
            CardBackupCodec.DecodeResult.Failure(CardBackupCodec.FailureReason.TOO_LARGE),
            CardBackupCodec.decode(oversized)
        )
    }

    private fun profile(
        id: String,
        label: String,
        idm: String,
        spad0: String? = null,
        idBlock: String? = null,
        accessCode: String? = null
    ): CardProfile = requireNotNull(CardProfile.create(
        label = label,
        idm = idm,
        spad0 = spad0,
        idBlock = idBlock,
        accessCode = accessCode,
        profileId = id
    ))
}
