package io.github.umislat.aimesimulator.provisioning

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.BadParcelableException
import android.util.Xml
import io.github.umislat.aimesimulator.provisioning.NfcRuntimeBaseline.Reason
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class)
class NfcRuntimeBaselineTest {
    @Test fun productionEntryRejectsNonShellBeforeAnyBinderOrCommand() {
        for (uid in listOf(0, 10000)) {
            ShadowProcess.setUid(uid)
            assertReason(Reason.RUNTIME_BASELINE_SHELL) { NfcRuntimeBaseline.capture(RuntimeEnvironment.getApplication()) }
        }
    }

    @Test @Config(sdk = [29]) fun legacyParcelTransportIsNotSilentlyAccepted() {
        ShadowProcess.setUid(2000)
        assertReason(Reason.RUNTIME_BASELINE_PLATFORM) { NfcRuntimeBaseline.capture(RuntimeEnvironment.getApplication()) }
    }

    @Test fun uniqueActiveOwnerAndMatchedCacheProduceDynamicXml() {
        val reader = FakeReader()
        val xml = NfcRuntimeBaseline.capture(reader)
        assertEquals(1, NfcSnapshotArchive.validateXml(xml))
        assertEquals(mapOf(COMPONENT to mapOf("component" to COMPONENT, "uid" to "10000",
            "system-code" to "4000", "nfcid2" to ID)), attributes(xml))
        assertEquals(listOf("users", "current", "unlocked", "installed", "runtime",
            "installed", "runtime", "users", "current", "unlocked"), reader.calls)
    }

    @Test fun staticFieldsAreNotFrozenAndPartialOverridesAndNullArePreserved() {
        val records = listOf(
            record(component = "test.static/.Service", dynamicSc = null, dynamicId = null),
            record(component = "test.idonly/.Service", uid = 10001, dynamicSc = null),
            record(component = "test.disabled/.Service", uid = 10002, sc = "NULL", dynamicSc = "NULL", dynamicId = null),
            record(component = "test.equal/.Service", uid = 10003)
        )
        val xml = NfcRuntimeBaseline.capture(FakeReader(records))
        val result = attributes(xml)
        assertEquals(3, result.size)
        assertFalse(result.containsKey("test.static/test.static.Service"))
        assertFalse(result.getValue("test.idonly/test.idonly.Service").containsKey("system-code"))
        assertEquals("NULL", result.getValue("test.disabled/test.disabled.Service")["system-code"])
        assertFalse(result.getValue("test.disabled/test.disabled.Service").containsKey("nfcid2"))
        assertEquals("4000", result.getValue("test.equal/test.equal.Service")["system-code"])
        assertFalse(xml.toString(Charsets.UTF_8).contains("pmm", true))
    }

    @Test fun initializedNonemptyStaticCacheCanProveAnEmptyDynamicTable() {
        val xml = NfcRuntimeBaseline.capture(FakeReader(listOf(record(dynamicSc = null, dynamicId = null))))
        assertEquals(0, NfcSnapshotArchive.validateXml(xml))
    }

    @Test fun outputIsIndependentOfServiceOrderAndShortComponentForm() {
        val first = record(component = "test.zed/.Service")
        val second = record(component = "test.alpha/test.alpha.Service", uid = 10001)
        val reader = FakeReader(listOf(first, second))
        var reads = 0
        reader.runtimeRead = { observation(if (reads++ == 0) listOf(first, second) else listOf(second, first)) }
        val xml = NfcRuntimeBaseline.capture(reader)
        assertArrayEquals(xml, NfcRuntimeBaseline.capture(FakeReader(listOf(second, first))))
    }

    @Test fun disabledInactiveOrAdditionalUsersAreNotTreatedAsAbsentDiskState() {
        for (ids in listOf(listOf(0, 10), listOf(0, 999), listOf(10), listOf(0, 11, 999))) {
            val reader = FakeReader().apply { usersRead = { ids } }
            assertReason(Reason.RUNTIME_BASELINE_USER_SCOPE) { NfcRuntimeBaseline.capture(reader) }
            assertEquals(listOf("users"), reader.calls)
        }
    }

