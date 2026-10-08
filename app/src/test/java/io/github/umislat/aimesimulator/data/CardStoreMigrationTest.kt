package io.github.umislat.aimesimulator.data

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

    @Before fun prepareLegacyStorage() {
        context = RuntimeEnvironment.getApplication()
        java.io.File(context.filesDir, "cards.json").delete()
        preferences = context.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE)
        preferences.edit().clear().putBoolean("storage_migrated", true).commit()
        val store = CardStore(context)
        store.put(profile)
        store.select(profile.profileId)
    }

    @Test fun retiresOldFixedModeWithoutTouchingSerializedCardData() {
        legacyFixedMode()
        val encoded = preferences.getString("profiles_v2", null)

        val migrated = CardStore(context)

        assertNull(migrated.selectedProfile())
        assertOriginalRoute(migrated)
        assertTrue(migrated.compatibilityRetiredNoticePending())
        assertEquals(encoded, preferences.getString("profiles_v2", null))
        assertEquals(listOf(profile), migrated.profiles())
        assertFalse(migrated.persistedRouteOnlyMode())
    }

    @Test fun honorsLegacyBooleanMode() {
        preferences.edit().remove("idm_route_mode")
            .putBoolean("compatibility_mode", true).commit()

        val migrated = CardStore(context)

        assertNull(migrated.selectedProfile())
        assertOriginalRoute(migrated)
        assertTrue(migrated.compatibilityRetiredNoticePending())
        assertEquals(listOf(profile), migrated.profiles())
    }

    @Test fun leavesOriginalSelectionAndDataUntouched() {
        originalMode()
        val migrated = CardStore(context)

        assertEquals(profile, migrated.selectedProfile())
        assertOriginalRoute(migrated)
        assertFalse(migrated.compatibilityRetiredNoticePending())
    }

    @Test fun doesNotRetireGeneratedModeAgainAfterUserReselects() {
        legacyFixedMode()
        val migrated = CardStore(context)
        migrated.dismissCompatibilityRetiredNotice()
        generatedMode(migrated)
        migrated.select(profile.profileId)

        val reopened = CardStore(context)

        assertEquals(profile, reopened.selectedProfile())
        assertFalse(reopened.compatibilityRetiredNoticePending())
        assertGeneratedRoute(reopened)
    }

    @Test fun keepsNoticePendingAcrossStoreInstancesUntilAcknowledged() {
        legacyFixedMode()
        assertTrue(CardStore(context).compatibilityRetiredNoticePending())
        assertTrue(CardStore(context).compatibilityRetiredNoticePending())

        CardStore(context).dismissCompatibilityRetiredNotice()

        assertFalse(CardStore(context).compatibilityRetiredNoticePending())
        assertNull(CardStore(context).selectedProfile())
    }

    @Test fun migratesLegacyJsonWithoutAutoSelectingRetiredIdentity() {
        preferences.edit().clear().putString("idm_route_mode", "FIXED_COMPATIBILITY").commit()
        val legacy = """
            [{"key":"legacy","name":"Saved","idm":"012E112233445566",
              "spad0":"00112233445566778899AABBCCDDEEFF",
              "felicaLiteIdBlock":"012E1122334455660078000000000000",
              "accessCode":"12345678901234567890"}]
        """.trimIndent()
        java.io.File(context.filesDir, "cards.json").writeText(legacy)

        val migrated = CardStore(context)

        assertNull(migrated.selectedProfile())
        assertEquals(profile.copy(profileId = "legacy"), migrated.profiles().single())
        assertTrue(migrated.compatibilityRetiredNoticePending())
        assertTrue(preferences.getBoolean("storage_migrated", false))
    }

    @Test fun emptyInstallationDoesNotCreateOrSelectACard() {
        preferences.edit().clear().commit()
        val store = CardStore(context)

        assertTrue(store.profiles().isEmpty())
        assertNull(store.selectedProfile())
        assertOriginalRoute(store)
        assertFalse(store.compatibilityRetiredNoticePending())
        assertTrue(store.persistedRouteOnlyMode())
        store.put(profile)
        assertTrue(CardStore(context).persistedRouteOnlyMode())
    }

    @Test fun preservesPrefixModeAndItsPersistedRouteOption() {
        preferences.edit().putString("idm_route_mode", "PREFIX_COMPATIBILITY")
            .putBoolean("persisted_route_only_mode", true).commit()

        val reopened = CardStore(context)

        assertEquals(profile, reopened.selectedProfile())
        assertEquals(IdmRouteMode.PREFIX_COMPATIBILITY, reopened.idmRouteMode())
        assertTrue(reopened.persistedRouteOnlyMode())
        assertFalse(reopened.compatibilityRetiredNoticePending())
    }

    @Test fun upgradingExistingModuleUsersDoesNotSwitchTheirMethod() {
        preferences.edit().remove("persisted_route_only_mode").commit()
        val encoded = preferences.getString("profiles_v2", null)
        val store = CardStore(context)
        assertFalse(store.persistedRouteOnlyMode())
        assertEquals(profile, store.selectedProfile())
        assertEquals(encoded, preferences.getString("profiles_v2", null))
    }

    private fun legacyFixedMode() {
        preferences.edit().putString("idm_route_mode", "FIXED_COMPATIBILITY")
            .putBoolean("persisted_route_only_mode", true).commit()
    }

    private fun originalMode() {
        preferences.edit().putString("idm_route_mode", "ORIGINAL").commit()
    }

    private fun generatedMode(store: CardStore) = store.setIdmRouteMode(IdmRouteMode.GENERATED_COMPATIBILITY)

    private fun assertOriginalRoute(store: CardStore) = assertEquals(IdmRouteMode.ORIGINAL, store.idmRouteMode())

    private fun assertGeneratedRoute(store: CardStore) = assertEquals(IdmRouteMode.GENERATED_COMPATIBILITY, store.idmRouteMode())
}
