package io.github.umislat.aimesimulator.provisioning

import android.app.Application
import android.os.Bundle
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream

@RunWith(BundledArchiveTestRunner::class)
@Config(sdk = [29, 36], application = Application::class)
class NfcSnapshotTest {
    @get:Rule val folder = TemporaryFolder()
    private val token = "a".repeat(32)
    private val xml = """<?xml version="1.0" encoding="utf-8"?><services><service component="example.lab/.Host" uid="10001" system-code="4000" nfcid2="02FE6C6162323031"/></services>""".toByteArray()
    private val prefix = "apps/com.android.nfc/"
    private val identity = prefix + "f/dynamic_systemcode_nfcid2.xml"
    private val metadata get() = NfcBackupFixtures.metadata()
    private val signer get() = checkNotNull(javaClass.getResourceAsStream("/miui-nfc-signer.hex"))
        .bufferedReader().use { it.readText().trim() }
    private fun manifest(certificate: String = signer) = "1\ncom.android.nfc\n36\n36\n\n0\n1\n$certificate\n".toByteArray()
    private fun archive(entries: List<Pair<String, ByteArray>> = listOf(prefix + "_manifest" to manifest(), identity to xml),
        amend: (TarArchiveEntry) -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ANDROID BACKUP\n5\n0\nnone\n".toByteArray())
        TarArchiveOutputStream(out, 512).use { tar ->
            for ((name, bytes) in entries) {
                val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong(); amend(this) }
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        return out.toByteArray()
    }
    private fun rejects(raw: ByteArray) { assertThrows(Exception::class.java) { NfcSnapshotArchive.parse(raw, metadata) } }

    @Test fun testsUseThePublishedParserNotAndroidAllsOlderCopy() {
        assertEquals("1.28.0", org.apache.commons.compress.archivers.tar.TarArchiveInputStream::class.java.`package`?.implementationVersion)
    }

    @Test fun preservesCompleteXmlBytesAndValidatesSigner() {
        val raw = archive()
        val parsed = NfcSnapshotArchive.parse(raw, metadata)
        assertArrayEquals(xml, parsed.xml)
        assertEquals(1, parsed.records)
        assertEquals(NfcStorageDomain.CE, parsed.domain)
        assertEquals(RestoreProbeArchive.sha256(raw), parsed.archiveHash)
        assertEquals(RestoreProbeArchive.sha256(xml), parsed.identityHash)
    }

    @Test fun rejectsMissingIdentityOrManifestAndWrongSigner() {
        rejects(archive(listOf(prefix + "_manifest" to manifest())))
        rejects(archive(listOf(identity to xml)))
        rejects(archive(listOf(prefix + "_manifest" to manifest("0011"), identity to xml)))
    }

    @Test fun rejectsDuplicateAndAmbiguousDeIdentity() {
        val base = listOf(prefix + "_manifest" to manifest(), identity to xml)
        rejects(archive(base + (identity to xml)))
        rejects(archive(base + (prefix + "d_f/dynamic_systemcode_nfcid2.xml" to xml)))
    }

    @Test fun readsTheSingleDeviceProtectedIdentityWithoutChangingItsBytes() {
        val raw = archive(listOf(prefix + "_manifest" to manifest(), NfcStorageDomain.DE.archivePath to xml))
        val parsed = NfcSnapshotArchive.parse(raw, metadata)
        assertEquals(NfcStorageDomain.DE, parsed.domain)
        assertArrayEquals(xml, parsed.xml)
        assertEquals(1, parsed.records)
        assertEquals(RestoreProbeArchive.sha256(raw), parsed.archiveHash)
    }

    @Test fun identityInAnyOtherAcceptedArchiveDomainIsNotTreatedAsCeOrDeFiles() {
        for (domain in listOf("r", "d_r", "sp", "d_sp", "db", "d_db", "ef")) {
            rejects(archive(listOf(prefix + "_manifest" to manifest(),
                prefix + domain + "/dynamic_systemcode_nfcid2.xml" to xml)))
        }
    }

    @Test fun rejectsForeignPackagesTraversalAndUnreviewedDomains() {
        for (name in listOf("apps/example.other/f/test", prefix + "f/../other", prefix + "f//other",
            prefix + "f/./other", prefix + "unknown/data")) {
            rejects(archive(listOf(prefix + "_manifest" to manifest(), identity to xml, name to byteArrayOf(1))))
        }
    }

    @Test fun rejectsOversizeEntriesArchiveAndInvalidPadding() {
        rejects(ByteArray(NfcSnapshotArchive.MAX_ARCHIVE + 1))
        rejects(archive(listOf(prefix + "_manifest" to manifest(), identity to ByteArray(NfcSnapshotArchive.MAX_XML + 1))))
        rejects(archive().also { it[it.lastIndex] = 1 })
        rejects(archive().dropLast(512).toByteArray())
        rejects(archive().dropLast(1).toByteArray())
    }

    @Test fun rejectsHiddenExtendedHeadersAndInvalidChecksum() {
        rejects(archive(amend = { it.addPaxHeader("comment", "not reviewed") }))
        val raw = archive()
        val offset = raw.indexOfFirst { it == 0.toByte() }
        raw[offset + 10] = 55
        rejects(raw)
    }

    @Test fun rejectsLinksEvenWhenTheyPointAtIdentity() {
        val out = ByteArrayOutputStream()
        out.write("ANDROID BACKUP\n5\n0\nnone\n".toByteArray())
        TarArchiveOutputStream(out, 512).use { tar ->
            val entry = TarArchiveEntry(identity, '2'.code.toByte()).apply { linkName = "/data/example" }
            tar.putArchiveEntry(entry); tar.closeArchiveEntry(); tar.finish()
        }
        rejects(out.toByteArray())
    }

    @Test fun rejectsInvalidCompressionEncryptionAndWrongPackageManifest() {
        val raw = archive()
        for ((from, to) in listOf("BACKUP\n5\n0\nnone" to "BACKUP\n5\n1\nnone",
            "\nnone\n" to "\naes!\n", "apps/com.android.nfc" to "apps/com.android.foo")) {
            val header = raw.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            val changed = header.replace(from, to).toByteArray()
            assertEquals(header.toByteArray().size, changed.size)
            rejects(raw.copyOf().also { changed.copyInto(it) })
        }
    }

    @Test fun xmlRejectsDuplicateCanonicalComponentsAndUnknownAttributes() {
        val service = """<service component="example.lab/.Host" uid="10001" nfcid2="RANDOM"/>"""
        for (bad in listOf("<services>$service${service.replace("/.Host", "/example.lab.Host")}</services>",
            "<services>${service.replace("uid=", "other=\"1\" uid=")}</services>",
            "<services>${service.replace("10001", "-1")}</services>",
            "<services>${service.replace("10001", "9999999999")}</services>",
            "<services>${service.replace("RANDOM", "123")}</services>")) {
            assertThrows(bad, Exception::class.java) { NfcSnapshotArchive.validateXml(bad.toByteArray()) }
        }
    }

    @Test fun xmlRejectsDoctypeNamespacesCommentsAndUnclosedRoot() {
        for (bad in listOf("<!DOCTYPE services [<!ENTITY x SYSTEM 'file:///data/test'>]><services>&x;</services>",
            "<services xmlns='x'/>", "<services xmlns:x='x'/>", "<services><!-- test --></services>",
            "<services><?action test?></services>", "<services>unexpected</services>", "<services>",
            "<services/><services/>")) {
            assertThrows(bad, Exception::class.java) { NfcSnapshotArchive.validateXml(bad.toByteArray()) }
        }
        assertThrows(Exception::class.java) { NfcSnapshotArchive.validateXml(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
        assertEquals(0, NfcSnapshotArchive.validateXml("<services/>".toByteArray()))
    }

    @Test fun localOptInRecordedArchiveMatchesIndependentXmlExactly() {
        val path = System.getenv("AIMESIM_PRIVATE_NFC_EXPORT")
        assumeTrue(!path.isNullOrBlank())
        val source = File(checkNotNull(path))
        // The old private evidence has a five-line OEM envelope. Production accepts only AOSP.
        val input = source.readBytes().inputStream()
        repeat(5) { while (true) { val byte = input.read(); check(byte >= 0); if (byte == 10) break } }
        val parsed = NfcSnapshotArchive.parse(input.readBytes(), metadata)
        assertTrue(parsed.xml.contentEquals(File(source.parentFile, "snapshot/identity.xml").readBytes()))
        assertEquals(2, parsed.records)
    }

    @Test fun successfulWorkflowReportsPhasesAndErasesRawArchive() {
        val backend = FakeBackend()
        val seen = mutableListOf<NfcSnapshotWorkflow.State>()
        val result = NfcSnapshotWorkflow(backend).run(token) { seen.add(it.state) }
        assertEquals(listOf(NfcSnapshotWorkflow.State.EXPORTING, NfcSnapshotWorkflow.State.VERIFYING), seen)
        assertEquals(NfcSnapshotWorkflow.State.CAPTURED, result.state)
        assertArrayEquals(xml, checkNotNull(result.capture).parsed.xml)
        assertTrue(backend.bytes.all { it == 0.toByte() })
        assertEquals(1, backend.exports)
    }

    @Test fun preflightFailureNeverExportsAndInterruptedExportRemainsUnknown() {
        val before = FakeBackend().apply { preflightFailure = true }
        assertEquals(NfcSnapshotWorkflow.State.FAILED, NfcSnapshotWorkflow(before).run(token) {}.state)
        assertEquals(0, before.exports)
        val interrupted = FakeBackend().apply { exportFailure = true }
        val result = NfcSnapshotWorkflow(interrupted).run(token) {}
        assertEquals(NfcSnapshotWorkflow.State.UNKNOWN, result.state)
        assertEquals("", result.detail)
        assertEquals(1, interrupted.exports)
    }

    @Test fun completedButInvalidExportIsRejectedWithoutRetry() {
        val backend = FakeBackend().apply { bytes = byteArrayOf(1, 2, 3) }
        assertEquals(NfcSnapshotWorkflow.State.REJECTED, NfcSnapshotWorkflow(backend).run(token) {}.state)
        assertTrue(backend.bytes.all { it == 0.toByte() })
        assertEquals(1, backend.exports)
    }

    @Test fun changedBootAfterExportCannotProduceCapturedState() {
        val backend = FakeBackend().apply { stampFailure = true }
        assertEquals(NfcSnapshotWorkflow.State.REJECTED, NfcSnapshotWorkflow(backend).run(token) {}.state)
    }

    private inner class FakeBackend : NfcSnapshotWorkflow.Backend {
        var bytes = archive()
        var exports = 0
        var preflightFailure = false
        var exportFailure = false
        var stampFailure = false
        override fun preflight() { check(!preflightFailure) }
        override fun metadata() = this@NfcSnapshotTest.metadata
        override fun export(): ByteArray { exports++; check(!exportFailure); return bytes }
        override fun stamp(parsed: NfcSnapshotArchive.Parsed): NfcSnapshotWorkflow.Capture {
            check(!stampFailure)
            return NfcSnapshotWorkflow.Capture(parsed, 4, 100, 1000, "build")
        }
    }

    @Test fun standardCompressedArchiveAndExtraFullBackupFilesAreSupported() {
        val standard = archive(listOf(prefix + "_manifest" to manifest(), prefix + "f/" to byteArrayOf(), identity to xml,
            prefix + "db/nfc.db" to byteArrayOf(1, 2), prefix + "sp/preferences.xml" to "<map/>".toByteArray()))
        val header = "ANDROID BACKUP\n5\n0\nnone\n".toByteArray()
        val compressed = ByteArrayOutputStream().apply {
            write("ANDROID BACKUP\n5\n1\nnone\n".toByteArray())
            DeflaterOutputStream(this).use { it.write(standard, header.size, standard.size - header.size) }
        }.toByteArray()
        assertArrayEquals(xml, NfcSnapshotArchive.parse(compressed, metadata).xml)
    }

    @Test fun missingIdentityAndCancelledExportHaveActionableResults() {
        for ((bytes, reason) in listOf(byteArrayOf() to "EMPTY_BACKUP",
            archive(listOf(prefix + "_manifest" to manifest())) to "IDENTITY_MISSING")) {
            val backend = FakeBackend().apply { this.bytes = bytes }
            val result = NfcSnapshotWorkflow(backend).run(token) {}
            assertEquals(NfcSnapshotWorkflow.State.REJECTED, result.state)
            assertEquals(reason, result.detail)
            assertTrue(result.settled)
        }
    }

    @Test fun mismatchedDeviceMetadataAndOemEnvelopeAreRejected() {
        assertThrows(Exception::class.java) { NfcSnapshotArchive.parse(archive(), NfcBackupFixtures.metadata(version = 123)) }
        assertThrows(Exception::class.java) { NfcSnapshotArchive.parse(archive(), NfcBackupFixtures.metadata(sdk = 35)) }
        assertThrows(Exception::class.java) { NfcSnapshotArchive.parse(archive(), NfcBackupFixtures.metadata(fixture = "restore-probe-signer.hex")) }
        rejects("MIUI BACKUP\n2\ncom.android.nfc NFC\n0\n0\n".toByteArray() + archive())
    }

    private fun data() = Bundle().apply {
        putString("token", token); putByteArray("xml", xml)
        putString("domain", "CE")
        putString("identityHash", RestoreProbeArchive.sha256(xml)); putInt("records", 1)
        putString("archiveHash", "f".repeat(64)); putInt("boot", 4)
        putLong("elapsed", 100); putLong("wall", 1000); putString("fingerprint", "build")
    }

    @Test fun storeRoundTripExpirationAndDeletion() {
        val store = NfcSnapshotStore(folder.root)
        assertNull(store.read())
        val summary = store.save(data(), token, 4, 100, "build")
        assertEquals(summary, store.read())
        assertTrue(summary.recent(4, 300100, "build"))
        assertFalse(summary.recent(4, 300101, "build"))
        assertFalse(summary.recent(5, 200, "build"))
        assertFalse(summary.recent(-1, 200, "build"))
        assertFalse(summary.recent(4, 99, "build"))
        assertFalse(summary.recent(4, 200, "other"))
        store.delete()
        assertNull(store.read())
    }

    @Test fun badCaptureDoesNotReplaceGoodSnapshot() {
        val store = NfcSnapshotStore(folder.root)
        val saved = store.save(data(), token, 4, 100, "build")
        for (changed in listOf(data().apply { putString("identityHash", "0".repeat(64)) },
            data().apply { putInt("records", 2) }, data().apply { putInt("boot", 5) },
            data().apply { putLong("elapsed", 200) }, data().apply { putString("fingerprint", "other") },
            data().apply { putString("token", "b".repeat(32)) }, data().apply { remove("domain") },
            data().apply { putString("domain", "UNKNOWN") })) {
            assertThrows(Exception::class.java) { store.save(changed, token, 4, 100, "build") }
            assertEquals(saved, store.read())
        }
    }

    @Test @Config(shadows = [PosixAtomicFileShadow::class])
    fun savedSnapshotsKeepTheirDomainAndReadHistoricalCeSnapshots() {
        val store = NfcSnapshotStore(folder.root)
        for (domain in NfcStorageDomain.entries) {
            val saved = store.save(data().apply { putString("domain", domain.name) }, token, 4, 100, "build")
            assertEquals(domain, saved.domain)
            assertEquals(saved, store.read())
        }
        val file = File(folder.root, "nfc-snapshot.json")
        val legacy = JSONObject(file.readText()).apply { put("version", 1); remove("domain") }
        file.writeText(legacy.toString())
        assertEquals(NfcStorageDomain.CE, store.read()!!.domain)
        assertEquals(legacy.toString(), file.readText())
        legacy.put("version", 2)
        file.writeText(legacy.toString())
        assertNull(store.read())
    }

    @Test fun corruptOrOversizeStoreIsNotUsable() {
        val store = NfcSnapshotStore(folder.root)
        store.save(data(), token, 4, 100, "build")
        val path = File(folder.root, "nfc-snapshot.json")
        val json = JSONObject(path.readText()).put("identityHash", "0".repeat(64))
        path.writeText(json.toString())
        assertNull(store.read())
        path.writeBytes(ByteArray(256 * 1024 + 1))
        assertNull(store.read())
    }
}
