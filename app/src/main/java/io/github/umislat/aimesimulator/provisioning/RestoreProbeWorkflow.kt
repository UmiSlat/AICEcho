package io.github.umislat.aimesimulator.provisioning

internal class RestoreProbeWorkflow(private val backend: Backend) {
    enum class State { IDLE, PREPARING, CONFIRMING, VERIFYING, VERIFIED, NOT_VERIFIED, UNKNOWN, FAILED, MARKERS_MATCH }
    data class Result(val state: State, val token: String = "", val detail: String = "") {
        val busy: Boolean get() = state in setOf(State.PREPARING, State.CONFIRMING, State.VERIFYING)
    }
    data class Completion(val exitCode: Int, val timedOut: Boolean)

    interface Backend {
        fun certificate(): ByteArray
        fun restore(archive: ByteArray): Completion
        fun readMarker(deviceProtected: Boolean): ByteArray?
    }

    fun run(token: String, report: (Result) -> Unit): Result {
        require(RestoreProbeArchive.validToken(token))
        var submitted = false
        return try {
            report(Result(State.PREPARING, token))
            val certificate = backend.certificate()
            require(RestoreProbeArchive.sha256(certificate) == RestoreProbeArchive.SIGNER_SHA256) {
                "Unexpected marker app signer"
            }
            val archive = RestoreProbeArchive.build(certificate, token)
            report(Result(State.CONFIRMING, token))
            submitted = true
            val completion = backend.restore(archive)
            if (completion.timedOut) return Result(State.UNKNOWN, token, "Restore did not finish in time")
            report(Result(State.VERIFYING, token))
            if (completion.exitCode == 0 && matches(token)) Result(State.VERIFIED, token)
            else Result(State.NOT_VERIFIED, token)
        } catch (error: Exception) {
            // A failed invocation can still have reached BackupManager. Never auto-retry it.
            Result(if (submitted) State.UNKNOWN else State.FAILED, token, error.javaClass.simpleName)
        }
    }

    fun matches(token: String): Boolean {
        require(RestoreProbeArchive.validToken(token))
        return backend.readMarker(false)?.contentEquals(RestoreProbeArchive.marker(token, false)) == true &&
            backend.readMarker(true)?.contentEquals(RestoreProbeArchive.marker(token, true)) == true
    }
}
