package io.github.umislat.aimesimulator.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedHceRouteWorkflowTest {
    @Test fun unsupportedDeviceStopsBeforeReadingSavedValues() {
        val backend = FakeBackend(supported = false)

        val result = workflow(backend).activate(NFCID2, SYSTEM_CODE)

        assertEquals(PersistedHceRouteWorkflow.Outcome.UNSUPPORTED, result.outcome)
        assertEquals(listOf("supported"), backend.calls)
    }

    @Test fun unavailableNfcServiceStopsBeforeReadingSavedValues() {
        val backend = FakeBackend(
            availabilityResult = HceActivationWorkflow.Availability.SERVICE_RESTARTING
        )

        val result = workflow(backend).activate(NFCID2, SYSTEM_CODE)

        assertEquals(PersistedHceRouteWorkflow.Outcome.SERVICE_RESTARTING, result.outcome)
        assertEquals(listOf("supported", "availability"), backend.calls)
    }

    @Test fun disabledNfcStopsBeforeReadingSavedValues() {
        val backend = FakeBackend(
            availabilityResult = HceActivationWorkflow.Availability.NFC_DISABLED
        )

        val result = workflow(backend).activate(NFCID2, SYSTEM_CODE)

        assertEquals(PersistedHceRouteWorkflow.Outcome.NFC_DISABLED, result.outcome)
        assertEquals(listOf("supported", "availability"), backend.calls)
    }

    @Test fun systemCodeMismatchReadsBothValuesAndSkipsEnable() {
        val backend = FakeBackend(currentSystemCode = "4000")

        val result = workflow(backend).activate(NFCID2, SYSTEM_CODE)

        assertEquals(PersistedHceRouteWorkflow.Outcome.SYSTEM_CODE_MISMATCH, result.outcome)
        assertEquals(
            listOf("supported", "availability", "disable", "systemCode", "nfcid2"),
            backend.calls
        )
    }

    @Test fun nfcid2MismatchSkipsEnable() {
        val backend = FakeBackend(currentNfcid2 = "02FE000000000000")

        val result = workflow(backend).activate(NFCID2, SYSTEM_CODE)

        assertEquals(PersistedHceRouteWorkflow.Outcome.NFCID2_MISMATCH, result.outcome)
        assertEquals(
            listOf("supported", "availability", "disable", "systemCode", "nfcid2"),
            backend.calls
        )
    }

    @Test fun exactSavedRouteEnablesWithoutAnyRegistrationOperation() {
        val backend = FakeBackend()

        val result = workflow(backend).activate(NFCID2, SYSTEM_CODE)

        assertTrue(result.succeeded)
        assertEquals(
            listOf("supported", "availability", "disable", "systemCode", "nfcid2", "enable"),
            backend.calls
        )
    }

    @Test fun hexadecimalCaseDoesNotChangeRouteIdentity() {
        val backend = FakeBackend(
            currentNfcid2 = NFCID2.lowercase(),
            currentSystemCode = SYSTEM_CODE.lowercase()
        )

        val result = workflow(backend).activate(NFCID2, SYSTEM_CODE)

        assertEquals(PersistedHceRouteWorkflow.Outcome.READY, result.outcome)
    }

    @Test fun enableRejectionIsReportedAfterAnExactMatch() {
        val backend = FakeBackend(enableResult = false)

        val result = workflow(backend).activate(NFCID2, SYSTEM_CODE)

        assertEquals(PersistedHceRouteWorkflow.Outcome.ENABLE_FAILED, result.outcome)
        assertEquals(
            listOf(
                "supported",
                "availability",
                "disable",
                "systemCode",
                "nfcid2",
                "enable",
                "disable"
            ),
            backend.calls
        )
    }

    @Test fun runtimeFailuresUseTheProvidedReporter() {
        Operation.values().forEach { operation ->
            val backend = FakeBackend(throwOn = operation)
            val reportedErrors = mutableListOf<RuntimeException>()

            val result = workflow(backend, reportedErrors).activate(NFCID2, SYSTEM_CODE)

            assertSame(operation.name, backend.failure, reportedErrors.single())
            assertEquals(operation.name, PersistedHceRouteWorkflow.Outcome.ERROR, result.outcome)
        }
    }

    private fun workflow(
        backend: FakeBackend,
        reportedErrors: MutableList<RuntimeException> = mutableListOf()
    ): PersistedHceRouteWorkflow = PersistedHceRouteWorkflow(backend) { error ->
        reportedErrors += error
        PersistedHceRouteWorkflow.Result(
            PersistedHceRouteWorkflow.Outcome.ERROR,
            error.message.orEmpty()
        )
    }

    private enum class Operation {
        AVAILABILITY, DISABLE, READ_SYSTEM_CODE, READ_NFCID2, ENABLE
    }

    private class FakeBackend(
        private val supported: Boolean = true,
        private val availabilityResult: HceActivationWorkflow.Availability =
            HceActivationWorkflow.Availability.READY,
        private val currentSystemCode: String? = SYSTEM_CODE,
        private val currentNfcid2: String? = NFCID2,
        private val enableResult: Boolean = true,
        private val throwOn: Operation? = null
    ) : PersistedHceRouteWorkflow.Backend {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("simulated NFC failure")

        override fun isSupported(): Boolean {
            calls += "supported"
            return supported
        }

        override fun availability(): HceActivationWorkflow.Availability {
            record(Operation.AVAILABILITY, "availability")
            return availabilityResult
        }

        override fun disable() {
            record(Operation.DISABLE, "disable")
        }

        override fun currentSystemCode(): String? {
            record(Operation.READ_SYSTEM_CODE, "systemCode")
            return currentSystemCode
        }

        override fun currentNfcid2(): String? {
            record(Operation.READ_NFCID2, "nfcid2")
            return currentNfcid2
        }

        override fun enable(): Boolean {
            record(Operation.ENABLE, "enable")
            return enableResult
        }

        private fun record(operation: Operation, value: String) {
            calls += value
            if (throwOn == operation) throw failure
        }
    }

    private companion object {
        const val NFCID2 = "02FE123456789ABC"
        const val SYSTEM_CODE = "88B4"
    }
}
