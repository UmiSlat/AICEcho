package io.github.umislat.aicecho.data

internal object CardImportPlanner {
    enum class Mode {
        MERGE,
        REPLACE
    }

    data class Plan(
        val profiles: List<CardProfile>,
        val selectedProfileId: String?
    )

    fun plan(
        existingProfiles: List<CardProfile>,
        existingSelectedProfileId: String?,
        importedProfiles: List<CardProfile>,
        mode: Mode
    ): Plan = when (mode) {
        Mode.REPLACE -> Plan(importedProfiles, selectedProfileId = null)
        Mode.MERGE -> {
            val merged = existingProfiles.toMutableList()
            importedProfiles.forEach { imported ->
                val index = merged.indexOfFirst { it.profileId == imported.profileId }
                if (index >= 0) merged[index] = imported else merged += imported
            }
            val selectedExisting = existingProfiles.firstOrNull {
                it.profileId == existingSelectedProfileId
            }
            val selectedImported = importedProfiles.firstOrNull {
                it.profileId == existingSelectedProfileId
            }
            Plan(
                profiles = merged,
                selectedProfileId = existingSelectedProfileId
                    ?.takeIf { selected ->
                        merged.any { it.profileId == selected } &&
                            (selectedImported == null || selectedImported == selectedExisting)
                    }
            )
        }
    }
}
