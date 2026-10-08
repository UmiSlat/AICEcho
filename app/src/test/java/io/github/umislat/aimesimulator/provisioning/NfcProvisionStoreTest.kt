package io.github.umislat.aimesimulator.provisioning

import android.app.Application
import android.os.Bundle
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class NfcProvisionStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val uid = 10366

    @Before fun before() { PosixAtomicFileShadow.reset() }
    @After fun after() { PosixAtomicFileShadow.reset() }

    @Test fun aNewStoreReconstructsTheExactPlanWithoutChangingItsFile() {
        val data = savedPlan(uid)
        NfcProvisionStore(folder.root).save(data, uid, 185)
        val file = File(folder.root, "nfc-provision-plan.json")
        val original = file.readBytes()
        val read = checkNotNull(NfcProvisionStore(folder.root).read(uid))
        assertEquals(data.getString("token"), read.getString("token"))
        assertEquals(185, read.getInt("boot"))
        for (name in listOf("original", "proposed")) assertArrayEquals(data.getByteArray(name), read.getByteArray(name))
        assertArrayEquals(original, file.readBytes())
        read.getByteArray("original")!![0] = 0
        assertArrayEquals(data.getByteArray("original"), NfcProvisionStore(folder.root).read(uid)!!.getByteArray("original"))
    }

    @Test fun missingDamagedOrDifferentOwnersPlanIsUnavailableAndRetained() {
        val store = NfcProvisionStore(folder.root)
        assertNull(store.read(uid))
        store.save(savedPlan(uid), uid, 185)
        assertNull(store.read(uid + 1))
        val file = File(folder.root, "nfc-provision-plan.json")
        val original = file.readText()
        for ((key, replacement) in listOf(
            "version" to 2, "token" to "bad", "uid" to uid + 1,
            "originalHash" to "0".repeat(64), "proposedHash" to "0".repeat(64),
            "oldSystemCode" to "4001", "nfcid2" to "012E998877665544",
            "proposed" to Base64.encodeToString("<services/>".toByteArray(), Base64.NO_WRAP)
        )) {
            val corrupt = JSONObject(original).put(key, replacement).toString()
            file.writeText(corrupt)
            assertNull("Accepted corrupt $key", store.read(uid))
            assertEquals(corrupt, file.readText())
        }
        file.writeBytes(ByteArray(4 * NfcSnapshotArchive.MAX_XML + 1) { 32 })
        assertNull(store.read(uid))
        assertTrue(file.exists())
    }

    @Test fun invalidReplacementCannotDestroyTheSavedOriginal() {
        val store = NfcProvisionStore(folder.root)
        val data = savedPlan(uid)
        store.save(data, uid, 185)
        val before = File(folder.root, "nfc-provision-plan.json").readBytes()
        data.putString("proposedHash", "f".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { store.save(data, uid, 186) }
        assertArrayEquals(before, File(folder.root, "nfc-provision-plan.json").readBytes())
    }

    @Test fun resumedTokenReplacesTheEarlierRecord() {
        val store = NfcProvisionStore(folder.root)
        val data = savedPlan(uid)
        store.save(data, uid, 185)
        data.putString("token", "b".repeat(32))
        store.save(data, uid, 186)
        assertEquals("b".repeat(32), NfcProvisionStore(folder.root).read(uid)!!.getString("token"))
        assertEquals(186, store.read(uid)!!.getInt("boot"))
    }

    @Test @Config(sdk = [36]) fun silentAtomicRenameFailureIsNotReportedAsASavedPlan() {
        val store = NfcProvisionStore(folder.root)
        val data = savedPlan(uid)
        store.save(data, uid, 185)
        data.putString("token", "b".repeat(32))
        PosixAtomicFileShadow.failRename = true
        assertThrows(IllegalStateException::class.java) { store.save(data, uid, 186) }
        assertEquals("a".repeat(32), store.read(uid)!!.getString("token"))
    }

    companion object {
        fun savedPlan(uid: Int): Bundle {
            val original = ("<services><service component=\"${NfcProvisionPlan.Target.AIME.component}\" " +
                "uid=\"$uid\" system-code=\"4000\" nfcid2=\"02FE010203040506\"/></services>").toByteArray()
            val plan = NfcProvisionPlan.prepare(original, RestoreProbeArchive.sha256(original),
                NfcProvisionPlan.Target.AIME, uid, NfcProvisionPlan.Identity("4000", "02FE010203040506"),
                NfcProvisionPlan.Identity("88B4", "012E102030405060"))
            return Bundle().apply {
                putString("token", "a".repeat(32)); putInt("uid", uid)
                putString("nfcid2", plan.after.nfcid2)
                putString("oldSystemCode", plan.before.systemCode); putString("oldNfcid2", plan.before.nfcid2)
                putString("originalHash", plan.originalHash); putString("proposedHash", plan.proposedHash)
                putByteArray("original", plan.originalXml()); putByteArray("proposed", plan.proposedXml())
            }
        }
    }
}
