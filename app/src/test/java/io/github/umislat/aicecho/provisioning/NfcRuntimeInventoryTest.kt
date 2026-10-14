package io.github.umislat.aicecho.provisioning

import android.content.ComponentName
import android.util.Xml
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36])
class NfcRuntimeInventoryTest {
    @Test fun structuredReadDoesNotRequestDumpAndSummaryDoesNotExposeIdentity() {
        var dumps = 0
        val result = NfcRuntimeInventory.inspect(object : NfcRuntimeInventory.Reader {
            override fun structured() = listOf(record(dynamicKnown = true, dynamicId = ID))
            override fun dump(): String { dumps++; error("Unexpected fallback") }
        })
        assertEquals(NfcRuntimeInventory.State.READ, result.state)
        assertEquals(NfcRuntimeInventory.Source.STRUCTURED, result.source)
        assertEquals(1, result.previewRecords)
        assertTrue(result.dynamicFieldsKnown)
        assertEquals(0, dumps)
        assertFalse(result.toString().contains(ID))
        assertFalse(result.bundle().toString().contains(ID))
        assertEquals(result, NfcRuntimeInventory.Summary.decode(result.bundle()))
    }

    @Test fun fallbackPreservesNullObservationButCannotGenerateAFilePreview() {
        val result = NfcRuntimeInventory.inspect(object : NfcRuntimeInventory.Reader {
            override fun structured(): List<NfcRuntimeInventory.Record> = error("Not available")
            override fun dump() = """
                Registered HCE-F services for current user:
                User 0
                    ComponentInfo{test.example/test.example.Service} (Description: Synthetic) (UID: 10000)
                    System Code: NULL
                    NFCID2: $ID
                    T3tPmm: FFFFFFFFFFFFFFFF

                Preferred services (in order of importance):
            """.trimIndent()
        })
        assertEquals(NfcRuntimeInventory.Source.DUMPSYS, result.source)
        assertTrue(result.fallback)
        assertFalse(result.dynamicFieldsKnown)
        assertEquals(-1, result.previewRecords)
        assertEquals(1, result.records)
    }

    @Test fun missingHiddenFieldsStillYieldStructuredEffectiveValues() {
        val observed = NfcRuntimeInventory.readServiceInfo(EffectiveService())
        assertEquals(ID, observed.nfcid2)
        assertFalse(observed.dynamicKnown)
        assertNull(NfcRuntimeInventory.Observation(NfcRuntimeInventory.Source.STRUCTURED, listOf(observed)).previewXml())
    }

    @Test fun structuredReflectionDistinguishesAbsentOverrideFromAnEqualOverride() {
        val absent = NfcRuntimeInventory.readServiceInfo(DynamicService(null, null))
        val explicit = NfcRuntimeInventory.readServiceInfo(DynamicService("4000", ID))
        assertTrue(absent.dynamicKnown)
        assertNull(absent.dynamicSystemCode)
        assertNull(absent.dynamicNfcid2)
        assertEquals(absent.systemCode, explicit.systemCode)
        assertEquals(absent.nfcid2, explicit.nfcid2)
        val staticPreview = observation(absent).previewXml()!!
        val dynamicPreview = observation(explicit).previewXml()!!
        assertEquals(0, NfcSnapshotArchive.validateXml(staticPreview))
        assertEquals(1, NfcSnapshotArchive.validateXml(dynamicPreview))
        assertFalse(staticPreview.contentEquals(dynamicPreview))
    }

    @Test fun incompatibleDynamicFieldsDoNotDiscardValidEffectiveValues() {
        for (service in listOf(DynamicService("88B4", ID), DynamicService("wrong", ID),
            DynamicService("4000", "wrong"), PartialDynamicService())) {
            val result = NfcRuntimeInventory.inspect(object : NfcRuntimeInventory.Reader {
                override fun structured() = listOf(NfcRuntimeInventory.readServiceInfo(service))
                override fun dump(): String = error("Dump must not be needed")
            })
            assertEquals(NfcRuntimeInventory.State.READ, result.state)
            assertEquals(NfcRuntimeInventory.Source.STRUCTURED, result.source)
            assertFalse(result.dynamicFieldsKnown)
            assertEquals(-1, result.previewRecords)
        }
    }

    @Test fun mixedKnownAndUnknownRecordsCannotProducePreview() {
        val known = record(dynamicKnown = true, dynamicId = ID)
        val unknown = NfcRuntimeInventory.Record("test.other/.Service", 10001, "4000", ID, "FFFFFFFFFFFFFFFF")
        val result = observation(known, unknown).summary()
        assertFalse(result.dynamicFieldsKnown)
        assertEquals(-1, result.previewRecords)
    }

    @Test fun validRuntimeComponentOutsideTheFileSchemaStillRemainsObservable() {
        val system = NfcRuntimeInventory.Record("android/.Service", 1000, "4000", ID, "FFFFFFFFFFFFFFFF", true, "4000")
        val result = observation(system).summary()
        assertEquals(NfcRuntimeInventory.State.READ, result.state)
        assertEquals(1, result.records)
        assertEquals(-1, result.previewRecords)
    }

