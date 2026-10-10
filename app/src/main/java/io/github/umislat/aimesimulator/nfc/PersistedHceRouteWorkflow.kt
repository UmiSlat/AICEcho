package io.github.umislat.aimesimulator.nfc

internal class PersistedHceRouteWorkflow(
    private val backend: Backend,
    private val failureReporter: (RuntimeException) -> Result
) {
    enum class Outcome {
        READY,
        UNSUPPORTED,
        NFC_DISABLED,
        SERVICE_RESTARTING,
        SYSTEM_CODE_MISMATCH,
        NFCID2_MISMATCH,
        ENABLE_FAILED,
        ERROR
    }

    data class Result(val outcome: Outcome, val detail: String = "") {
        val succeeded: Boolean get() = outcome == Outcome.READY
    }

    // This path must only inspect and enable an existing mapping; registration belongs to
    // HceActivationWorkflow and must not be added to this backend.
    interface Backend {
        fun isSupported(): Boolean
        fun availability(): HceActivationWorkflow.Availability
        fun disable()
        fun currentSystemCode(): String?
        fun currentNfcid2(): String?
        fun enable(): Boolean
    }

    fun activate(expectedNfcid2: String, expectedSystemCode: String): Result {
        if (!backend.isSupported()) return Result(Outcome.UNSUPPORTED)

        val availability = try {
            backend.availability()
        } catch (error: RuntimeException) {
            return failureReporter(error)
        }
        when (availability) {
            HceActivationWorkflow.Availability.SERVICE_RESTARTING -> {
                return Result(Outcome.SERVICE_RESTARTING)
            }
            HceActivationWorkflow.Availability.NFC_DISABLED -> {
                return Result(Outcome.NFC_DISABLED)
            }
            HceActivationWorkflow.Availability.READY -> Unit
        }

        return try {
            backend.disable()
            val currentSystemCode = backend.currentSystemCode()
            val currentNfcid2 = backend.currentNfcid2()
            if (!expectedSystemCode.equals(currentSystemCode, ignoreCase = true)) {
                return Result(Outcome.SYSTEM_CODE_MISMATCH)
            }
            if (!expectedNfcid2.equals(currentNfcid2, ignoreCase = true)) {
                return Result(Outcome.NFCID2_MISMATCH)
            }
            if (!backend.enable()) {
                backend.disable()
                return Result(Outcome.ENABLE_FAILED)
            }
            Result(Outcome.READY)
        } catch (error: RuntimeException) {
            failureReporter(error)
        }
    }
}