    @Test fun missingDuplicateNegativeAndOutOfRangeUserIdsAreRejected() {
        for (ids in listOf(emptyList(), listOf(0, 0), listOf(-1), listOf(21474), List(129) { it })) {
            assertReason(Reason.RUNTIME_BASELINE_USERS_UNAVAILABLE) {
                NfcRuntimeBaseline.capture(FakeReader().apply { usersRead = { ids } })
            }
        }
    }

    @Test fun lockedOrBackgroundOwnerCannotYieldBaseline() {
        assertReason(Reason.RUNTIME_BASELINE_USER_INACTIVE) {
            NfcRuntimeBaseline.capture(FakeReader().apply { currentRead = { 10 } })
        }
        assertReason(Reason.RUNTIME_BASELINE_USER_INACTIVE) {
            NfcRuntimeBaseline.capture(FakeReader().apply { unlockedRead = { false } })
        }
    }

    @Test fun emptyRuntimeAndEmptyInstalledListsDoNotProveInitialization() {
        assertReason(Reason.RUNTIME_BASELINE_CACHE_EMPTY) { NfcRuntimeBaseline.capture(FakeReader(emptyList())) }
        assertReason(Reason.RUNTIME_BASELINE_CACHE_EMPTY) {
            NfcRuntimeBaseline.capture(FakeReader().apply { runtimeRead = { observation(emptyList()) } })
        }
    }

    @Test fun missingExtraAndUidMismatchedCacheRecordsAreRejected() {
        val other = record(component = "test.other/.Service", uid = 10001)
        for (runtime in listOf(listOf(other), listOf(record(), other), listOf(record(uid = 10001)))) {
            assertReason(Reason.RUNTIME_BASELINE_CACHE_MISMATCH) {
                NfcRuntimeBaseline.capture(FakeReader().apply { runtimeRead = { observation(runtime) } })
            }
        }
        assertReason(Reason.RUNTIME_BASELINE_CACHE_MISMATCH) {
            NfcRuntimeBaseline.capture(FakeReader(listOf(record(), other)).apply { runtimeRead = { observation(listOf(record())) } })
        }
    }

    @Test fun duplicateInstalledComponentsAndExcessiveResultsAreRejected() {
        val service = NfcRuntimeBaseline.InstalledService(COMPONENT, 10000)
        for (services in listOf(listOf(service, service), List(129) { service })) {
            assertReason(Reason.RUNTIME_BASELINE_SERVICES_UNAVAILABLE) {
                NfcRuntimeBaseline.capture(FakeReader().apply { installedRead = { services } })
            }
        }
    }

    @Test fun runtimeEntryForOtherUserIsRejectedEvenWithAnOwnerCache() {
        assertReason(Reason.RUNTIME_BASELINE_USER_SCOPE) {
            NfcRuntimeBaseline.capture(FakeReader().apply { runtimeRead = { observation(listOf(record(uid = 1010000))) } })
        }
    }

    @Test fun dumpFallbackAndUnknownDynamicFieldsCannotAuthorizeReconstruction() {
        val effective = record(known = false, dynamicSc = null, dynamicId = null)
        assertReason(Reason.RUNTIME_BASELINE_EFFECTIVE_ONLY) {
            NfcRuntimeBaseline.capture(FakeReader().apply {
                runtimeRead = { NfcRuntimeInventory.Observation(NfcRuntimeInventory.Source.DUMPSYS, listOf(effective)) }
            })
        }
        assertReason(Reason.RUNTIME_BASELINE_DYNAMIC_UNKNOWN) {
            NfcRuntimeBaseline.capture(FakeReader().apply { runtimeRead = { observation(listOf(effective)) } })
        }
    }

