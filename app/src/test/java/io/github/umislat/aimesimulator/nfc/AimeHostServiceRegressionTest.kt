package io.github.umislat.aimesimulator.nfc

import android.app.Application
import android.content.Context
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.data.CompatibilityIdm
import io.github.umislat.aimesimulator.data.HexCodec
import io.github.umislat.aimesimulator.data.IdmRouteMode
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

    @Test fun routeChangesRefreshCachedMetadataWithoutChangingCapturedBlocks() {
        read(profile.idm, 0x00, 0x82, 0x83)
        setGenerated(true)
        assertNull(packet(profile.idm, 0x00))
        val generated = CompatibilityIdm.generate(profile.idm)
        val result = read(generated, 0x00, 0x82, 0x83)
        assertEquals(profile.spad0, HexCodec.encode(result.blocks[0]))
        assertEquals(profile.idBlock, HexCodec.encode(result.blocks[1]))
        assertEquals(generated + "00F1000000014300", HexCodec.encode(result.blocks[2]))
        setGenerated(false)
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

    @Test fun oldFixedIdentityMigrationDoesNotExposeSelectedCard() {
        val preferences = context.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE)
        preferences.edit().putString("idm_route_mode", "FIXED_COMPATIBILITY").commit()
        assertNull(packet(profile.idm, 0x00))
        assertEquals(listOf(profile), CardStore(context).profiles())
        assertTrue(CardStore(context).compatibilityRetiredNoticePending())
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

    private fun packet(idm: String, vararg blocks: Int): ByteArray? =
        service.processNfcFPacket(FelicaCodec.readRequest(bytes(idm), blocks), null)

    private fun read(idm: String, vararg blocks: Int): FelicaCodec.ReadResult =
        requireNotNull(FelicaCodec.decodeReadResponse(requireNotNull(packet(idm, *blocks))))

    private fun bytes(value: String): ByteArray = requireNotNull(HexCodec.decode(value))

    private fun setGenerated(enabled: Boolean) {
        store.setIdmRouteMode(if (enabled) IdmRouteMode.GENERATED_COMPATIBILITY else IdmRouteMode.ORIGINAL)
    }
}
