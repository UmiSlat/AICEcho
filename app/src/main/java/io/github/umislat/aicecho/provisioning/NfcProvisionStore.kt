package io.github.umislat.aicecho.provisioning

import android.os.Bundle
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Retains the original table before any restore is submitted. Plaintext, private, no-backup. */
internal class NfcProvisionStore(directory: File) {
    private val file = AtomicFile(File(directory, "nfc-provision-plan.json"))

    @Synchronized fun save(data: Bundle, ownerUid: Int, boot: Int) {
        val plan = validate(data, ownerUid)
        val checkpoint = checkpoint(data, plan)
        val token = data.getString("token").orEmpty()
        val json = JSONObject().put("version", 3).put("token", token).put("uid", ownerUid).put("boot", boot)
            .put("domain", plan.domain.name)
            .put("source", plan.source.name).put("state", checkpoint.state.name).put("direction", checkpoint.direction.name)
            .put("nfcid2", plan.after.nfcid2).put("oldSystemCode", plan.before.systemCode)
            .put("oldNfcid2", plan.before.nfcid2).put("originalHash", plan.originalHash)
            .put("proposedHash", plan.proposedHash)
            .put("original", Base64.encodeToString(plan.originalXml(), Base64.NO_WRAP))
            .put("proposed", Base64.encodeToString(plan.proposedXml(), Base64.NO_WRAP))
        checkpoint.rollback?.let {
            json.put("rollbackObservation", Base64.encodeToString(it.observationXml(), Base64.NO_WRAP))
            json.put("rollbackXml", Base64.encodeToString(it.xml(), Base64.NO_WRAP))
        }
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
        // AtomicFile can log a failed rename without throwing. Do not authorize a
        // restore unless the retained record is the one we just saved.
        val retained = checkNotNull(read(ownerUid)) { "Saved plan could not be read back" }
        check(retained.getString("token") == token && retained.getInt("boot") == boot &&
            retained.getString("originalHash") == plan.originalHash &&
            retained.getString("proposedHash") == plan.proposedHash && retained.getString("source") == plan.source.name &&
            retained.getString("domain") == plan.domain.name &&
            retained.getString("state") == checkpoint.state.name && retained.getString("direction") == checkpoint.direction.name &&
            retained.getByteArray("rollbackXml").contentEquals(checkpoint.rollback?.xml()) &&
            retained.getByteArray("rollbackObservation").contentEquals(checkpoint.rollback?.observationXml())) { "Saved plan differs" }
    }

    @Synchronized fun retainRollback(token: String, ownerUid: Int, boot: Int, observation: ByteArray, replacement: ByteArray) {
        val saved = checkNotNull(read(ownerUid))
        check(saved.getString("token") == token && saved.getString("state") == "ROLLING_BACK" &&
            saved.getString("direction") == "ROLLBACK")
        saved.putByteArray("rollbackObservation", observation)
        saved.putByteArray("rollbackXml", replacement)
        save(saved, ownerUid, boot)
    }

    /** Persist operation intent before IPC; a missing completion remains distinguishable after reboot. */
    @Synchronized fun mark(token: String, ownerUid: Int, boot: Int, state: NfcProvisionWorkflow.State,
        direction: NfcProvisionWorkflow.Direction? = null) {
        val saved = checkNotNull(read(ownerUid))
        check(saved.getString("token") == token)
        saved.putString("state", state.name)
        if (direction != null) {
            saved.putString("direction", direction.name)
            saved.remove("rollbackObservation"); saved.remove("rollbackXml")
        }
        save(saved, ownerUid, boot)
    }

    @Synchronized fun read(ownerUid: Int): Bundle? = runCatching {
        val raw = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count <= MAX_RECORD - output.size())
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
        val json = JSONObject(raw)
        val version = json.getInt("version")
        require(version in 1..3)
        Bundle().apply {
            putString("token", json.getString("token"))
            putInt("uid", json.getInt("uid"))
            putInt("boot", json.getInt("boot"))
            putString("domain", if (version < 3) NfcStorageDomain.CE.name else json.getString("domain"))
            putString("source", if (version == 1) NfcProvisionPlan.BaselineSource.FILE.name else json.getString("source"))
            putString("state", if (version == 1) NfcProvisionWorkflow.State.PREPARED.name else json.getString("state"))
            putString("direction", if (version == 1) NfcProvisionWorkflow.Direction.FORWARD.name else json.getString("direction"))
            for (name in listOf("nfcid2", "oldSystemCode", "oldNfcid2", "originalHash", "proposedHash")) {
                putString(name, json.getString(name))
            }
            for (name in listOf("original", "proposed")) {
                putByteArray(name, Base64.decode(json.getString(name), Base64.NO_WRAP))
            }
            for (name in listOf("rollbackObservation", "rollbackXml")) {
                if (json.has(name)) putByteArray(name, Base64.decode(json.getString(name), Base64.NO_WRAP))
            }
        }.also { checkpoint(it, validate(it, ownerUid)) }
    }.getOrNull()

    companion object {
        private const val MAX_RECORD = 6 * NfcSnapshotArchive.MAX_XML

        fun requiresResume(data: Bundle?) = data?.getString("state") in setOf("AWAITING_RELOAD", "CONFIRMING",
            "ROLLING_BACK", "RELOADING", "UNKNOWN")

        fun validate(data: Bundle, ownerUid: Int): NfcProvisionPlan {
            require(RestoreProbeArchive.validToken(data.getString("token").orEmpty()))
            require(data.getInt("uid", -1) == ownerUid)
            val original = checkNotNull(data.getByteArray("original"))
            val proposed = checkNotNull(data.getByteArray("proposed"))
            require(proposed.size <= NfcSnapshotArchive.MAX_XML)
            val plan = NfcProvisionPlan.prepare(original, data.getString("originalHash").orEmpty(),
                NfcProvisionPlan.Target.AICECHO, ownerUid,
                NfcProvisionPlan.Identity(data.getString("oldSystemCode").orEmpty(), data.getString("oldNfcid2").orEmpty()),
                NfcProvisionPlan.Identity("88B4", data.getString("nfcid2").orEmpty()),
                NfcProvisionPlan.BaselineSource.valueOf(data.getString("source") ?: "FILE"),
                NfcStorageDomain.valueOf(data.getString("domain") ?: "CE"))
            require(plan.proposedXml().contentEquals(proposed))
            require(plan.proposedHash == data.getString("proposedHash"))
            return plan
        }

        fun checkpoint(data: Bundle, plan: NfcProvisionPlan): NfcProvisionWorkflow.Checkpoint {
            val state = NfcProvisionWorkflow.State.valueOf(data.getString("state") ?: "PREPARED")
            val direction = NfcProvisionWorkflow.Direction.valueOf(data.getString("direction") ?: "FORWARD")
            val observation = data.getByteArray("rollbackObservation")
            val xml = data.getByteArray("rollbackXml")
            require((observation == null) == (xml == null))
            val replacement = if (observation != null && xml != null) {
                require(direction == NfcProvisionWorkflow.Direction.ROLLBACK && xml.size <= NfcSnapshotArchive.MAX_XML)
                checkNotNull(plan.rollbackAgainst(observation).candidate).also { require(it.xml().contentEquals(xml)) }
            } else null
            require(state != NfcProvisionWorkflow.State.AWAITING_RELOAD ||
                (plan.source == NfcProvisionPlan.BaselineSource.RUNTIME &&
                    (direction == NfcProvisionWorkflow.Direction.FORWARD || replacement != null)))
            return NfcProvisionWorkflow.Checkpoint(state, direction, replacement)
        }
    }
}
