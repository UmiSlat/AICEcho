package io.github.umislat.aimesimulator.provisioning

import android.app.Application
import android.util.Xml
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.InflaterInputStream

@RunWith(BundledArchiveTestRunner::class)
@Config(sdk = [29, 36], application = Application::class)
class NfcProvisionPlanTest {
    private val target = NfcProvisionPlan.Target.AIME
    private val oldId = NfcProvisionPlan.Identity("4000", "02FE010203040506")
    private val newId = NfcProvisionPlan.Identity("88B4", "012E102030405060")
    private val source = """<?xml version="1.0" encoding="UTF-8"?>
        <services>
          <service component="io.github.umislat.aimesimulator/.nfc.AimeHostService" uid="10366" system-code="4000" nfcid2="02FE010203040506" description="a &amp; b &quot;x&quot;"/>
          <service component="example.wallet/.Host" uid="10500" system-code="4001" nfcid2="02FE111111111111"/>
        </services>
    """.trimIndent().toByteArray()
    private fun plan(raw: ByteArray = source) = NfcProvisionPlan.prepare(raw, RestoreProbeArchive.sha256(raw),
        target, 10366, oldId, newId)
    private fun records(xml: ByteArray): List<Map<String, String>> {
        val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(xml), "UTF-8") }
        val records = mutableListOf<Map<String, String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "service") {
                records.add((0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) })
            }
        }
        return records
    }
    private fun edited(bytes: ByteArray, from: String, to: String) = bytes.toString(Charsets.UTF_8)
        .replace(from, to).toByteArray()

    @Test fun forwardChangesOnlyTargetIdentityAndKeepsAttributesAndOrder() {
        val plan = plan()
        val before = records(source)
        val after = records(plan.proposedXml())
        assertEquals(before[0] + mapOf("system-code" to "88B4", "nfcid2" to newId.nfcid2), after[0])
        assertEquals(before[1], after[1])
        assertEquals(1, plan.preservedRecords)
        assertArrayEquals(source, plan.originalXml())
    }

    @Test fun forwardRequiresExactReviewedPreimageEvenForFormattingChanges() {
        val plan = plan()
        assertEquals(NfcProvisionPlan.ForwardCheck.BASELINE_MATCH, plan.checkForward(source))
        assertEquals(NfcProvisionPlan.ForwardCheck.PROPOSED_PRESENT, plan.checkForward(plan.proposedXml()))
        for (changed in listOf(edited(source, "4001", "4002"), source + "\n".toByteArray(), byteArrayOf())) {
            assertEquals(NfcProvisionPlan.ForwardCheck.CONFLICT, plan.checkForward(changed))
        }
    }

    @Test fun exactPostimageAllowsExactOriginalByteRollback() {
        val plan = plan()
        val rollback = plan.rollbackAgainst(plan.proposedXml())
        assertEquals(NfcProvisionPlan.RollbackCheck.CANDIDATE, rollback.check)
        assertArrayEquals(source, checkNotNull(rollback.candidate).xml())
        assertTrue(rollback.candidate.matchesObservation(plan.proposedXml()))
    }

    @Test fun rollbackPreservesAnotherServicesLaterChanges() {
        val plan = plan()
        val current = edited(plan.proposedXml(), "4001", "4002")
        val candidate = checkNotNull(plan.rollbackAgainst(current).candidate)
        val reverted = records(candidate.xml())
        assertEquals(records(source)[0], reverted[0])
        assertEquals(records(current)[1], reverted[1])
        assertEquals("4002", reverted[1]["system-code"])
        assertTrue(candidate.matchesObservation(current))
        assertFalse(candidate.matchesObservation(edited(current, "4002", "4003")))
        assertFalse(candidate.matchesObservation(plan.proposedXml()))
    }

    @Test fun rollbackPreservesLaterServiceAdditionAndDeletion() {
        val plan = plan()
        val third = """<service component="example.other/.Host" uid="99910500" system-code="4003" nfcid2="RANDOM"/>"""
        val added = edited(plan.proposedXml(), "</services>", "$third</services>")
        val addedBack = checkNotNull(plan.rollbackAgainst(added).candidate).xml()
        assertEquals(records(added).drop(1), records(addedBack).drop(1))
        val ownOnly = "<services>" + plan.proposedXml().toString(Charsets.UTF_8)
            .substringAfter("<services>").substringBefore("<service component=\"example.wallet") + "</services>"
        val removedBack = checkNotNull(plan.rollbackAgainst(ownOnly.toByteArray()).candidate).xml()
        assertEquals(listOf(records(source)[0]), records(removedBack))
    }

    @Test fun changedOwnerIdentityOrTargetAttributesCannotBeRolledBack() {
        val plan = plan()
        for (current in listOf(edited(plan.proposedXml(), "10366", "10367"),
            edited(plan.proposedXml(), newId.nfcid2, "012E999999999999"),
            edited(plan.proposedXml(), "88B4", "4000"),
            edited(plan.proposedXml(), "description=", "description='changed' ignored="))) {
            val result = plan.rollbackAgainst(current)
            assertNull(result.candidate)
            assertTrue(result.check in setOf(NfcProvisionPlan.RollbackCheck.TARGET_CONFLICT,
                NfcProvisionPlan.RollbackCheck.INVALID_CURRENT))
        }
    }

    @Test fun descriptionOnlyChangeIsStillAnOwnershipConflict() {
        val plan = plan()
        val current = edited(plan.proposedXml(), "a &amp; b", "changed &amp; b")
        assertEquals(NfcProvisionPlan.RollbackCheck.TARGET_CONFLICT, plan.rollbackAgainst(current).check)
    }

    @Test fun missingTargetAndInvalidXmlNeverProduceReplacement() {
        val plan = plan()
        assertEquals(NfcProvisionPlan.RollbackCheck.TARGET_CONFLICT, plan.rollbackAgainst("<services/>".toByteArray()).check)
        assertEquals(NfcProvisionPlan.RollbackCheck.INVALID_CURRENT, plan.rollbackAgainst("<services>".toByteArray()).check)
    }

    @Test fun alreadyRevertedTargetDoesNotUndoOtherChangesOrWriteAgain() {
        val plan = plan()
        val result = plan.rollbackAgainst(edited(source, "4001", "4002"))
        assertEquals(NfcProvisionPlan.RollbackCheck.ALREADY_REVERTED, result.check)
        assertNull(result.candidate)
    }

    @Test fun callerCannotMutatePlanOrRollbackByteArrays() {
        val input = source.copyOf()
        val plan = plan(input)
        input.fill(0)
        plan.originalXml().fill(0)
        plan.proposedXml().fill(0)
        assertArrayEquals(source, plan.originalXml())
        val observed = plan.proposedXml()
        val candidate = checkNotNull(plan.rollbackAgainst(observed).candidate)
        observed.fill(0)
        candidate.xml().fill(0)
        assertArrayEquals(source, candidate.xml())
        assertTrue(candidate.matchesObservation(plan.proposedXml()))
    }

    @Test fun wrongHashOwnerPreimageAndNoopAreRejected() {
        assertThrows(Exception::class.java) { NfcProvisionPlan.prepare(source, "0".repeat(64), target, 10366, oldId, newId) }
        for (uid in listOf(0, 2000, 1027, 10367, 110366)) {
            assertThrows(Exception::class.java) { NfcProvisionPlan.prepare(source, RestoreProbeArchive.sha256(source), target, uid, oldId, newId) }
        }
        assertThrows(Exception::class.java) { NfcProvisionPlan.prepare(source, RestoreProbeArchive.sha256(source), target, 10366, newId, oldId) }
        val current = plan().proposedXml()
        assertThrows(Exception::class.java) { NfcProvisionPlan.prepare(current, RestoreProbeArchive.sha256(current), target, 10366, newId, newId) }
    }

    @Test fun missingTargetOrNonConcretePreimageIsNotInvented() {
        for (changed in listOf(edited(source, "aimesimulator/.nfc.AimeHostService", "aimesimulator/.Other"),
            edited(source, oldId.nfcid2, "RANDOM"), edited(source, "4000", "NULL"))) {
            assertThrows(Exception::class.java) { plan(changed) }
        }
    }

    @Test fun doesNotRestrictOriginalIdmToOneIssuerPrefix() {
        for (idm in listOf("012E102030405060", "021E102030405060", "02FE102030405060", "012F102030405060")) {
            val plan = NfcProvisionPlan.prepare(source, RestoreProbeArchive.sha256(source), target, 10366,
                oldId, NfcProvisionPlan.Identity("88b4", idm.lowercase()))
            assertEquals(idm, records(plan.proposedXml())[0]["nfcid2"])
        }
    }

    @Test fun onlyAimeSystemAndFixedSyntheticLabIdentityArePlanned() {
        for (identity in listOf(NfcProvisionPlan.Identity("FFFF", newId.nfcid2),
            NfcProvisionPlan.Identity("4000", newId.nfcid2), NfcProvisionPlan.Identity("88B4", "0".repeat(16)))) {
            assertThrows(Exception::class.java) { NfcProvisionPlan.prepare(source, RestoreProbeArchive.sha256(source), target, 10366, oldId, identity) }
        }
        val lab = edited(source, "io.github.umislat.aimesimulator/.nfc.AimeHostService", NfcProvisionPlan.Target.LAB.component)
        assertThrows(Exception::class.java) { NfcProvisionPlan.prepare(lab, RestoreProbeArchive.sha256(lab), NfcProvisionPlan.Target.LAB, 10366, oldId, newId) }
    }

    @Test fun identityStringDoesNotDiscloseCardValues() {
        assertFalse(newId.toString().contains(newId.nfcid2))
        assertFalse(newId.toString().contains(newId.systemCode))
    }

    private fun certificate() = checkNotNull(javaClass.getResourceAsStream("/miui-nfc-signer.hex"))
        .bufferedReader().use { it.readText().trim().chunked(2).map { pair -> pair.toInt(16).toByte() }.toByteArray() }

    private fun archiveFiles(archive: ByteArray): Map<String, ByteArray> {
        val prefix = "ANDROID BACKUP\n5\n1\nnone\n".toByteArray()
        assertTrue(archive.copyOfRange(0, prefix.size).contentEquals(prefix))
        val files = linkedMapOf<String, ByteArray>()
        TarArchiveInputStream(InflaterInputStream(ByteArrayInputStream(archive, prefix.size, archive.size - prefix.size))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                assertTrue(entry.isFile)
                assertEquals(384, entry.mode)
                assertEquals(0L, entry.modTime.time)
                assertTrue(entry.extraPaxHeaders.isEmpty())
                files[entry.name] = tar.readBytes()
            }
        }
        return files
    }

    @Test fun archiveContainsOnlyFixedManifestAndCeIdentityWithPinnedSigner() {
        val plan = plan()
        val archive = NfcRestoreArchive.forward(plan, certificate())
        val files = archiveFiles(archive)
        assertEquals(listOf("apps/com.android.nfc/_manifest", "apps/com.android.nfc/f/dynamic_systemcode_nfcid2.xml"), files.keys.toList())
        assertArrayEquals(plan.proposedXml(), files.values.last())
        val manifest = files.values.first().toString(Charsets.US_ASCII).trimEnd('\n').split('\n')
        assertEquals(listOf("1", "com.android.nfc", "36", "36", "", "0", "1"), manifest.take(7))
        assertEquals(RestoreProbeArchive.hex(certificate()), manifest.last())
        assertThrows(Exception::class.java) { NfcRestoreArchive.forward(plan, byteArrayOf(0)) }
        // Synthetic fixture only, for an independent host-side TAR/zlib check.
        File("build/reports/nfc-provision").apply { mkdirs() }.resolve("forward.ab").writeBytes(archive)
    }

    @Test fun rollbackArchiveUsesMergedCurrentTableNotStaleWholeBackup() {
        val plan = plan()
        val current = edited(plan.proposedXml(), "4001", "4002")
        val candidate = checkNotNull(plan.rollbackAgainst(current).candidate)
        val archive = NfcRestoreArchive.rollback(candidate, certificate())
        val restored = archiveFiles(archive).values.last()
        assertEquals("4002", records(restored)[1]["system-code"])
        assertEquals(records(source)[0], records(restored)[0])
        File("build/reports/nfc-provision").apply { mkdirs() }.resolve("rollback-merged.ab").writeBytes(archive)
    }

    @Test fun optInAppSnapshotCanPlanOnlySyntheticLabChangeWithoutLosingOtherRecord() {
        val path = System.getenv("AIMESIM_PRIVATE_NFC_SNAPSHOT")
        assumeTrue(!path.isNullOrBlank())
        val json = JSONObject(File(checkNotNull(path)).readText())
        val xml = Base64.getDecoder().decode(json.getString("xml"))
        val record = records(xml).single { it.getValue("component").startsWith("io.github.umislat.hceflab/") }
        val plan = NfcProvisionPlan.prepare(xml, json.getString("identityHash"), NfcProvisionPlan.Target.LAB,
            record.getValue("uid").toInt(), NfcProvisionPlan.Identity(record.getValue("system-code"), record.getValue("nfcid2")),
            NfcProvisionPlan.Identity("88B4", "012E6C6162323031"))
        val othersBefore = records(xml).filter { it != record }
        val othersAfter = records(plan.proposedXml()).filter { !it.getValue("component").startsWith("io.github.umislat.hceflab/") }
        assertTrue(othersBefore == othersAfter)
        assertTrue(plan.rollbackAgainst(plan.proposedXml()).candidate!!.xml().contentEquals(xml))
        assertEquals(1, plan.preservedRecords)
    }
}
