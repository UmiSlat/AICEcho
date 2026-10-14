package io.github.umislat.aicecho.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HceActivationWorkflowTest {
    @Test fun unsupportedDeviceStopsBeforeAdapterAndSelection() {
        val backend = FakeBackend(supported = false)
        val selection = FakeSelection(PREVIOUS_PROFILE)

        val report = workflow(backend, selection).activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

        assertEquals(HceSession.Stage.UNSUPPORTED, report.stage)
        assertEquals(listOf("supported"), backend.calls)
        assertTrue(selection.attempts.isEmpty())
    }

    @Test fun missingAdapterReportsServiceRestarting() {
        val backend = FakeBackend(
            availabilityResult = HceActivationWorkflow.Availability.SERVICE_RESTARTING
        )
        val selection = FakeSelection(PREVIOUS_PROFILE)

        val report = workflow(backend, selection).activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

        assertEquals(HceSession.Stage.SERVICE_RESTARTING, report.stage)
        assertEquals(listOf("supported", "availability"), backend.calls)
        assertTrue(selection.attempts.isEmpty())
    }

    @Test fun disabledAdapterReportsNfcDisabled() {
        val backend = FakeBackend(
            availabilityResult = HceActivationWorkflow.Availability.NFC_DISABLED
        )
        val selection = FakeSelection(PREVIOUS_PROFILE)

        val report = workflow(backend, selection).activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

        assertEquals(HceSession.Stage.NFC_DISABLED, report.stage)
        assertEquals(listOf("supported", "availability"), backend.calls)
        assertTrue(selection.attempts.isEmpty())
    }

    @Test fun nfcid2RejectionRestoresPreviousSelection() {
        val backend = FakeBackend(nfcid2Result = false)
        val selection = FakeSelection(PREVIOUS_PROFILE)

        val report = workflow(backend, selection).activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

        assertEquals(HceSession.Stage.ID, report.stage)
        assertEquals(listOf(TARGET_PROFILE, PREVIOUS_PROFILE), selection.attempts)
        assertEquals(PREVIOUS_PROFILE, selection.currentId)
        assertEquals(
            listOf("supported", "availability", "disable", "nfcid2:$NFCID2"),
            backend.calls
        )
    }

    @Test fun systemCodeRejectionDisablesServiceAndRestoresSelection() {
        val backend = FakeBackend(systemCodeResult = false)
        val selection = FakeSelection(PREVIOUS_PROFILE)

        val report = workflow(backend, selection).activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

        assertEquals(HceSession.Stage.SYSTEM_CODE, report.stage)
        assertEquals(listOf(TARGET_PROFILE, PREVIOUS_PROFILE), selection.attempts)
        assertEquals(PREVIOUS_PROFILE, selection.currentId)
        assertEquals(
            listOf(
                "supported",
                "availability",
                "disable",
                "nfcid2:$NFCID2",
                "systemCode:$SYSTEM_CODE",
                "disable"
            ),
            backend.calls
        )
    }

    @Test fun enableRejectionDisablesServiceAndRestoresSelection() {
        val backend = FakeBackend(enableResult = false)
        val selection = FakeSelection(PREVIOUS_PROFILE)

        val report = workflow(backend, selection).activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

        assertEquals(HceSession.Stage.ENABLE, report.stage)
        assertEquals(listOf(TARGET_PROFILE, PREVIOUS_PROFILE), selection.attempts)
        assertEquals(PREVIOUS_PROFILE, selection.currentId)
        assertEquals(2, backend.calls.count { it == "disable" })
    }

    @Test fun successfulRegistrationKeepsSelectionAndForwardsParameters() {
        val backend = FakeBackend()
        val selection = FakeSelection(PREVIOUS_PROFILE)

        val report = workflow(backend, selection).activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

        assertEquals(HceSession.Stage.READY, report.stage)
        assertEquals(listOf(TARGET_PROFILE), selection.attempts)
        assertEquals(TARGET_PROFILE, selection.currentId)
        assertEquals(
            listOf(
                "supported",
                "availability",
                "disable",
                "nfcid2:$NFCID2",
                "systemCode:$SYSTEM_CODE",
                "enable"
            ),
            backend.calls
        )
    }

    @Test fun alreadySelectedProfileIsNotWrittenAgain() {
        val backend = FakeBackend()
        val selection = FakeSelection(TARGET_PROFILE)

        val report = workflow(backend, selection).activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

        assertEquals(HceSession.Stage.READY, report.stage)
        assertTrue(selection.attempts.isEmpty())
        assertEquals(TARGET_PROFILE, selection.currentId)
    }

    @Test fun nullPreviousSelectionIsRestoredAfterRejection() {
        val backend = FakeBackend(nfcid2Result = false)
        val selection = FakeSelection(null)

        val report = workflow(backend, selection).activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

        assertEquals(HceSession.Stage.ID, report.stage)
        assertEquals(listOf(TARGET_PROFILE, null), selection.attempts)
        assertEquals(null, selection.currentId)
    }

    @Test fun availabilityExceptionUsesFailureReporterBeforeSelectionChanges() {
        val backend = FakeBackend(throwOn = Operation.AVAILABILITY)
        val selection = FakeSelection(PREVIOUS_PROFILE)
        val reportedErrors = mutableListOf<RuntimeException>()

        val report = workflow(backend, selection, reportedErrors)
            .activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

        assertEquals(HceSession.Stage.EXCEPTION, report.stage)
        assertSame(backend.failure, reportedErrors.single())
        assertTrue(selection.attempts.isEmpty())
        assertEquals(PREVIOUS_PROFILE, selection.currentId)
    }

    @Test fun registrationExceptionsRestoreSelectionAndUseFailureReporter() {
        val operations = listOf(
            Operation.DISABLE,
            Operation.SET_NFCID2,
            Operation.REGISTER_SYSTEM_CODE,
            Operation.ENABLE
        )

        operations.forEach { operation ->
            val backend = FakeBackend(throwOn = operation)
            val selection = FakeSelection(PREVIOUS_PROFILE)
            val reportedErrors = mutableListOf<RuntimeException>()

            val report = workflow(backend, selection, reportedErrors)
                .activate(TARGET_PROFILE, NFCID2, SYSTEM_CODE)

            assertEquals(operation.name, HceSession.Stage.EXCEPTION, report.stage)
            assertSame(operation.name, backend.failure, reportedErrors.single())
            assertEquals(
                operation.name,
                listOf(TARGET_PROFILE, PREVIOUS_PROFILE),
                selection.attempts
            )
            assertEquals(operation.name, PREVIOUS_PROFILE, selection.currentId)
        }
    }

    private fun workflow(
        backend: FakeBackend,
        selection: FakeSelection,
        reportedErrors: MutableList<RuntimeException> = mutableListOf()
    ): HceActivationWorkflow = HceActivationWorkflow(backend, selection) { error ->
        reportedErrors += error
        HceSession.Report(HceSession.Stage.EXCEPTION, error.message.orEmpty())
    }

    private enum class Operation {
        AVAILABILITY, DISABLE, SET_NFCID2, REGISTER_SYSTEM_CODE, ENABLE
    }

    private class FakeBackend(
        private val supported: Boolean = true,
        private val availabilityResult: HceActivationWorkflow.Availability =
            HceActivationWorkflow.Availability.READY,
        private val nfcid2Result: Boolean = true,
        private val systemCodeResult: Boolean = true,
        private val enableResult: Boolean = true,
        private val throwOn: Operation? = null
    ) : HceActivationWorkflow.Backend {
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

        override fun setNfcid2(nfcid2: String): Boolean {
            record(Operation.SET_NFCID2, "nfcid2:$nfcid2")
            return nfcid2Result
        }

        override fun registerSystemCode(systemCode: String): Boolean {
            record(Operation.REGISTER_SYSTEM_CODE, "systemCode:$systemCode")
            return systemCodeResult
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

    private class FakeSelection(initialId: String?) : HceActivationWorkflow.Selection {
        var currentId: String? = initialId
            private set
        val attempts = mutableListOf<String?>()

        override fun selectedProfileId(): String? = currentId

        override fun select(profileId: String?) {
            attempts += profileId
            currentId = profileId
        }
    }

    private companion object {
        const val PREVIOUS_PROFILE = "previous-profile"
        const val TARGET_PROFILE = "target-profile"
        const val NFCID2 = "02FE123456789ABC"
        const val SYSTEM_CODE = "88B4"
    }
}
