package io.github.umislat.aicecho.provisioning

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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.InflaterInputStream

@RunWith(BundledArchiveTestRunner::class)
@Config(sdk = [29, 36], application = Application::class)
class NfcProvisionPlanTest {
    private val target = NfcProvisionPlan.Target.AICECHO
    private val oldId = NfcProvisionPlan.Identity("4000", "02FE010203040506")
    private val newId = NfcProvisionPlan.Identity("88B4", "012E102030405060")
    private val source = """<?xml version="1.0" encoding="UTF-8"?>
        <services>
          <service component="io.github.umislat.aicecho/.nfc.AicHostService" uid="10366" system-code="4000" nfcid2="02FE010203040506" description="a &amp; b &quot;x&quot;"/>
          <service component="example.wallet/.Host" uid="10500" system-code="4001" nfcid2="02FE111111111111"/>
        </services>
    """.trimIndent().toByteArray()
    private fun plan(raw: ByteArray = source,
        baselineSource: NfcProvisionPlan.BaselineSource = NfcProvisionPlan.BaselineSource.FILE,
        domain: NfcStorageDomain = NfcStorageDomain.CE) =
        NfcProvisionPlan.prepare(raw, RestoreProbeArchive.sha256(raw), target, 10366, oldId, newId, baselineSource, domain)
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

