package io.github.umislat.aicecho.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardImportPlannerTest {
    @Test fun mergeKeepsOrderSelectionAndUpdatesMatchingIds() {
        val oldFirst = profile("one", "Old", "012E112233445566")
        val second = profile("two", "Second", "02FE123456789ABC")
        val newFirst = oldFirst.copy(label = "Updated")
        val third = profile("three", "Third", "02FEABCDEF123456")

        val plan = CardImportPlanner.plan(
            existingProfiles = listOf(oldFirst, second),
            existingSelectedProfileId = second.profileId,
            importedProfiles = listOf(newFirst, third),
            mode = CardImportPlanner.Mode.MERGE
        )

        assertEquals(listOf(newFirst, second, third), plan.profiles)
        assertEquals(second.profileId, plan.selectedProfileId)
    }

    @Test fun replaceClearsSelectionUntilUserChoosesACard() {
        val imported = profile("new", "Imported", "02FE123456789ABC")

        val plan = CardImportPlanner.plan(
            existingProfiles = listOf(profile("old", "Old", "012E112233445566")),
            existingSelectedProfileId = "old",
            importedProfiles = listOf(imported),
            mode = CardImportPlanner.Mode.REPLACE
        )

        assertEquals(listOf(imported), plan.profiles)
        assertNull(plan.selectedProfileId)
    }

    @Test fun mergeClearsSelectionWhenTheSelectedProfileWasUpdated() {
        val selected = profile("selected", "Old", "012E112233445566")
        val updated = selected.copy(label = "Updated")

        val plan = CardImportPlanner.plan(
            existingProfiles = listOf(selected),
            existingSelectedProfileId = selected.profileId,
            importedProfiles = listOf(updated),
            mode = CardImportPlanner.Mode.MERGE
        )

        assertEquals(listOf(updated), plan.profiles)
        assertNull(plan.selectedProfileId)
    }

    @Test fun mergeKeepsSelectionWhenMatchingBackupRecordIsUnchanged() {
        val selected = profile("selected", "Same", "012E112233445566")

        val plan = CardImportPlanner.plan(
            existingProfiles = listOf(selected),
            existingSelectedProfileId = selected.profileId,
            importedProfiles = listOf(selected),
            mode = CardImportPlanner.Mode.MERGE
        )

        assertEquals(listOf(selected), plan.profiles)
        assertEquals(selected.profileId, plan.selectedProfileId)
    }

    private fun profile(id: String, label: String, idm: String): CardProfile =
        requireNotNull(CardProfile.create(label, idm, profileId = id))
}
