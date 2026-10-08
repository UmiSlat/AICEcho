package io.github.umislat.aimesimulator.provisioning

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
        val token = data.getString("token").orEmpty()
        val json = JSONObject().put("version", 1).put("token", token).put("uid", ownerUid).put("boot", boot)
            .put("nfcid2", plan.after.nfcid2).put("oldSystemCode", plan.before.systemCode)
            .put("oldNfcid2", plan.before.nfcid2).put("originalHash", plan.originalHash)
            .put("proposedHash", plan.proposedHash)
            .put("original", Base64.encodeToString(plan.originalXml(), Base64.NO_WRAP))
            .put("proposed", Base64.encodeToString(plan.proposedXml(), Base64.NO_WRAP))
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
            retained.getString("proposedHash") == plan.proposedHash) { "Saved plan differs" }
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
        require(json.getInt("version") == 1)
        Bundle().apply {
            putString("token", json.getString("token"))
            putInt("uid", json.getInt("uid"))
            putInt("boot", json.getInt("boot"))
            for (name in listOf("nfcid2", "oldSystemCode", "oldNfcid2", "originalHash", "proposedHash")) {
                putString(name, json.getString(name))
            }
            for (name in listOf("original", "proposed")) {
                putByteArray(name, Base64.decode(json.getString(name), Base64.NO_WRAP))
            }
        }.also { validate(it, ownerUid) }
    }.getOrNull()

    companion object {
        private const val MAX_RECORD = 4 * NfcSnapshotArchive.MAX_XML

        fun validate(data: Bundle, ownerUid: Int): NfcProvisionPlan {
            require(RestoreProbeArchive.validToken(data.getString("token").orEmpty()))
            require(data.getInt("uid", -1) == ownerUid)
            val original = checkNotNull(data.getByteArray("original"))
            val proposed = checkNotNull(data.getByteArray("proposed"))
            require(proposed.size <= NfcSnapshotArchive.MAX_XML)
            val plan = NfcProvisionPlan.prepare(original, data.getString("originalHash").orEmpty(),
                NfcProvisionPlan.Target.AIME, ownerUid,
                NfcProvisionPlan.Identity(data.getString("oldSystemCode").orEmpty(), data.getString("oldNfcid2").orEmpty()),
                NfcProvisionPlan.Identity("88B4", data.getString("nfcid2").orEmpty()))
            require(plan.proposedXml().contentEquals(proposed))
            require(plan.proposedHash == data.getString("proposedHash"))
            return plan
        }
    }
}
