package io.github.umislat.aimesimulator.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object CardBackupCodec {
    const val FILE_FORMAT = "io.github.umislat.aimesimulator.card-backup"
    const val FILE_VERSION = 1
    const val SYSTEM_CODE = "88B4"
    const val PMM = "00F1000000014300"
    const val MAX_BACKUP_BYTES = 512 * 1024
    const val MAX_PROFILES = 256

    data class Snapshot(
        val profiles: List<CardProfile>,
        val selectedProfileId: String?,
        val exportedAtEpochMillis: Long
    )

    sealed interface DecodeResult {
        data class Success(val snapshot: Snapshot) : DecodeResult
        data class Failure(val reason: FailureReason) : DecodeResult
    }

    enum class FailureReason {
        EMPTY,
        TOO_LARGE,
        MALFORMED,
        UNSUPPORTED_VERSION,
        TOO_MANY_PROFILES,
        INVALID_PROFILE,
        DUPLICATE_PROFILE,
        INVALID_SELECTION,
        INTEGRITY_CHECK_FAILED
    }

    fun encode(snapshot: Snapshot): String {
        require(snapshot.profiles.size <= MAX_PROFILES)
        require(snapshot.exportedAtEpochMillis > 0)
        require(snapshot.profiles.map(CardProfile::profileId).toSet().size == snapshot.profiles.size)
        require(snapshot.selectedProfileId == null ||
            snapshot.profiles.any { it.profileId == snapshot.selectedProfileId })

        val root = JSONObject().apply {
            put("format", FILE_FORMAT)
            put("version", FILE_VERSION)
            put("exportedAt", snapshot.exportedAtEpochMillis)
            put("systemCode", SYSTEM_CODE)
            put("pmm", PMM)
            put("selectedProfileId", snapshot.selectedProfileId ?: JSONObject.NULL)
            put("profiles", JSONArray().apply {
                snapshot.profiles.forEach { profile -> put(profile.toJson()) }
            })
            put("sha256", checksum(snapshot))
        }
        return root.toString(2)
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty()) return DecodeResult.Failure(FailureReason.EMPTY)
        if (bytes.size > MAX_BACKUP_BYTES) {
            return DecodeResult.Failure(FailureReason.TOO_LARGE)
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return DecodeResult.Failure(FailureReason.MALFORMED)
        }
        if (text.isBlank()) return DecodeResult.Failure(FailureReason.EMPTY)

        return try {
            val root = JSONObject(text)
            if (root.requireString("format") != FILE_FORMAT) {
                return DecodeResult.Failure(FailureReason.MALFORMED)
            }
            val version = root.requireInt("version")
            if (version != FILE_VERSION) {
                return DecodeResult.Failure(FailureReason.UNSUPPORTED_VERSION)
            }
            if (root.requireString("systemCode") != SYSTEM_CODE ||
                root.requireString("pmm") != PMM) {
                return DecodeResult.Failure(FailureReason.MALFORMED)
            }
            val exportedAt = root.requireLong("exportedAt")
            if (exportedAt <= 0 || exportedAt > MAX_DATE_EPOCH_MILLIS) {
                return DecodeResult.Failure(FailureReason.MALFORMED)
            }
            val array = root.getJSONArray("profiles")
            if (array.length() > MAX_PROFILES) {
                return DecodeResult.Failure(FailureReason.TOO_MANY_PROFILES)
            }
            val profiles = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                        ?: return DecodeResult.Failure(FailureReason.INVALID_PROFILE)
                    val profileId = item.requireString("id")
                    if (profileId.isBlank() || profileId.length > MAX_PROFILE_ID_LENGTH) {
                        return DecodeResult.Failure(FailureReason.INVALID_PROFILE)
                    }
                    val label = item.requireString("label")
                    if (label.length > MAX_LABEL_LENGTH) {
                        return DecodeResult.Failure(FailureReason.INVALID_PROFILE)
                    }
                    val profile = CardProfile.create(
                        label = label,
                        idm = item.requireString("idm"),
                        spad0 = item.optionalString("spad0"),
                        idBlock = item.optionalString("idBlock"),
                        accessCode = item.optionalString("accessCode"),
                        profileId = profileId
                    ) ?: return DecodeResult.Failure(FailureReason.INVALID_PROFILE)
                    add(profile)
                }
            }
            if (profiles.map(CardProfile::profileId).toSet().size != profiles.size) {
                return DecodeResult.Failure(FailureReason.DUPLICATE_PROFILE)
            }
            val selectedProfileId = root.optionalString("selectedProfileId")
            if (selectedProfileId != null && profiles.none { it.profileId == selectedProfileId }) {
                return DecodeResult.Failure(FailureReason.INVALID_SELECTION)
            }
            val snapshot = Snapshot(profiles, selectedProfileId, exportedAt)
            val expectedChecksum = root.requireString("sha256")
            if (!expectedChecksum.matches(Regex("[0-9A-Fa-f]{64}")) ||
                !MessageDigest.isEqual(
                    expectedChecksum.uppercase().toByteArray(StandardCharsets.US_ASCII),
                    checksum(snapshot).toByteArray(StandardCharsets.US_ASCII)
                )) {
                return DecodeResult.Failure(FailureReason.INTEGRITY_CHECK_FAILED)
            }
            DecodeResult.Success(snapshot)
        } catch (_: JSONException) {
            DecodeResult.Failure(FailureReason.MALFORMED)
        }
    }

    private fun checksum(snapshot: Snapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: String?) {
            if (value == null) {
                digest.update("-1:".toByteArray(StandardCharsets.UTF_8))
            } else {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
                digest.update(':'.code.toByte())
                digest.update(bytes)
            }
            digest.update('\n'.code.toByte())
        }

        put(FILE_FORMAT)
        put(FILE_VERSION.toString())
        put(snapshot.exportedAtEpochMillis.toString())
        put(SYSTEM_CODE)
        put(PMM)
        put(snapshot.selectedProfileId)
        put(snapshot.profiles.size.toString())
        snapshot.profiles.forEach { profile ->
            put(profile.profileId)
            put(profile.label)
            put(profile.idm)
            put(profile.spad0)
            put(profile.idBlock)
            put(profile.accessCode)
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        }
    }

    private fun CardProfile.toJson(): JSONObject = JSONObject().apply {
        put("id", profileId)
        put("label", label)
        put("idm", idm)
        put("spad0", spad0 ?: JSONObject.NULL)
        put("idBlock", idBlock ?: JSONObject.NULL)
        put("accessCode", accessCode ?: JSONObject.NULL)
    }

    private fun JSONObject.requireString(key: String): String =
        (get(key) as? String) ?: throw JSONException("$key must be a string")

    private fun JSONObject.requireInt(key: String): Int {
        val value = get(key) as? Number ?: throw JSONException("$key must be a number")
        val longValue = value.toLong()
        if (longValue !in Int.MIN_VALUE..Int.MAX_VALUE || value.toDouble() != longValue.toDouble()) {
            throw JSONException("$key must be an integer")
        }
        return longValue.toInt()
    }

    private fun JSONObject.requireLong(key: String): Long {
        val value = get(key) as? Number ?: throw JSONException("$key must be a number")
        val longValue = value.toLong()
        if (value.toDouble() != longValue.toDouble()) {
            throw JSONException("$key must be an integer")
        }
        return longValue
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return (get(key) as? String) ?: throw JSONException("$key must be a string or null")
    }

    private const val MAX_PROFILE_ID_LENGTH = 128
    private const val MAX_LABEL_LENGTH = 256
    private const val MAX_DATE_EPOCH_MILLIS = 253_402_300_799_999L
}