    @Test fun blockedStructuredReadHasBoundedWaitAndDoesNotQueueMoreBinderCalls() {
        val gate = NfcRuntimeReadGate(100)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            assertThrows(TimeoutException::class.java) {
                gate.read {
                    calls.incrementAndGet(); started.countDown()
                    release.await()
                    finished.countDown()
                    listOf(record())
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(3) {
                assertThrows(IllegalStateException::class.java) { gate.read { calls.incrementAndGet(); emptyList() } }
            }
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var recovered = false
        while (!recovered && System.nanoTime() < deadline) {
            recovered = runCatching { gate.read { emptyList() }.isEmpty() }.getOrDefault(false)
            if (!recovered) Thread.sleep(5)
        }
        assertTrue(recovered)
    }

    @Test fun previewKeepsIdOnlyOverrideAndDoesNotFreezeStaticSystemCode() {
        val bytes = observation(record(dynamicKnown = true, dynamicId = ID)).previewXml()!!
        val xml = bytes.toString(Charsets.UTF_8)
        assertTrue(xml.contains("nfcid2=\"$ID\""))
        assertFalse(xml.contains("system-code="))
        assertFalse(xml.contains("pmm", true))
        assertFalse(xml.contains("description="))
    }

    @Test fun previewPreservesNullAndDoesNotInventAnIdOverride() {
        val bytes = observation(record(sc = "NULL", dynamicKnown = true, dynamicSc = "NULL")).previewXml()!!
        val xml = bytes.toString(Charsets.UTF_8)
        assertTrue(xml.contains("system-code=\"NULL\""))
        assertFalse(xml.contains("nfcid2="))
    }

    @Test fun emptyOrMultiUserCacheIsNotACompleteBaseline() {
        assertNull(NfcRuntimeInventory.Observation(NfcRuntimeInventory.Source.STRUCTURED, emptyList()).previewXml())
        val multi = NfcRuntimeInventory.Observation(NfcRuntimeInventory.Source.STRUCTURED,
            listOf(record(dynamicKnown = true), record(uid = 1010000, dynamicKnown = true)))
        assertEquals(2, multi.summary().users)
        assertNull(multi.previewXml())
        assertEquals(-1, multi.summary().previewRecords)
    }

    @Test fun invalidRecordsAndDynamicEffectiveMismatchAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { record(id = "1234") }
        assertThrows(IllegalArgumentException::class.java) { record(dynamicKnown = false, dynamicId = ID) }
        assertThrows(IllegalArgumentException::class.java) { record(sc = "4000", dynamicKnown = true, dynamicSc = "88B4") }
        assertThrows(IllegalArgumentException::class.java) { observation(record(), record()) }
        assertThrows(IllegalArgumentException::class.java) {
            NfcRuntimeInventory.Observation(NfcRuntimeInventory.Source.DUMPSYS, listOf(record(dynamicKnown = true)))
        }
    }

    @Test fun failuresAreExplicitAndNeverLeakExceptionOrPartialIdentity() {
        val failed = NfcRuntimeInventory.inspect(object : NfcRuntimeInventory.Reader {
            override fun structured(): List<NfcRuntimeInventory.Record> = error(ID)
            override fun dump() = "Permission denied $ID"
        })
        assertEquals(NfcRuntimeInventory.State.FAILED, failed.state)
        assertEquals(NfcRuntimeInventory.Failure.UNAVAILABLE, failed.failure)
        assertNull(failed.source)
        assertFalse(failed.toString().contains(ID))
        assertEquals(0, failed.records)
    }

    @Test fun recordAndObservationDescriptionsAreRedacted() {
        val value = record()
        assertFalse(value.toString().contains(ID))
        assertFalse(observation(value).toString().contains(ID))
    }

    @Test fun privatePairedDumpMatchesFileFieldsWhenExplicitlyProvided() {
        val dumpPath = System.getenv("AICECHO_PRIVATE_NFC_DUMP") ?: return
        val xmlPath = requireNotNull(System.getenv("AICECHO_PRIVATE_NFC_XML"))
        val dumpFile = File(dumpPath)
        require(dumpFile.length() <= 1024 * 1024)
        val entries = NfcFServiceDump.parse(dumpFile.readText())
        val xml = File(xmlPath).readBytes()
        val count = NfcSnapshotArchive.validateXml(xml)
        assertEquals(count, entries.size)
        val parser = Xml.newPullParser().apply { setInput(xml.inputStream(), "UTF-8") }
        var matched = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.depth != 2) continue
            val component = ComponentName.unflattenFromString(parser.getAttributeValue(null, "component"))!!.flattenToString()
            val uid = parser.getAttributeValue(null, "uid").toInt()
            val entry = entries.single { it.component == component && it.uid == uid }
            // Boolean assertions keep private identifiers out of assertion output.
            assertTrue(entry.systemCode == parser.getAttributeValue(null, "system-code"))
            assertTrue(entry.nfcid2 == parser.getAttributeValue(null, "nfcid2"))
            matched++
        }
        assertTrue(matched > 0)
        assertEquals(count, matched)
    }

    open class EffectiveService {
        fun getComponent() = ComponentName("test.example", "test.example.Service")
        fun getUid() = 10000
        fun getSystemCode() = "4000"
        fun getNfcid2() = ID
        fun getT3tPmm() = "FFFFFFFFFFFFFFFF"
    }
    class DynamicService(@JvmField val mDynamicSystemCode: String?, @JvmField val mDynamicNfcid2: String?) : EffectiveService()
    class PartialDynamicService(@JvmField val mDynamicSystemCode: String? = "4000") : EffectiveService()

    private fun observation(vararg records: NfcRuntimeInventory.Record) =
        NfcRuntimeInventory.Observation(NfcRuntimeInventory.Source.STRUCTURED, records.toList())
    private fun record(uid: Int = 10000, sc: String = "4000", id: String = ID,
        dynamicKnown: Boolean = false, dynamicSc: String? = null, dynamicId: String? = null) =
        NfcRuntimeInventory.Record("test.example/.Service", uid, sc, id, "FFFFFFFFFFFFFFFF", dynamicKnown, dynamicSc, dynamicId)

    companion object { private const val ID = "02FE001122334455" }
}
