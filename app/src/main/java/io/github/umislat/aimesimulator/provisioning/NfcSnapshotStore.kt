package io.github.umislat.aimesimulator.provisioning

import android.os.Bundle
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** One atomic private record, under noBackupFilesDir. Never an implicit restore input. */
internal class NfcSnapshotStore(directory: File) {
    private val file = AtomicFile(File(directory, "nfc-snapshot.json"))
    data class Summary(val token: String, val hash: String, val records: Int, val bytes: Int,
        val boot: Int, val elapsed: Long, val wall: Long, val fingerprint: String) {
        fun recent(currentBoot: Int, now: Long, currentFingerprint: String): Boolean =
            currentBoot >= 0 && currentBoot == boot && fingerprint == currentFingerprint &&
                now >= elapsed && now - elapsed <= 300_000
    }

    @Synchronized fun save(data: Bundle, token: String, boot: Int, elapsed: Long, fingerprint: String): Summary {
        require(RestoreProbeArchive.validToken(token) && token == data.getString("token"))
        val xml = checkNotNull(data.getByteArray("xml"))
        val count = NfcSnapshotArchive.validateXml(xml)
        val hash = RestoreProbeArchive.sha256(xml)
        require(hash == data.getString("identityHash") && count == data.getInt("records", -1))
        require(data.getInt("boot", -1) == boot && boot >= 0 && data.getString("fingerprint") == fingerprint)
        val captured = data.getLong("elapsed", -1)
        require(captured >= 0 && captured <= elapsed)
        val archiveHash = data.getString("archiveHash").orEmpty()
        require(archiveHash.matches(Regex("[0-9a-f]{64}")))
        val wall = data.getLong("wall", -1).also { require(it >= 0) }
        val json = JSONObject().put("version", 1).put("token", token).put("boot", boot)
            .put("elapsed", captured).put("wall", wall).put("fingerprint", fingerprint)
            .put("archiveHash", archiveHash).put("identityHash", hash).put("records", count)
            .put("xml", Base64.encodeToString(xml, Base64.NO_WRAP))
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
        return Summary(token, hash, count, xml.size, boot, captured, wall, fingerprint)
    }

    @Synchronized fun read(): Summary? = runCatching {
        val raw = file.openRead().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count <= 256 * 1024 - out.size())
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val json = JSONObject(raw.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1 && RestoreProbeArchive.validToken(json.getString("token")))
        val xml = Base64.decode(json.getString("xml"), Base64.NO_WRAP)
        val count = NfcSnapshotArchive.validateXml(xml)
        val hash = RestoreProbeArchive.sha256(xml)
        require(hash == json.getString("identityHash") && count == json.getInt("records"))
        require(json.getString("archiveHash").matches(Regex("[0-9a-f]{64}")))
        require(json.getInt("boot") >= 0 && json.getLong("elapsed") >= 0 && json.getLong("wall") >= 0)
        Summary(json.getString("token"), hash, count, xml.size, json.getInt("boot"),
            json.getLong("elapsed"), json.getLong("wall"), json.getString("fingerprint"))
    }.getOrNull()

    @Synchronized fun delete() { file.delete() }
}