    private fun reformatted(xml: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        Xml.newSerializer().apply {
            setOutput(output, "UTF-8")
            startTag(null, "services")
            for (record in records(xml).reversed()) {
                text("\n  ")
                startTag(null, "service")
                for ((key, value) in record.entries.reversed()) {
                    val normalized = when {
                        key == "component" -> value.replace("/.", "/${value.substringBefore('/')}.")
                        key in setOf("system-code", "nfcid2") && value !in setOf("NULL", "RANDOM") -> value.lowercase()
                        else -> value
                    }
                    attribute(null, key, normalized)
                }
                endTag(null, "service")
            }
            text("\n")
            endTag(null, "services")
            flush()
        }
        return output.toByteArray()
    }

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
        assertEquals(NfcProvisionPlan.BaselineSource.FILE, plan.source)
        assertEquals(NfcProvisionPlan.ForwardCheck.BASELINE_MATCH, plan.checkForward(source))
        assertEquals(NfcProvisionPlan.ForwardCheck.PROPOSED_PRESENT, plan.checkForward(plan.proposedXml()))
        for (changed in listOf(edited(source, "4001", "4002"), source + "\n".toByteArray(),
            reformatted(source), byteArrayOf())) {
            assertEquals(NfcProvisionPlan.ForwardCheck.CONFLICT, plan.checkForward(changed))
        }
    }

    @Test fun exactPostimageAllowsExactOriginalByteRollback() {
        val plan = plan()
        val rollback = plan.rollbackAgainst(plan.proposedXml())
        assertEquals(NfcProvisionPlan.RollbackCheck.CANDIDATE, rollback.check)
        assertArrayEquals(source, checkNotNull(rollback.candidate).xml())
        assertTrue(rollback.candidate.matchesObservation(plan.proposedXml()))
        assertArrayEquals(plan.proposedXml(), rollback.candidate.observationXml())
        assertTrue(rollback.candidate.matchesReplacement(source))
        assertFalse(rollback.candidate.matchesReplacement(reformatted(source)))
        assertFalse(rollback.candidate.matchesObservation(reformatted(plan.proposedXml())))
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
        candidate.observationXml().fill(0)
        assertArrayEquals(source, candidate.xml())
        assertArrayEquals(plan.proposedXml(), candidate.observationXml())
        assertTrue(candidate.matchesObservation(plan.proposedXml()))
    }

    @Test fun runtimeComparisonIgnoresRecordAttributeOrderFormattingComponentSpellingAndHexCase() {
        val plan = plan(baselineSource = NfcProvisionPlan.BaselineSource.RUNTIME)
        val baseline = reformatted(source)
        val proposed = reformatted(plan.proposedXml())
        assertEquals(NfcProvisionPlan.BaselineSource.RUNTIME, plan.source)
        assertFalse(source.contentEquals(baseline))
        assertFalse(plan.proposedXml().contentEquals(proposed))
        assertTrue(plan.matchesOriginal(baseline))
        assertTrue(plan.matchesProposed(proposed))
        assertEquals(NfcProvisionPlan.ForwardCheck.BASELINE_MATCH, plan.checkForward(baseline))
        assertEquals(NfcProvisionPlan.ForwardCheck.PROPOSED_PRESENT, plan.checkForward(proposed))
        assertEquals(RestoreProbeArchive.sha256(source), plan.originalHash)
        assertEquals(RestoreProbeArchive.sha256(plan.proposedXml()), plan.proposedHash)
        for (invalid in listOf(byteArrayOf(), "<services>".toByteArray())) {
            assertFalse(plan.matchesOriginal(invalid))
            assertFalse(plan.matchesProposed(invalid))
            assertEquals(NfcProvisionPlan.ForwardCheck.CONFLICT, plan.checkForward(invalid))
        }
    }

    @Test fun runtimeComparisonPreservesMissingAttributesVersusNullAndOtherAllowedValues() {
        for ((attribute, removed) in listOf("system-code" to " system-code=\"4001\"",
            "nfcid2" to " nfcid2=\"02FE111111111111\"")) {
            val baseline = edited(source, removed, "")
            val runtimePlan = plan(baseline, NfcProvisionPlan.BaselineSource.RUNTIME)
            val nullValue = edited(baseline, "uid=\"10500\"", "uid=\"10500\" $attribute=\"NULL\"")
            assertTrue(runtimePlan.matchesOriginal(reformatted(baseline)))
            assertFalse(runtimePlan.matchesOriginal(nullValue))
            assertEquals(NfcProvisionPlan.ForwardCheck.CONFLICT, runtimePlan.checkForward(nullValue))
            val nullPlan = plan(nullValue, NfcProvisionPlan.BaselineSource.RUNTIME)
            assertTrue(nullPlan.matchesOriginal(reformatted(nullValue)))
            assertFalse(nullPlan.matchesOriginal(baseline))
        }
        val plan = plan(baselineSource = NfcProvisionPlan.BaselineSource.RUNTIME)
        for (changed in listOf(edited(source, "10500", "10501"),
            edited(source, "4001", "NULL"), edited(source, "02FE111111111111", "RANDOM"),
            edited(source, "a &amp; b", "A &amp; b"),
            edited(source, "uid=\"10500\"", "uid=\"10500\" description=\"\""))) {
            assertEquals(NfcProvisionPlan.ForwardCheck.CONFLICT, plan.checkForward(changed))
        }
    }

    @Test fun runtimeRollbackMatchesSemanticTargetAndWholeReplacement() {
        val plan = plan(baselineSource = NfcProvisionPlan.BaselineSource.RUNTIME)
        val current = reformatted(plan.proposedXml())
        val rollback = plan.rollbackAgainst(current)
        assertEquals(NfcProvisionPlan.RollbackCheck.CANDIDATE, rollback.check)
        val candidate = checkNotNull(rollback.candidate)
        assertEquals(NfcProvisionPlan.BaselineSource.RUNTIME, candidate.source)
        assertArrayEquals(current, candidate.observationXml())
        assertTrue(candidate.matchesReplacement(source))
        assertEquals(records(current).single { it["uid"] == "10500" },
            records(candidate.xml()).single { it["uid"] == "10500" })
        assertTrue(candidate.matchesObservation(plan.proposedXml()))
        assertTrue(candidate.matchesReplacement(reformatted(source)))
        assertFalse(candidate.matchesReplacement(edited(source, "4001", "4002")))
        assertFalse(candidate.matchesReplacement(edited(source, "10500", "10501")))
        assertFalse(candidate.matchesReplacement(plan.proposedXml()))
        assertFalse(candidate.matchesReplacement(byteArrayOf()))
        candidate.observationXml().fill(0)
        candidate.xml().fill(0)
        current.fill(0)
        assertTrue(candidate.matchesObservation(plan.proposedXml()))
        assertTrue(candidate.matchesReplacement(source))
    }

    @Test fun runtimeRollbackPreservesOtherRecordsLatestChangesAndMissingAttributes() {
        val plan = plan(baselineSource = NfcProvisionPlan.BaselineSource.RUNTIME)
        val current = reformatted(edited(edited(plan.proposedXml(), "4001", "NULL"),
            " nfcid2=\"02FE111111111111\"", ""))
        assertEquals(NfcProvisionPlan.ForwardCheck.CONFLICT, plan.checkForward(current))
        val candidate = checkNotNull(plan.rollbackAgainst(current).candidate)
        val wallet = records(current).single { it.getValue("uid") == "10500" }
        assertEquals(wallet, records(candidate.xml()).single { it.getValue("uid") == "10500" })
        assertEquals(records(source)[0], records(candidate.xml()).single { it.getValue("uid") == "10366" })
        assertTrue(candidate.matchesObservation(reformatted(current)))
        assertTrue(candidate.matchesReplacement(reformatted(candidate.xml())))
        assertFalse(candidate.matchesReplacement(source))
        assertFalse(candidate.matchesObservation(plan.proposedXml()))
        assertFalse(candidate.matchesReplacement(edited(candidate.xml(), "uid=\"10500\"",
            "uid=\"10500\" nfcid2=\"NULL\"")))
        val restoredCandidate = checkNotNull(plan.rollbackAgainst(candidate.observationXml()).candidate)
        assertEquals(NfcProvisionPlan.BaselineSource.RUNTIME, restoredCandidate.source)
        assertTrue(restoredCandidate.matchesReplacement(candidate.xml()))
        assertArrayEquals(candidate.xml(), restoredCandidate.xml())
    }

    @Test fun runtimeRollbackKeepsServiceAdditionAndDeletion() {
        val plan = plan(baselineSource = NfcProvisionPlan.BaselineSource.RUNTIME)
        val third = """<service component="example.other/.Host" uid="99910500" nfcid2="RANDOM"/>"""
        val added = reformatted(edited(plan.proposedXml(), "</services>", "$third</services>"))
        val candidate = checkNotNull(plan.rollbackAgainst(added).candidate)
        val others = { xml: ByteArray -> records(xml).filter { it["uid"] != "10366" } }
        assertEquals(others(added), others(candidate.xml()))
        val targetOnly = "<services><service component=\"${target.component}\" uid=\"10366\" " +
            "system-code=\"88b4\" nfcid2=\"${newId.nfcid2.lowercase()}\" description=\"a &amp; b &quot;x&quot;\"/></services>"
        val removed = checkNotNull(plan.rollbackAgainst(targetOnly.toByteArray()).candidate)
        assertEquals(listOf(records(source)[0]), records(removed.xml()))
    }

    @Test fun runtimeRollbackStillRejectsTargetUidIdentityMissingNullAndDescriptionChanges() {
        val plan = plan(baselineSource = NfcProvisionPlan.BaselineSource.RUNTIME)
        for (current in listOf(edited(plan.proposedXml(), "10366", "10367"),
            edited(plan.proposedXml(), "10366", "110366"),
            edited(plan.proposedXml(), newId.nfcid2, "012E999999999999"),
            edited(plan.proposedXml(), " system-code=\"88B4\"", ""),
            edited(plan.proposedXml(), "88B4", "NULL"),
            edited(plan.proposedXml(), "a &amp; b", "A &amp; b"))) {
            val rollback = plan.rollbackAgainst(reformatted(current))
            assertEquals(NfcProvisionPlan.RollbackCheck.TARGET_CONFLICT, rollback.check)
            assertNull(rollback.candidate)
        }
        val reverted = plan.rollbackAgainst(reformatted(edited(source, "4001", "4002")))
        assertEquals(NfcProvisionPlan.RollbackCheck.ALREADY_REVERTED, reverted.check)
        assertNull(reverted.candidate)
    }

    @Test fun sameComponentInAnotherUserIsPreservedAndCurrentIdentityRequiresExactUid() {
        val extra = """<service component="${target.component}" uid="99910366" system-code="4002" nfcid2="02FE222222222222"/>"""
        val baseline = edited(source, "</services>", "$extra</services>")
        for (baselineSource in NfcProvisionPlan.BaselineSource.entries) {
            val plan = plan(baseline, baselineSource)
            assertEquals(2, plan.preservedRecords)
            assertTrue(plan.matchesOriginal(baseline))
            val profile = records(baseline).single { it["uid"] == "99910366" }
            assertEquals(profile, records(plan.proposedXml()).single { it["uid"] == "99910366" })
            val current = edited(plan.proposedXml(), "4002", "4003")
            val candidate = checkNotNull(plan.rollbackAgainst(current).candidate)
            assertEquals("4003", records(candidate.xml()).single { it["uid"] == "99910366" }["system-code"])
            assertTrue(candidate.matchesReplacement(candidate.xml()))
            assertFalse(candidate.matchesReplacement(baseline))
            assertEquals(NfcProvisionPlan.ForwardCheck.CONFLICT, plan.checkForward(current))
            if (baselineSource == NfcProvisionPlan.BaselineSource.RUNTIME) {
                assertTrue(plan.matchesOriginal(reformatted(baseline)))
                assertTrue(candidate.matchesReplacement(reformatted(candidate.xml())))
            }
        }
        assertEquals("4000", checkNotNull(NfcProvisionPlan.currentIdentity(baseline, target, 10366)).systemCode)
        assertEquals("4002", checkNotNull(NfcProvisionPlan.currentIdentity(baseline, target, 99910366)).systemCode)
        assertNull(NfcProvisionPlan.currentIdentity(baseline, target, 110366))
        assertThrows(Exception::class.java) { NfcProvisionPlan.currentIdentity(baseline, target, 10367) }
        assertThrows(Exception::class.java) { NfcProvisionPlan.currentIdentity(baseline, target, 99910367) }
    }

    @Test fun duplicateCanonicalComponentWithinOneUserIsRejectedEvenWithAnotherUid() {
        for (uid in listOf(10366, 10367)) {
            val extra = """<service component="${target.component}" uid="$uid" system-code="4002" nfcid2="02FE222222222222"/>"""
            val duplicate = edited(source, "</services>", "$extra</services>")
            for (baselineSource in NfcProvisionPlan.BaselineSource.entries) {
                assertThrows(Exception::class.java) { plan(duplicate, baselineSource) }
            }
        }
    }

    @Test fun directReplacementConstructorKeepsFileComparisonByDefault() {
        val candidate = NfcProvisionPlan.Replacement(source, plan().proposedXml())
        assertEquals(NfcProvisionPlan.BaselineSource.FILE, candidate.source)
        assertTrue(candidate.matchesObservation(source))
        assertFalse(candidate.matchesObservation(reformatted(source)))
        assertTrue(candidate.matchesReplacement(candidate.xml()))
        assertFalse(candidate.matchesReplacement(reformatted(candidate.xml())))
    }

    @Test fun fileRollbackStillRequiresExactTargetAttributeSpelling() {
        val plan = plan()
        val observation = reformatted(plan.proposedXml())
        assertFalse(plan.matchesProposed(observation))
        assertFalse(plan.matchesOriginal(reformatted(source)))
        assertEquals(NfcProvisionPlan.RollbackCheck.TARGET_CONFLICT, plan.rollbackAgainst(observation).check)
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
        for (changed in listOf(edited(source, "aicecho/.nfc.AicHostService", "aicecho/.Other"),
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
        val lab = edited(source, "io.github.umislat.aicecho/.nfc.AicHostService", NfcProvisionPlan.Target.LAB.component)
        assertThrows(Exception::class.java) { NfcProvisionPlan.prepare(lab, RestoreProbeArchive.sha256(lab), NfcProvisionPlan.Target.LAB, 10366, oldId, newId) }
    }

    @Test fun identityStringDoesNotDiscloseCardValues() {
        assertFalse(newId.toString().contains(newId.nfcid2))
        assertFalse(newId.toString().contains(newId.systemCode))
    }

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

    @Test fun archiveContainsOnlyFixedManifestAndCeIdentityWithDeviceMetadata() {
        val plan = plan()
        val metadata = NfcBackupFixtures.metadata(version = 12345, sdk = 35, fixture = "restore-probe-signer.hex")
        val archive = NfcRestoreArchive.forward(plan, metadata)
        val files = archiveFiles(archive)
        assertEquals(listOf("apps/com.android.nfc/_manifest", "apps/com.android.nfc/f/dynamic_systemcode_nfcid2.xml"), files.keys.toList())
        assertArrayEquals(plan.proposedXml(), files.values.last())
        val manifest = files.values.first().toString(Charsets.US_ASCII).trimEnd('\n').split('\n')
        assertEquals(listOf("1", "com.android.nfc", "12345", "35", "", "0", "1"), manifest.take(7))
        assertEquals(RestoreProbeArchive.hex(metadata.certificates.single()), manifest.last())
        assertArrayEquals(plan.proposedXml(), NfcSnapshotArchive.parse(archive, metadata).xml)
        assertThrows(Exception::class.java) { NfcBackupMetadata(36, 36, listOf(byteArrayOf(0))) }
        // Synthetic fixture only, for an independent host-side TAR/zlib check.
        File("build/reports/nfc-provision").apply { mkdirs() }.resolve("forward.ab").writeBytes(archive)
    }

    @Test fun rollbackArchiveUsesMergedCurrentTableNotStaleWholeBackup() {
        val plan = plan()
        val current = edited(plan.proposedXml(), "4001", "4002")
        val candidate = checkNotNull(plan.rollbackAgainst(current).candidate)
        val archive = NfcRestoreArchive.rollback(candidate, NfcBackupFixtures.metadata())
        val restored = archiveFiles(archive).values.last()
        assertEquals("4002", records(restored)[1]["system-code"])
        assertEquals(records(source)[0], records(restored)[0])
        File("build/reports/nfc-provision").apply { mkdirs() }.resolve("rollback-merged.ab").writeBytes(archive)
    }

    @Test fun forwardAndMergedRollbackWriteExactlyTheCapturedDomainForBothSources() {
        for (source in NfcProvisionPlan.BaselineSource.entries) for (domain in NfcStorageDomain.entries) {
            val plan = plan(baselineSource = source, domain = domain)
            val current = edited(plan.proposedXml(), "4001", "4002")
            val rollback = checkNotNull(plan.rollbackAgainst(current).candidate)
            assertEquals(domain, plan.domain)
            assertEquals(domain, rollback.domain)
            for ((archive, xml) in listOf(NfcRestoreArchive.forward(plan, NfcBackupFixtures.metadata()) to plan.proposedXml(),
                NfcRestoreArchive.rollback(rollback, NfcBackupFixtures.metadata()) to rollback.xml())) {
                assertEquals(listOf("apps/com.android.nfc/_manifest", domain.archivePath), archiveFiles(archive).keys.toList())
                val parsed = NfcSnapshotArchive.parse(archive, NfcBackupFixtures.metadata())
                assertEquals(domain, parsed.domain)
                assertArrayEquals(xml, parsed.xml)
            }
        }
    }

    @Test fun optInAppSnapshotCanPlanOnlySyntheticLabChangeWithoutLosingOtherRecord() {
        val path = System.getenv("AICECHO_PRIVATE_NFC_SNAPSHOT")
        assumeTrue(!path.isNullOrBlank())
        val json = JSONObject(File(checkNotNull(path)).readText())
        val xml = Base64.getDecoder().decode(json.getString("xml"))
        val record = records(xml).single { it.getValue("component").startsWith("io.github.umislat.aicecho.hceflab/") }
        val plan = NfcProvisionPlan.prepare(xml, json.getString("identityHash"), NfcProvisionPlan.Target.LAB,
            record.getValue("uid").toInt(), NfcProvisionPlan.Identity(record.getValue("system-code"), record.getValue("nfcid2")),
            NfcProvisionPlan.Identity("88B4", "012E6C6162323031"))
        val othersBefore = records(xml).filter { it != record }
        val othersAfter = records(plan.proposedXml()).filter { !it.getValue("component").startsWith("io.github.umislat.aicecho.hceflab/") }
        assertTrue(othersBefore == othersAfter)
        assertTrue(plan.rollbackAgainst(plan.proposedXml()).candidate!!.xml().contentEquals(xml))
        assertEquals(1, plan.preservedRecords)
    }
}
