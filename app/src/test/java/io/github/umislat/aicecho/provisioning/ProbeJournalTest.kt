package io.github.umislat.aicecho.provisioning

import android.app.Application
import android.content.Context
import io.github.umislat.aicecho.provisioning.RestoreProbeWorkflow.Result
import io.github.umislat.aicecho.provisioning.RestoreProbeWorkflow.State
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class)
class ProbeJournalTest {
    private val token = "a".repeat(32)
    private val preferences get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("probe-journal-test", Context.MODE_PRIVATE)
    @Before fun clear() { preferences.edit().clear().commit() }

    @Test fun persistedIntentSurvivesProcessLossAndBlocksAnotherRequest() {
        assertTrue(ProbeJournal(preferences, 4).begin(token))
        val restarted = ProbeJournal(preferences, 4)
        assertTrue(restarted.read().pending)
        assertEquals(token, restarted.read().token)
        assertThrows(IllegalStateException::class.java) { restarted.begin("b".repeat(32)) }
    }

    @Test fun onlyVerifiedCompletionOrKnownPreflightFailureResolvesIntent() {
        for (state in State.entries) {
            clear()
            val journal = ProbeJournal(preferences, 4)
            journal.begin(token)
            journal.complete(Result(state, token))
            assertEquals(state !in setOf(State.VERIFIED, State.FAILED), journal.read().pending)
        }
    }

    @Test fun staleNonceCannotUnlockAndUnknownBootCannotReset() {
        val journal = ProbeJournal(preferences, 4)
        journal.begin(token)
        assertFalse(journal.complete(Result(State.VERIFIED, "b".repeat(32))))
        assertTrue(journal.read().pending)
        assertTrue(ProbeJournal(preferences, -1).read().pending)
    }

    @Test fun knownRebootAllowsNewProbeButDoesNotPretendPreviousSuccess() {
        ProbeJournal(preferences, 4).begin(token)
        val nextBoot = ProbeJournal(preferences, 5)
        assertFalse(nextBoot.read().pending)
        assertTrue(nextBoot.begin("b".repeat(32)))
        assertTrue(nextBoot.read().pending)
    }

    @Test fun capabilityGateNeedsShellExpectedLabAndNoUnresolvedOperation() {
        val ready = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY,
            uid = 2000, bu = true, markerApp = true)
        assertTrue(ready.canStart)
        assertFalse(ready.copy(uid = 0).canStart)
        assertFalse(ready.copy(markerApp = false).canStart)
        assertFalse(ready.copy(bu = false).canStart)
        assertFalse(ready.copy(pending = true).canStart)
        assertFalse(ready.copy(working = true).canStart)
        assertFalse(ready.copy(probe = Result(State.CONFIRMING, token)).canStart)
    }

    @Test fun snapshotJournalOnlySettlesMatchingCompletedOperations() {
        for (state in NfcSnapshotWorkflow.State.entries) {
            clear()
            val journal = ProbeJournal(preferences, 4)
            journal.begin(token)
            val result = NfcSnapshotWorkflow.Result(state, token)
            assertFalse(journal.completeSnapshot(result.copy(token = "b".repeat(32))))
            assertTrue(journal.read().pending)
            journal.completeSnapshot(result)
            assertEquals(!result.settled, journal.read().pending)
        }
    }

    @Test fun snapshotAndRestoreCannotStartWhileEitherIsPendingOrBusy() {
        val ready = ShizukuProbeModel.Screen(connection = ShizukuProbeModel.Connection.READY,
            uid = 2000, bu = true, markerApp = true, snapshotCandidate = true)
        assertTrue(ready.canCapture)
        assertFalse(ready.copy(snapshotCandidate = false).canCapture)
        assertFalse(ready.copy(uid = 0).canCapture)
        for (blocked in listOf(ready.copy(pending = true), ready.copy(snapshotPending = true),
            ready.copy(working = true), ready.copy(probe = Result(State.CONFIRMING, token)),
            ready.copy(probe = Result(State.UNKNOWN, token)),
            ready.copy(snapshot = NfcSnapshotWorkflow.Result(NfcSnapshotWorkflow.State.UNKNOWN, token)),
            ready.copy(snapshot = NfcSnapshotWorkflow.Result(NfcSnapshotWorkflow.State.EXPORTING, token)))) {
            assertFalse(blocked.canStart)
            assertFalse(blocked.canCapture)
        }
        assertFalse(ready.copy(pending = true, snapshotPending = true,
            probe = Result(State.UNKNOWN, token)).canVerify)
    }
}
