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
    proposed: ByteArray
) {
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

    /** A candidate is bound to the exact observation it was computed from, not to future state. */
    class Replacement internal constructor(preimage: ByteArray, replacement: ByteArray) {
        private val preimage = preimage.copyOf()
        private val replacement = replacement.copyOf()
        val preimageHash = RestoreProbeArchive.sha256(this.preimage)
        val replacementHash = RestoreProbeArchive.sha256(this.replacement)
        fun matchesObservation(current: ByteArray) = preimage.contentEquals(current)
        fun xml() = replacement.copyOf()
    }

    private val original = original.copyOf()
    private val proposed = proposed.copyOf()
    private val originalRecords = records(this.original)
    private val proposedRecords = records(this.proposed)
    val originalHash = RestoreProbeArchive.sha256(this.original)
    val proposedHash = RestoreProbeArchive.sha256(this.proposed)
    val preservedRecords = originalRecords.size - 1

    fun originalXml() = original.copyOf()
    fun proposedXml() = proposed.copyOf()

    fun checkForward(current: ByteArray) = when {
        original.contentEquals(current) -> ForwardCheck.BASELINE_MATCH
        proposed.contentEquals(current) -> ForwardCheck.PROPOSED_PRESENT
        else -> ForwardCheck.CONFLICT
    }

    fun rollbackAgainst(current: ByteArray): Rollback {
        val observation = current.copyOf()
        val live = try { records(observation) } catch (_: Exception) {
            return Rollback(RollbackCheck.INVALID_CURRENT)
        }
        val previous = originalRecords.getValue(target.component)
        val applied = proposedRecords.getValue(target.component)
        val present = live[target.component]
        if (present == previous) return Rollback(RollbackCheck.ALREADY_REVERTED)
        // UID, component spelling and all attributes must still match our proposed target record.
        if (present != applied) return Rollback(RollbackCheck.TARGET_CONFLICT)
        val reverted = if (proposed.contentEquals(observation)) original.copyOf() else {
            val merged = LinkedHashMap(live).apply { put(target.component, previous) }
            serialize(merged).also { require(records(it) == merged) }
        }
        val verified = records(reverted)
        check(verified[target.component] == previous)
        check(verified.filterKeys { it != target.component } == live.filterKeys { it != target.component })
        return Rollback(RollbackCheck.CANDIDATE, Replacement(observation, reverted))
    }

    companion object {
        fun currentIdentity(xml: ByteArray, target: Target, uid: Int): Identity? {
            val record = records(xml)[target.component] ?: return null
            require(record["uid"] == uid.toString())
            val sc = record["system-code"].orEmpty()
            val idm = record["nfcid2"].orEmpty()
            if (!sc.matches(Regex("[0-9A-Fa-f]{4}")) || !idm.matches(Regex("[0-9A-Fa-f]{16}"))) return null
            return Identity(sc, idm)
        }

        fun prepare(baseline: ByteArray, baselineHash: String, target: Target, uid: Int,
            expected: Identity, requested: Identity): NfcProvisionPlan {
            val original = baseline.copyOf()
            require(baselineHash.matches(Regex("[0-9a-f]{64}")) && RestoreProbeArchive.sha256(original) == baselineHash)
            require(uid in 10000..99999)
            require(requested.systemCode == "88B4" && requested.nfcid2 != "0".repeat(16) && requested.nfcid2 != "F".repeat(16))
            if (target == Target.LAB) require(requested.nfcid2 == "012E6C6162323031")
            val records = records(original)
            val record = checkNotNull(records[target.component]) { "Existing target record required" }
            require(record["uid"] == uid.toString() && expected.matches(record))
            require(!requested.matches(record)) { "No identity change requested" }
            val changed = LinkedHashMap(record).apply {
                put("system-code", requested.systemCode)
                put("nfcid2", requested.nfcid2)
            }
            val updated = LinkedHashMap(records).apply { put(target.component, changed) }
            val proposed = serialize(updated)
            check(records(proposed) == updated)
            return NfcProvisionPlan(target, uid, expected, requested, original, proposed)
        }

        // Reuse the snapshot validator before reading its closed schema. Do not relax a proven read path.
        private fun records(raw: ByteArray): LinkedHashMap<String, Map<String, String>> {
            NfcSnapshotArchive.validateXml(raw)
            val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(raw), "UTF-8") }
            val records = linkedMapOf<String, Map<String, String>>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 2) {
                    val attrs = (0 until parser.attributeCount).associateTo(linkedMapOf()) {
                        parser.getAttributeName(it) to parser.getAttributeValue(it)
                    }
                    val parts = attrs.getValue("component").split('/')
                    val canonical = parts[0] + "/" + if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1]
                    check(records.put(canonical, attrs) == null)
                }
            }
            return records
        }

        private fun serialize(records: Map<String, Map<String, String>>): ByteArray {
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
