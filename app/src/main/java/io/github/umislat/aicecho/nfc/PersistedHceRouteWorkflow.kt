package io.github.umislat.aicecho.nfc

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
        unavailable()?.let { return it }
        return try {
            backend.disable()
            mismatch(expectedNfcid2, expectedSystemCode)?.let { return it }
            if (!backend.enable()) {
                backend.disable()
                return Result(Outcome.ENABLE_FAILED)
            }
            Result(Outcome.READY)
        } catch (error: RuntimeException) {
            failureReporter(error)
        }
    }

    fun inspect(expectedNfcid2: String, expectedSystemCode: String): Result {
        unavailable()?.let { return it }
        return try {
            mismatch(expectedNfcid2, expectedSystemCode) ?: Result(Outcome.READY)
        } catch (error: RuntimeException) {
            failureReporter(error)
        }
    }

    private fun unavailable(): Result? {
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
            HceActivationWorkflow.Availability.READY -> return null
        }
    }

    private fun mismatch(expectedNfcid2: String, expectedSystemCode: String): Result? {
        val currentSystemCode = backend.currentSystemCode()
        val currentNfcid2 = backend.currentNfcid2()
        if (currentSystemCode == null || currentNfcid2 == null) {
            return Result(Outcome.SERVICE_RESTARTING)
        }
        if (!expectedSystemCode.equals(currentSystemCode, ignoreCase = true))
            return Result(Outcome.SYSTEM_CODE_MISMATCH)
        if (!expectedNfcid2.equals(currentNfcid2, ignoreCase = true))
            return Result(Outcome.NFCID2_MISMATCH)
        return null
    }
}
