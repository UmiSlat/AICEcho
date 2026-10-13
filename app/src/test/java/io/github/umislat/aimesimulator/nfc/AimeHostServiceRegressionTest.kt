package io.github.umislat.aimesimulator.nfc

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.data.HexCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AimeHostServiceRegressionTest {
    private lateinit var context: Context
    private lateinit var store: CardStore
    private lateinit var controller: ServiceController<AimeHostService>
    private lateinit var service: AimeHostService
    private val profile = requireNotNull(CardProfile.create(
        "Synthetic", "012E112233445566",
        spad0 = "00112233445566778899AABBCCDDEEFF",
        idBlock = "012E1122334455660078000000000000",
        accessCode = "12345678901234567890",
        profileId = "service-test"
    ))

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        java.io.File(context.filesDir, "cards.json").delete()
        context.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("storage_migrated", true).commit()
        store = CardStore(context)
        store.put(profile)
        store.select(profile.profileId)
        controller = Robolectric.buildService(AimeHostService::class.java).create()
        service = controller.get()
    }

    @After fun tearDown() {
        controller.destroy()
    }

    @Test fun capturedRawBlocksSurviveSaveAndReopenBeforeSimulation() {
        val capture = PhysicalCardReader.captureFelica(bytes(profile.idm), bytes("88B4")) { requested ->
            requested.map { number ->
                bytes(if (number == 0) requireNotNull(profile.spad0) else requireNotNull(profile.idBlock))
            }
        }
        val captured = requireNotNull(CardProfile.create(
            profile.label, capture.idm, capture.spad0, capture.idBlock, profile.accessCode, profile.profileId
        ))
        store.put(captured)
        assertEquals(captured, CardStore(context).selectedProfile())

        val result = read(profile.idm, 0x00, 0x82, 0x83, 0x85)
        assertTrue(result.succeeded)
        assertEquals(profile.spad0, HexCodec.encode(result.blocks[0]))
        assertEquals(profile.idBlock, HexCodec.encode(result.blocks[1]))
        assertEquals(profile.idm + "00F1000000014300", HexCodec.encode(result.blocks[2]))
        assertEquals(HceSession.SYSTEM_CODE + "0000000000000000000000000000", HexCodec.encode(result.blocks[3]))
    }

    @Test fun cardChangesRefreshCachedMetadataWithoutChangingCapturedBlocks() {
        read(profile.idm, 0x00, 0x82, 0x83)
        val other = profile.copy(profileId = "other-card", idm = "012E998877665544")
        store.put(other)
        store.select(other.profileId)
        assertNull(packet(profile.idm, 0x00))
        val result = read(other.idm, 0x00, 0x82, 0x83)
        assertEquals(profile.spad0, HexCodec.encode(result.blocks[0]))
        assertEquals(profile.idBlock, HexCodec.encode(result.blocks[1]))
        assertEquals(other.idm + "00F1000000014300", HexCodec.encode(result.blocks[2]))
        store.select(profile.profileId)
        assertEquals(profile.idm + "00F1000000014300", HexCodec.encode(read(profile.idm, 0x83).blocks.single()))
    }

    @Test fun profileEditsRefreshCachedRawData() {
        read(profile.idm, 0x00)
        val edited = profile.copy(spad0 = "FFEEDDCCBBAA99887766554433221100")
        store.put(edited)
        assertEquals(edited.spad0, HexCodec.encode(read(profile.idm, 0x00).blocks.single()))
    }

    @Test fun deselectionAndDeletionStopResponsesEvenWithAnExistingCache() {
        read(profile.idm, 0x00)
        store.select(null)
        assertNull(packet(profile.idm, 0x00))
        store.select(profile.profileId)
        read(profile.idm, 0x00)
        store.remove(profile.profileId)
        assertNull(packet(profile.idm, 0x00))
    }

    @Test fun obsoleteIdentitySettingsNeverEnableAlternateResponses() {
        val preferences = context.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE)
        for (mode in listOf("FIXED_COMPATIBILITY", "GENERATED_COMPATIBILITY", "PREFIX_COMPATIBILITY")) {
            preferences.edit().putString("idm_route_mode", mode).putBoolean("compatibility_mode", true).commit()
            assertNull(packet("02FE112233445566", 0x00))
            assertNull(packet("02FED782A6CF82BB", 0x00))
            val response = read(profile.idm, 0x00, 0x82, 0x83)
            assertEquals(profile.spad0, HexCodec.encode(response.blocks[0]))
            assertEquals(profile.idBlock, HexCodec.encode(response.blocks[1]))
            assertEquals(profile.idm + "00F1000000014300", HexCodec.encode(response.blocks[2]))
            assertEquals(profile, CardStore(context).selectedProfile())
        }
    }

    @Test fun unsupportedBlockRejectsWholeReadWithoutPartialData() {
        val result = read(profile.idm, 0x00, 0x80)
        assertFalse(result.succeeded)
        assertEquals(0x01, result.status1)
        assertEquals(0xA2, result.status2)
        assertTrue(result.blocks.isEmpty())
    }

    @Test fun unsupportedServiceIsRejected() {
        val request = FelicaCodec.readRequest(bytes(profile.idm), intArrayOf(0))
        request[11] = 0x09
        val result = requireNotNull(FelicaCodec.decodeReadResponse(
            requireNotNull(service.processNfcFPacket(request, null))
        ))
        assertFalse(result.succeeded)
        assertTrue(result.blocks.isEmpty())
    }

    @Test fun writeIsRejectedWithoutChangingStoredData() {
        val request = FelicaCodec.readRequest(bytes(profile.idm), intArrayOf(0)).copyOf(32)
        request[0] = request.size.toByte()
        request[1] = FelicaCodec.WRITE_COMMAND
        val response = requireNotNull(service.processNfcFPacket(request, null))
        assertEquals(FelicaCodec.WRITE_RESPONSE, response[1])
        assertEquals(0x01, response[10].toInt() and 0xFF)
        assertEquals(0xA8, response[11].toInt() and 0xFF)
        assertEquals(profile, CardStore(context).selectedProfile())
        bytes("02FE112233445566").copyInto(request, 2)
        assertNull(service.processNfcFPacket(request, null))
    }

    @Test fun malformedAndUnknownCommandsAreIgnored() {
        assertNull(service.processNfcFPacket(byteArrayOf(0), null))
        val request = FelicaCodec.readRequest(bytes(profile.idm), intArrayOf(0)).copyOf(10)
        request[0] = 10
        request[1] = 0x30
        assertNull(service.processNfcFPacket(request, null))
    }

    @Test fun commandsAddressedToAnotherIdentityAreIgnored() {
        assertNull(packet("02FE112233445566", 0x00))
    }

    @Test fun emptyBlockRequestIsIgnored() {
        val request = FelicaCodec.readRequest(bytes(profile.idm), intArrayOf(0)).copyOf(14)
        request[0] = 14
        request[13] = 0
        assertNull(service.processNfcFPacket(request, null))
    }

    @Test fun savedRouteTrafficReportsOnlyCountersWithoutChangingRootResponses() {
        val events = mutableListOf<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { intent?.let(events::add) }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(NfcSessionEvent.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            store.setPersistedRouteOnlyMode(true)
            val savedResponse = packet(profile.idm, 0x00)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, events.size)
            assertEquals(context.packageName, events.single().`package`)
            assertEquals(setOf(NfcSessionEvent.TIME, NfcSessionEvent.RESPONDED), events.single().extras!!.keySet())
            assertTrue(events.single().getBooleanExtra(NfcSessionEvent.RESPONDED, false))
            store.setPersistedRouteOnlyMode(false)
            assertTrue(savedResponse.contentEquals(packet(profile.idm, 0x00)))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, events.size)
            assertEquals(profile, store.selectedProfile())
        } finally { context.unregisterReceiver(receiver) }
    }

    @Test fun savedRouteRejectedCommandsStillReportThatTheApplicationWasReached() {
        val events = mutableListOf<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { intent?.let(events::add) }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(NfcSessionEvent.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            store.setPersistedRouteOnlyMode(true)
            assertNull(packet("02FE112233445566", 0x00))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, events.size)
            assertFalse(events.single().getBooleanExtra(NfcSessionEvent.RESPONDED, true))
        } finally { context.unregisterReceiver(receiver) }
    }

    private fun packet(idm: String, vararg blocks: Int): ByteArray? =
        service.processNfcFPacket(FelicaCodec.readRequest(bytes(idm), blocks), null)

    private fun read(idm: String, vararg blocks: Int): FelicaCodec.ReadResult =
        requireNotNull(FelicaCodec.decodeReadResponse(requireNotNull(packet(idm, *blocks))))

    private fun bytes(value: String): ByteArray = requireNotNull(HexCodec.decode(value))

}
