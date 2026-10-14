package io.github.umislat.aicecho.ui

import io.github.umislat.aicecho.nfc.NfcCardRegistration
import io.github.umislat.aicecho.provisioning.NfcProvisionWorkflow
import io.github.umislat.aicecho.provisioning.ShizukuProbeModel
import org.junit.Assert.*
import org.junit.Test

class CardSetupStepTest {
    private val ready = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY,
        uid = 2000, bu = true, provisionCandidate = true, planSaved = true)
    private val idm = "012E112233445566"

    @Test fun wizardChoosesOnlyTheCurrentAction() {
        for ((state, action) in mapOf(
            NfcProvisionWorkflow.State.IDLE to CardSetupStep.CHECK,
            NfcProvisionWorkflow.State.NEEDS_BASELINE to CardSetupStep.INITIALIZE,
            NfcProvisionWorkflow.State.PREPARED to CardSetupStep.APPLY,
            NfcProvisionWorkflow.State.UNCHANGED to CardSetupStep.APPLY,
            NfcProvisionWorkflow.State.VERIFIED to CardSetupStep.RELOAD,
            NfcProvisionWorkflow.State.AWAITING_RELOAD to CardSetupStep.RELOAD,
            NfcProvisionWorkflow.State.RELOADED to CardSetupStep.START,
            NfcProvisionWorkflow.State.ALREADY_PRESENT to CardSetupStep.START
        )) {
            val screen = ready.copy(provision = NfcProvisionWorkflow.Result(state),
                canActivate = action == CardSetupStep.START)
            assertEquals(state.toString(), action, CardSetupStep.resolve(screen, true))
            assertTrue(action.enabled(screen, true, true))
        }
    }

    @Test fun lostOrBusyOperationsCannotOfferAnotherWriteOrStart() {
        for (screen in listOf(ready.copy(provisionPending = true), ready.copy(pending = true),
            ready.copy(snapshotPending = true), ready.copy(provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.UNKNOWN)))) {
            val step = CardSetupStep.resolve(screen, true)
            assertEquals(CardSetupStep.BLOCKED, step)
            assertFalse(step.enabled(screen, true, true))
        }
        assertEquals(CardSetupStep.BUSY, CardSetupStep.resolve(ready.copy(working = true), true))
    }

    @Test fun savedCheckpointIsResumedBeforePreparingAnotherCard() {
        val screen = ready.copy(planSaved = false, savedPlanAvailable = true, savedPlanRequiresResume = true)
        assertEquals(CardSetupStep.RESUME, CardSetupStep.resolve(screen, false))
        assertTrue(CardSetupStep.RESUME.enabled(screen, false, false))
        assertEquals(CardSetupStep.RELOAD, CardSetupStep.resolve(ready.copy(
            provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.AWAITING_RELOAD)), false))
    }

    @Test fun changingSelectedCardCannotApplyOrStartTheOldPlan() {
        for (state in listOf(NfcProvisionWorkflow.State.PREPARED, NfcProvisionWorkflow.State.RELOADED)) {
            val screen = ready.copy(provision = NfcProvisionWorkflow.Result(state), canActivate = true)
            assertEquals(CardSetupStep.CHECK, CardSetupStep.resolve(screen, false))
            assertFalse(CardSetupStep.APPLY.enabled(screen, false, true))
            assertFalse(CardSetupStep.START.enabled(screen, false, true))
        }
    }

    @Test fun dailyActionUsesObservedRegistrationNotSelectionAlone() {
        val registration = NfcCardRegistration(NfcCardRegistration.Availability.AVAILABLE, "88b4", idm.lowercase())
        assertEquals(CardUseState.READY, CardUseState.resolve(idm, registration, false))
        assertEquals(CardUseState.SETUP, CardUseState.resolve("012E001122334455", registration, false))
        assertEquals(CardUseState.SETUP, CardUseState.resolve(idm, registration.copy(systemCode = "4000"), false))
        assertEquals(CardUseState.NO_CARD, CardUseState.resolve(null, registration, false))
        assertEquals(CardUseState.CONTINUE, CardUseState.resolve(idm, registration, true))
        assertEquals(CardUseState.CONTINUE, CardUseState.resolve(null, registration, true))
    }

    @Test fun unavailableNfcIsNotAssumedToNeedIdentityRewriting() {
        for ((availability, action) in mapOf(NfcCardRegistration.Availability.NFC_OFF to CardUseState.NFC_OFF,
            NfcCardRegistration.Availability.UNSUPPORTED to CardUseState.UNSUPPORTED,
            NfcCardRegistration.Availability.UNKNOWN to CardUseState.UNKNOWN)) {
            assertEquals(action, CardUseState.resolve(idm, NfcCardRegistration(availability), false))
        }
    }
}
