package io.github.umislat.aicecho.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CardStoreMigrationTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private val profile = requireNotNull(CardProfile.create(
        "Saved", "012E112233445566",
        spad0 = "00112233445566778899AABBCCDDEEFF",
        idBlock = "012E1122334455660078000000000000",
        accessCode = "12345678901234567890",
        profileId = "saved-profile"
    ))

    @Before fun prepareStorage() {
        context = RuntimeEnvironment.getApplication()
        java.io.File(context.filesDir, "cards.json").delete()
        preferences = context.getSharedPreferences("aicecho", Context.MODE_PRIVATE)
        preferences.edit().clear().putBoolean("storage_migrated", true).commit()
        CardStore(context).apply { put(profile); select(profile.profileId) }
    }

    @Test fun obsoleteIdentitySettingsDoNotChangeCardsSelectionOrEmulationMethod() {
        for (mode in listOf("ORIGINAL", "FIXED_COMPATIBILITY", "GENERATED_COMPATIBILITY", "PREFIX_COMPATIBILITY", "UNKNOWN")) {
            preferences.edit().putString("idm_route_mode", mode)
                .putBoolean("compatibility_mode", true)
                .putBoolean("compatibility_retired_notice", true)
                .putBoolean("persisted_route_only_mode", true).commit()
            val before = preferences.all

            val reopened = CardStore(context)

            assertEquals(profile, reopened.selectedProfile())
            assertEquals(listOf(profile), reopened.profiles())
            assertTrue(reopened.persistedRouteOnlyMode())
            assertEquals(before, preferences.all)
        }
    }

    @Test fun unusedBooleanCompatibilitySettingDoesNotClearSelection() {
        preferences.edit().remove("idm_route_mode").putBoolean("compatibility_mode", true).commit()
        val before = preferences.all
        assertEquals(profile, CardStore(context).selectedProfile())
        assertEquals(before, preferences.all)
    }

    @Test fun importsLegacyCardDataWithoutIdentityMigration() {
        preferences.edit().clear().putString("idm_route_mode", "FIXED_COMPATIBILITY").commit()
        val legacy = """
            [{"key":"legacy","name":"Saved","idm":"012E112233445566",
              "spad0":"00112233445566778899AABBCCDDEEFF",
              "felicaLiteIdBlock":"012E1122334455660078000000000000",
              "accessCode":"12345678901234567890"}]
        """.trimIndent()
        java.io.File(context.filesDir, "cards.json").writeText(legacy)

        val migrated = CardStore(context)

        assertEquals(profile.copy(profileId = "legacy"), migrated.selectedProfile())
        assertEquals(listOf(profile.copy(profileId = "legacy")), migrated.profiles())
        assertTrue(preferences.getBoolean("storage_migrated", false))
    }

    @Test fun emptyInstallationDoesNotCreateOrSelectACard() {
        preferences.edit().clear().commit()
        val store = CardStore(context)

        assertTrue(store.profiles().isEmpty())
        assertNull(store.selectedProfile())
        assertTrue(store.persistedRouteOnlyMode())
        assertFalse(preferences.contains("idm_route_mode"))
        store.put(profile)
        assertTrue(CardStore(context).persistedRouteOnlyMode())
    }

    @Test fun upgradingExistingModuleUsersDoesNotSwitchTheirMethod() {
        preferences.edit().remove("persisted_route_only_mode").commit()
        val encoded = preferences.getString("profiles_v2", null)
        val store = CardStore(context)
        assertFalse(store.persistedRouteOnlyMode())
        assertEquals(profile, store.selectedProfile())
        assertEquals(encoded, preferences.getString("profiles_v2", null))
    }
}