    @Test fun unresolvedRandomIdBlocksWhileAssignedRandomIdIsPreserved() {
        assertReason(Reason.RUNTIME_BASELINE_RANDOM_PENDING) {
            NfcRuntimeBaseline.capture(FakeReader(listOf(record(id = "RANDOM", dynamicId = "RANDOM"))))
        }
        val xml = NfcRuntimeBaseline.capture(FakeReader(listOf(record(dynamicSc = null))))
        assertEquals(ID, attributes(xml).getValue(COMPONENT)["nfcid2"])
    }

    @Test fun unsupportedXmlRecordCannotDisappearSilently() {
        assertReason(Reason.RUNTIME_BASELINE_XML_UNSUPPORTED) {
            NfcRuntimeBaseline.capture(FakeReader(listOf(record(component = "android/.Service", uid = 1000))))
        }
    }

    @Test fun scopeMustStillBeCompleteAfterCollection() {
        var reads = 0
        assertReason(Reason.RUNTIME_BASELINE_USER_SCOPE) {
            NfcRuntimeBaseline.capture(FakeReader().apply { usersRead = { if (reads++ == 0) listOf(0) else listOf(0, 999) } })
        }
        var unlocks = 0
        assertReason(Reason.RUNTIME_BASELINE_USER_INACTIVE) {
            NfcRuntimeBaseline.capture(FakeReader().apply { unlockedRead = { unlocks++ == 0 } })
        }
    }

    @Test fun serviceSetChangeDuringCaptureIsNotAcceptedAsAStableBaseline() {
        val first = record()
        val second = record(component = "test.other/.Service", uid = 10001)
        var serviceReads = 0
        var runtimeReads = 0
        val reader = FakeReader().apply {
            installedRead = { listOf(if (serviceReads++ == 0) first else second).map { installed(it) } }
            runtimeRead = { observation(listOf(if (runtimeReads++ == 0) first else second)) }
        }
        assertReason(Reason.RUNTIME_BASELINE_CHANGED) { NfcRuntimeBaseline.capture(reader) }
    }

    @Test fun dynamicChangeIncludingEqualValueBecomingStaticIsDetected() {
        for (second in listOf(record(sc = "88B4", dynamicSc = "88B4"), record(dynamicSc = null),
            record(pmm = "00F1000000014300"))) {
            var reads = 0
            assertReason(Reason.RUNTIME_BASELINE_CHANGED) {
                NfcRuntimeBaseline.capture(FakeReader().apply {
                    runtimeRead = { observation(listOf(if (reads++ == 0) record() else second)) }
                })
            }
        }
    }

    @Test fun readFailuresAreFixedReasonsWithoutExceptionTextOrCause() {
        assertReason(Reason.RUNTIME_BASELINE_USERS_UNAVAILABLE) {
            NfcRuntimeBaseline.capture(FakeReader().apply { usersRead = { error(ID) } })
        }
        assertReason(Reason.RUNTIME_BASELINE_SERVICES_UNAVAILABLE) {
            NfcRuntimeBaseline.capture(FakeReader().apply { installedRead = { throw BadParcelableException(ID) } })
        }
        assertReason(Reason.RUNTIME_BASELINE_UNAVAILABLE) {
            NfcRuntimeBaseline.capture(FakeReader().apply { runtimeRead = { error(ID) } })
        }
    }

    @Test fun userInfoNamesAreIgnoredIncludingDelimiterAndFakeRowText() {
        val names = listOf("Owner", "name:12:}", "\nUserInfo{999:Injected:13} running\n", "\u4e3b\u4eba\t{}\r\n")
        for (name in names) {
            assertEquals(listOf(0), NfcRuntimeBaseline.decodeUserIds(listOf(UserInfo(0, name))))
            assertEquals(listOf(0, 999), NfcRuntimeBaseline.decodeUserIds(listOf(UserInfo(0, name), UserInfo(999, name))))
        }
    }

