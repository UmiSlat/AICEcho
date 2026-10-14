package io.github.umislat.aicecho.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class CardStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var cachedProfilesInitialized = false
    private var cachedProfilesSource: String? = null
    private var cachedProfiles = emptyList<CardProfile>()

    init {
        if (!preferences.contains(KEY_PERSISTED_ROUTE_ONLY_MODE)) {
            val newInstallation = !preferences.contains(KEY_MIGRATION_DONE) &&
                !preferences.contains(KEY_PROFILES) && !File(appContext.filesDir, "cards.json").isFile
            preferences.edit { putBoolean(KEY_PERSISTED_ROUTE_ONLY_MODE, newInstallation) }
        }
        migrateExistingInstallation()
    }

    @Synchronized
    fun profiles(): List<CardProfile> {
        val encoded = preferences.getString(KEY_PROFILES, null)
        if (cachedProfilesInitialized && encoded == cachedProfilesSource) return cachedProfiles
        return decodeProfiles(encoded).also { profiles ->
            cachedProfilesSource = encoded
            cachedProfiles = profiles
            cachedProfilesInitialized = true
        }
    }

    @Synchronized
    fun selectedProfile(): CardProfile? {
        val selectedId = preferences.getString(KEY_SELECTED, null) ?: return null
        return profiles().firstOrNull { it.profileId == selectedId }
    }

    @Synchronized
    fun put(profile: CardProfile) {
        val updated = profiles().toMutableList()
        val index = updated.indexOfFirst { it.profileId == profile.profileId }
        if (index >= 0) updated[index] = profile else updated += profile
        writeProfiles(updated)
    }

    @Synchronized
    fun remove(profileId: String) {
        val updated = profiles().filterNot { it.profileId == profileId }
        preferences.edit {
            putString(KEY_PROFILES, encodeProfiles(updated))
            if (preferences.getString(KEY_SELECTED, null) == profileId) remove(KEY_SELECTED)
        }
    }

    @Synchronized
    fun select(profileId: String?) {
        if (preferences.getString(KEY_SELECTED, null) == profileId) return
        preferences.edit {
            if (profileId == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, profileId)
        }
    }

    @Synchronized
    fun backupSnapshot(nowEpochMillis: Long = System.currentTimeMillis()): CardBackupCodec.Snapshot =
        CardBackupCodec.Snapshot(
            profiles = profiles(),
            selectedProfileId = selectedProfile()?.profileId,
            exportedAtEpochMillis = nowEpochMillis
        )

    @Synchronized
    fun importProfiles(
        snapshot: CardBackupCodec.Snapshot,
        mode: CardImportPlanner.Mode
    ): CardImportPlanner.Plan {
        val plan = CardImportPlanner.plan(
            existingProfiles = profiles(),
            existingSelectedProfileId = selectedProfile()?.profileId,
            importedProfiles = snapshot.profiles,
            mode = mode
        )
        val encodedProfiles = encodeProfiles(plan.profiles)
        preferences.edit {
            putString(KEY_PROFILES, encodedProfiles)
            if (plan.selectedProfileId == null) remove(KEY_SELECTED)
            else putString(KEY_SELECTED, plan.selectedProfileId)
        }
        cachedProfilesSource = encodedProfiles
        cachedProfiles = plan.profiles
        cachedProfilesInitialized = true
        return plan
    }

    fun persistedRouteOnlyMode(): Boolean =
        preferences.getBoolean(KEY_PERSISTED_ROUTE_ONLY_MODE, false)

    fun setPersistedRouteOnlyMode(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_PERSISTED_ROUTE_ONLY_MODE, enabled) }
    }

    fun advancedMode(): Boolean = preferences.getBoolean(KEY_ADVANCED_MODE, false)

    fun setAdvancedMode(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_ADVANCED_MODE, enabled) }
    }

    fun showIdm(): Boolean = preferences.getBoolean(KEY_SHOW_IDM, true)

    fun setShowIdm(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_SHOW_IDM, enabled) }
    }

    fun showAccessCode(): Boolean = preferences.getBoolean(KEY_SHOW_ACCESS_CODE, true)

    fun setShowAccessCode(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_SHOW_ACCESS_CODE, enabled) }
    }

    fun defaultNfcGuidanceShown(): Boolean =
        preferences.getBoolean(KEY_DEFAULT_NFC_GUIDANCE_SHOWN, false)

    fun markDefaultNfcGuidanceShown() {
        preferences.edit { putBoolean(KEY_DEFAULT_NFC_GUIDANCE_SHOWN, true) }
    }

    private fun writeProfiles(profiles: List<CardProfile>) {
        preferences.edit { putString(KEY_PROFILES, encodeProfiles(profiles)) }
    }

    private fun encodeProfiles(profiles: List<CardProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("id", profile.profileId)
                put("label", profile.label)
                put("idm", profile.idm)
                profile.spad0?.let { put("spad0", it) }
                profile.idBlock?.let { put("idBlock", it) }
                profile.accessCode?.let { put("accessCode", it) }
            })
        }
        return array.toString()
    }

    private fun decodeProfiles(encoded: String?): List<CardProfile> {
        if (encoded.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    CardProfile.create(
                        label = item.optString("label"),
                        idm = item.optString("idm"),
                        spad0 = item.optNullableString("spad0"),
                        idBlock = item.optNullableString("idBlock"),
                        accessCode = item.optNullableString("accessCode"),
                        profileId = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                    )?.let(::add)
                }
            }
        }.onFailure { Log.w(TAG, "Ignoring malformed profile storage", it) }
            .getOrDefault(emptyList())
    }

    private fun migrateExistingInstallation() {
        if (preferences.getBoolean(KEY_MIGRATION_DONE, false)) return

        val imported = mutableListOf<CardProfile>()
        val oldFile = File(appContext.filesDir, "cards.json")
        if (oldFile.isFile) {
            runCatching {
                val array = JSONArray(oldFile.readText())
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    CardProfile.create(
                        label = item.optString("name"),
                        idm = item.optString("idm"),
                        spad0 = item.optNullableString("spad0"),
                        idBlock = item.optNullableString("felicaLiteIdBlock"),
                        accessCode = item.optNullableString("accessCode"),
                        profileId = item.optString("key").ifBlank { java.util.UUID.randomUUID().toString() }
                    )?.let(imported::add)
                }
            }.onFailure { Log.w(TAG, "Existing profile import failed", it) }
        }

        preferences.edit {
            putBoolean(KEY_MIGRATION_DONE, true)
            if (profiles().isEmpty() && imported.isNotEmpty()) {
                putString(KEY_PROFILES, encodeProfiles(imported))
                putString(KEY_SELECTED, imported.first().profileId)
            }
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

    companion object {
        private const val TAG = "AICEchoCardStore"
        private const val PREFERENCES = "aicecho"
        private const val KEY_PROFILES = "profiles_v2"
        private const val KEY_SELECTED = "selected_profile"
        private const val KEY_PERSISTED_ROUTE_ONLY_MODE = "persisted_route_only_mode"
        private const val KEY_ADVANCED_MODE = "advanced_mode"
        private const val KEY_SHOW_IDM = "show_idm"
        private const val KEY_SHOW_ACCESS_CODE = "show_access_code"
        private const val KEY_DEFAULT_NFC_GUIDANCE_SHOWN = "default_nfc_guidance_shown"
        private const val KEY_MIGRATION_DONE = "storage_migrated"
    }
}
