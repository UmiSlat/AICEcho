package io.github.umislat.aimesimulator.provisioning

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

/** Local byte/record checks only. No check here locks the live NFC process or authorizes a write. */
internal class NfcProvisionPlan private constructor(
    val target: Target,
    val uid: Int,
    val before: Identity,
    val after: Identity,
    original: ByteArray,
    proposed: ByteArray,
    val source: BaselineSource = BaselineSource.FILE,
    val domain: NfcStorageDomain = NfcStorageDomain.CE
) {
    enum class BaselineSource { FILE, RUNTIME }

    enum class Target(val component: String) {
        AIME("io.github.umislat.aimesimulator/io.github.umislat.aimesimulator.nfc.AimeHostService"),
        LAB("io.github.umislat.hceflab/io.github.umislat.hceflab.LabService")
    }

    class Identity(systemCode: String, nfcid2: String) {
        val systemCode = systemCode.uppercase()
        val nfcid2 = nfcid2.uppercase()
        init {
            require(this.systemCode.matches(Regex("[0-9A-F]{4}")))
            require(this.nfcid2.matches(Regex("[0-9A-F]{16}")))
        }
        internal fun matches(record: Map<String, String>) =
            systemCode == record["system-code"]?.uppercase() && nfcid2 == record["nfcid2"]?.uppercase()
        override fun toString() = "Identity(redacted)"
    }

    enum class ForwardCheck { BASELINE_MATCH, PROPOSED_PRESENT, CONFLICT }
    enum class RollbackCheck { CANDIDATE, ALREADY_REVERTED, TARGET_CONFLICT, INVALID_CURRENT }
    class Rollback internal constructor(val check: RollbackCheck, val candidate: Replacement? = null)

    /** Comparison fidelity follows the baseline source; hashes always identify the stored bytes. */
    class Replacement internal constructor(preimage: ByteArray, replacement: ByteArray,
        val source: BaselineSource = BaselineSource.FILE, val domain: NfcStorageDomain = NfcStorageDomain.CE) {
        private val preimage = preimage.copyOf()
        private val replacement = replacement.copyOf()
        val preimageHash = RestoreProbeArchive.sha256(this.preimage)
        val replacementHash = RestoreProbeArchive.sha256(this.replacement)
        fun matchesObservation(current: ByteArray) = matchesXml(preimage, current, source)
        fun matchesReplacement(current: ByteArray) = matchesXml(replacement, current, source)
        fun observationXml() = preimage.copyOf()
        fun xml() = replacement.copyOf()
    }

    private val original = original.copyOf()
    private val proposed = proposed.copyOf()
    private val originalRecords = records(this.original)
    private val proposedRecords = records(this.proposed)
    val originalHash = RestoreProbeArchive.sha256(this.original)
    val proposedHash = RestoreProbeArchive.sha256(this.proposed)
    val preservedRecords = originalRecords.size - 1
    private val targetKey = RecordKey(uid / 100000, target.component)

    fun originalXml() = original.copyOf()
    fun proposedXml() = proposed.copyOf()
    fun matchesOriginal(current: ByteArray) = matchesXml(original, current, source)
    fun matchesProposed(current: ByteArray) = matchesXml(proposed, current, source)

    fun checkForward(current: ByteArray) = when {
        matchesOriginal(current) -> ForwardCheck.BASELINE_MATCH
        matchesProposed(current) -> ForwardCheck.PROPOSED_PRESENT
        else -> ForwardCheck.CONFLICT
    }

    fun rollbackAgainst(current: ByteArray): Rollback {
        val observation = current.copyOf()
        val live = try { records(observation) } catch (_: Exception) {
            return Rollback(RollbackCheck.INVALID_CURRENT)
        }
        val previous = originalRecords.getValue(targetKey)
        val applied = proposedRecords.getValue(targetKey)
        val present = live[targetKey]
        if (matchesRecord(present, previous, source)) return Rollback(RollbackCheck.ALREADY_REVERTED)
        // UID, attribute presence and non-identity attributes remain part of target ownership.
        if (!matchesRecord(present, applied, source)) return Rollback(RollbackCheck.TARGET_CONFLICT)
        val reverted = if (proposed.contentEquals(observation)) original.copyOf() else {
            val merged = LinkedHashMap(live).apply { put(targetKey, previous) }
            serialize(merged).also { require(records(it) == merged) }
        }
        val verified = records(reverted)
        check(matchesRecord(verified[targetKey], previous, source))
        check(matchesRecords(verified.filterKeys { it != targetKey }, live.filterKeys { it != targetKey }, source))
        return Rollback(RollbackCheck.CANDIDATE, Replacement(observation, reverted, source, domain))
    }

    private data class RecordKey(val userId: Int, val component: String)

    companion object {
        fun currentIdentity(xml: ByteArray, target: Target, uid: Int): Identity? {
            val record = records(xml)[RecordKey(uid / 100000, target.component)] ?: return null
            require(record["uid"] == uid.toString())
            val sc = record["system-code"].orEmpty()
            val idm = record["nfcid2"].orEmpty()
            if (!sc.matches(Regex("[0-9A-Fa-f]{4}")) || !idm.matches(Regex("[0-9A-Fa-f]{16}"))) return null
            return Identity(sc, idm)
        }

        fun prepare(baseline: ByteArray, baselineHash: String, target: Target, uid: Int,
            expected: Identity, requested: Identity, source: BaselineSource = BaselineSource.FILE,
            domain: NfcStorageDomain = NfcStorageDomain.CE): NfcProvisionPlan {
            val original = baseline.copyOf()
            require(baselineHash.matches(Regex("[0-9a-f]{64}")) && RestoreProbeArchive.sha256(original) == baselineHash)
            require(uid in 10000..99999)
            require(requested.systemCode == "88B4" && requested.nfcid2 != "0".repeat(16) && requested.nfcid2 != "F".repeat(16))
            if (target == Target.LAB) require(requested.nfcid2 == "012E6C6162323031")
            val records = records(original)
            val key = RecordKey(uid / 100000, target.component)
            val record = checkNotNull(records[key]) { "Existing target record required" }
            require(record["uid"] == uid.toString() && expected.matches(record))
            require(!requested.matches(record)) { "No identity change requested" }
            val changed = LinkedHashMap(record).apply {
                put("system-code", requested.systemCode)
                put("nfcid2", requested.nfcid2)
            }
            val updated = LinkedHashMap(records).apply { put(key, changed) }
            val proposed = serialize(updated)
            check(records(proposed) == updated)
            return NfcProvisionPlan(target, uid, expected, requested, original, proposed, source, domain)
        }

        private fun canonicalComponent(component: String): String {
            val parts = component.split('/')
            return parts[0] + "/" + if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1]
        }

        private fun canonicalRecord(record: Map<String, String>) = record.mapValues { (key, value) ->
            when (key) {
                "component" -> canonicalComponent(value)
                "system-code", "nfcid2" -> value.uppercase()
                else -> value
            }
        }

        private fun matchesRecord(left: Map<String, String>?, right: Map<String, String>?, source: BaselineSource) =
            if (source == BaselineSource.FILE) left == right else
                left?.let(::canonicalRecord) == right?.let(::canonicalRecord)

        private fun matchesRecords(left: Map<RecordKey, Map<String, String>>,
            right: Map<RecordKey, Map<String, String>>, source: BaselineSource) =
            left.keys == right.keys && left.all { (key, value) -> matchesRecord(value, right[key], source) }

        private fun matchesXml(expected: ByteArray, current: ByteArray, source: BaselineSource): Boolean {
            if (source == BaselineSource.FILE) return expected.contentEquals(current)
            return try { matchesRecords(records(expected), records(current), source) } catch (_: Exception) { false }
        }

        // Reuse the snapshot validator before reading its closed schema. Do not relax a proven read path.
        private fun records(raw: ByteArray): LinkedHashMap<RecordKey, Map<String, String>> {
            NfcSnapshotArchive.validateXml(raw)
            val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(raw), "UTF-8") }
            val records = linkedMapOf<RecordKey, Map<String, String>>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 2) {
                    val attrs = (0 until parser.attributeCount).associateTo(linkedMapOf()) {
                        parser.getAttributeName(it) to parser.getAttributeValue(it)
                    }
                    val key = RecordKey(attrs.getValue("uid").toInt() / 100000,
                        canonicalComponent(attrs.getValue("component")))
                    check(records.put(key, attrs) == null)
                }
            }
            return records
        }

        private fun serialize(records: Map<RecordKey, Map<String, String>>): ByteArray {
            val output = ByteArrayOutputStream()
            Xml.newSerializer().apply {
                setOutput(output, "UTF-8")
                startDocument("UTF-8", true)
                startTag(null, "services")
                for (attrs in records.values) {
                    startTag(null, "service")
                    for ((key, value) in attrs) attribute(null, key, value)
                    endTag(null, "service")
                }
                endTag(null, "services")
                endDocument()
                flush()
            }
            return output.toByteArray().also { NfcSnapshotArchive.validateXml(it) }
        }
    }
}