    @Test fun malformedUserBinderValuesCannotBeCoercedToIds() {
        for (value in listOf(null, "Users:\nUserInfo{0:Owner:13}", emptyList<Any>(), listOf(null),
            listOf(UserInfo(-1, "")), listOf(UserInfo(0, ""), UserInfo(0, "")), listOf(WrongUserInfo()),
            listOf(UserInfo(21474, "")), List(129) { UserInfo(it, "") })) {
            assertReason(Reason.RUNTIME_BASELINE_USERS_UNAVAILABLE) { NfcRuntimeBaseline.decodeUserIds(value) }
        }
    }

    @Test fun currentUserParserRequiresOneCompleteSuccessfulAsciiValue() {
        for (text in listOf("0", "0\n", "0\r\n")) {
            assertEquals(0, NfcRuntimeBaseline.decodeCurrentUser(command(text)))
        }
        assertEquals(999, NfcRuntimeBaseline.decodeCurrentUser(command("999\n")))
        for (value in listOf(command(""), command(" 0\n"), command("00\n"), command("-1\n"),
            command("21474\n"), command("0\n0\n"), command("0\nError"), command("0\n", uncertain = true),
            command("0\n", exitCode = 1), command("0".repeat(4097)), command("\u0660\n"))) {
            assertReason(Reason.RUNTIME_BASELINE_USERS_UNAVAILABLE) { NfcRuntimeBaseline.decodeCurrentUser(value) }
        }
    }

    @Test fun installedBinderValuesRequireValidServicesAndOwnerUids() {
        val values = NfcRuntimeBaseline.decodeInstalledServices(listOf(resolve()))
        assertEquals(COMPONENT, values.single().component)
        assertEquals(10000, values.single().uid)
        assertFalse(values.single().toString().contains(COMPONENT))
        val unbound = resolve().apply { serviceInfo.permission = null }
        val otherUser = resolve().apply { serviceInfo.applicationInfo.uid = 1010000 }
        val wrongPackage = resolve().apply { serviceInfo.applicationInfo.packageName = "test.other" }
        val noApp = resolve().apply { serviceInfo.applicationInfo = null }
        for (value in listOf(null, "1 service found", listOf(null), listOf(ResolveInfo()), listOf(unbound),
            listOf(otherUser), listOf(wrongPackage), listOf(noApp), listOf(resolve(), resolve()), List(129) { resolve() })) {
            assertReason(Reason.RUNTIME_BASELINE_SERVICES_UNAVAILABLE) { NfcRuntimeBaseline.decodeInstalledServices(value) }
        }
    }

    @Test fun inheritedListAccessorIsReadableButPartialParcelFailureNeverBecomesAList() {
        assertEquals(COMPONENT, NfcRuntimeBaseline.decodeServiceSlice(ServiceSlice(listOf(resolve()))).single().component)
        for (slice in listOf(null, Any(), BrokenServiceSlice())) {
            assertReason(Reason.RUNTIME_BASELINE_SERVICES_UNAVAILABLE) { NfcRuntimeBaseline.decodeServiceSlice(slice) }
        }
    }

