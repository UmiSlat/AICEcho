package io.github.umislat.aimesimulator.provisioning

internal class NfcSnapshotWorkflow(private val backend: Backend) {
    enum class State { IDLE, PREPARING, EXPORTING, VERIFYING, CAPTURED, REJECTED, FAILED, UNKNOWN }
    data class Result(val state: State, val token: String = "", val capture: Capture? = null, val detail: String = "") {
        val busy get() = state in setOf(State.PREPARING, State.EXPORTING, State.VERIFYING)
        val settled get() = state in setOf(State.CAPTURED, State.REJECTED, State.FAILED)
    }
    class Capture(val parsed: NfcSnapshotArchive.Parsed, val boot: Int, val elapsed: Long,
        val wall: Long, val fingerprint: String)
    interface Backend {
        fun preflight()
        fun metadata(): NfcBackupMetadata
        fun export(): ByteArray
        fun stamp(parsed: NfcSnapshotArchive.Parsed): Capture
    }

    fun run(token: String, report: (Result) -> Unit): Result {
        require(RestoreProbeArchive.validToken(token))
        var submitted = false
        var completed = false
        return try {
            backend.preflight()
            report(Result(State.EXPORTING, token))
            submitted = true
            val raw = backend.export()
            completed = true
            report(Result(State.VERIFYING, token))
            val parsed = try { NfcSnapshotArchive.parse(raw, backend.metadata()) } finally { raw.fill(0) }
            Result(State.CAPTURED, token, backend.stamp(parsed))
        } catch (error: Exception) {
            val state = if (!submitted) State.FAILED else if (completed || error is NfcBackupException) State.REJECTED else State.UNKNOWN
            Result(state, token, detail = when {
                state == State.UNKNOWN -> ""
                error is NfcBackupException -> error.reason.name
                !submitted -> "UNSUPPORTED"
                else -> "INVALID_ARCHIVE"
            })
        } catch (_: LinkageError) {
            Result(if (completed) State.REJECTED else if (submitted) State.UNKNOWN else State.FAILED, token)
        }
    }
}