    @Test fun aTimedOutCaptureDoesNotAccumulateBinderWorkers() {
        val gate = NfcRuntimeBaseline.ReadGate(100)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            assertReason(Reason.RUNTIME_BASELINE_TIMEOUT) {
                gate.read {
                    calls.incrementAndGet(); entered.countDown()
                    release.await(); finished.countDown()
                    byteArrayOf(1)
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(3) {
                assertReason(Reason.RUNTIME_BASELINE_PENDING) { gate.read { calls.incrementAndGet(); byteArrayOf() } }
            }
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var recovered = false
        while (!recovered && System.nanoTime() < deadline) {
            recovered = runCatching { gate.read { byteArrayOf(2) }.contentEquals(byteArrayOf(2)) }.getOrDefault(false)
            if (!recovered) Thread.sleep(5)
        }
        assertTrue(recovered)
    }

    @Test fun gatePreservesTypedFailureButRedactsArbitraryWorkerException() {
        val gate = NfcRuntimeBaseline.ReadGate()
        assertReason(Reason.RUNTIME_BASELINE_USER_SCOPE) {
            gate.read { throw NfcRuntimeBaseline.Failure(Reason.RUNTIME_BASELINE_USER_SCOPE) }
        }
        assertReason(Reason.RUNTIME_BASELINE_UNAVAILABLE) { gate.read { error(ID) } }
    }

    class UserInfo(@JvmField val id: Int, @JvmField val name: String)
    class WrongUserInfo(@JvmField val id: String = "0")
    private open class SliceBase(private val values: List<ResolveInfo>) { fun getList() = values }
    private class ServiceSlice(values: List<ResolveInfo>) : SliceBase(values)
    private class BrokenServiceSlice { fun getList(): List<ResolveInfo> = throw BadParcelableException(ID) }

    private class FakeReader(records: List<NfcRuntimeInventory.Record> = listOf(record())) : NfcRuntimeBaseline.Reader {
        val calls = mutableListOf<String>()
        var usersRead: () -> List<Int> = { listOf(0) }
        var currentRead: () -> Int = { 0 }
        var unlockedRead: () -> Boolean = { true }
        var installedRead: () -> List<NfcRuntimeBaseline.InstalledService> = { records.map { installed(it) } }
        var runtimeRead: () -> NfcRuntimeInventory.Observation = { observation(records) }
        override fun users(): List<Int> { calls += "users"; return usersRead() }
        override fun currentUser(): Int { calls += "current"; return currentRead() }
        override fun ownerUnlocked(): Boolean { calls += "unlocked"; return unlockedRead() }
        override fun installedServices(): List<NfcRuntimeBaseline.InstalledService> { calls += "installed"; return installedRead() }
        override fun runtime(): NfcRuntimeInventory.Observation { calls += "runtime"; return runtimeRead() }
    }

    private fun assertReason(expected: Reason, action: () -> Any?) {
        val result = assertThrows(NfcRuntimeBaseline.Failure::class.java) { action() }
        assertEquals(expected, result.reason)
        assertEquals(expected.name, result.message)
        assertNull(result.cause)
        assertFalse(result.toString().contains(ID))
    }

    private fun attributes(xml: ByteArray): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, Map<String, String>>()
        val parser = Xml.newPullParser().apply { setInput(xml.inputStream(), "UTF-8") }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.depth != 2) continue
            val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
            result[attrs.getValue("component")] = attrs
        }
        return result
    }

    private fun command(text: String, exitCode: Int = 0, uncertain: Boolean = false) =
        ProbeProcess.Result(exitCode, text.toByteArray(Charsets.UTF_8), uncertain)

    private fun resolve() = ResolveInfo().apply {
        serviceInfo = ServiceInfo().apply {
            packageName = "test.example"; name = "test.example.Service"
            permission = Manifest.permission.BIND_NFC_SERVICE
            applicationInfo = ApplicationInfo().apply { packageName = "test.example"; uid = 10000 }
        }
    }

    companion object {
        private const val COMPONENT = "test.example/test.example.Service"
        private const val ID = "02FE001122334455"
        private fun record(component: String = COMPONENT, uid: Int = 10000, sc: String = "4000", id: String = ID,
            pmm: String = "FFFFFFFFFFFFFFFF", known: Boolean = true, dynamicSc: String? = "4000", dynamicId: String? = ID) =
            NfcRuntimeInventory.Record(component, uid, sc, id, pmm, known, dynamicSc, dynamicId)
        private fun installed(value: NfcRuntimeInventory.Record) = NfcRuntimeBaseline.InstalledService(value.component, value.uid)
        private fun observation(records: List<NfcRuntimeInventory.Record>) =
            NfcRuntimeInventory.Observation(NfcRuntimeInventory.Source.STRUCTURED, records)
    }
}
