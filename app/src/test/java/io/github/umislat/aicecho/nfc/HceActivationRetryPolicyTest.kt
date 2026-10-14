package io.github.umislat.aicecho.nfc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HceActivationRetryPolicyTest {
    @Test fun retriesIdentifierFailureImmediatelyAfterSuccessfulActivation() {
        assertTrue(
            HceActivationRetryPolicy.isLikelyActiveLink(
                report(HceSession.Stage.ID),
                report(HceSession.Stage.READY),
                retryingLinkRelease = false
            )
        )
    }

    @Test fun keepsRetryingIdentifierAndSystemCodeWhileWaitingForLinkRelease() {
        assertTrue(
            HceActivationRetryPolicy.isLikelyActiveLink(
                report(HceSession.Stage.ID),
                report(HceSession.Stage.LINK_ACTIVE),
                retryingLinkRelease = false
            )
        )
        assertTrue(
            HceActivationRetryPolicy.isLikelyActiveLink(
                report(HceSession.Stage.SYSTEM_CODE),
                previousReport = null,
                retryingLinkRelease = true
            )
        )
    }

    @Test fun firstIdentifierFailureRemainsARealCompatibilityResult() {
        assertFalse(
            HceActivationRetryPolicy.isLikelyActiveLink(
                report(HceSession.Stage.ID),
                previousReport = null,
                retryingLinkRelease = false
            )
        )
    }

    @Test fun serviceRestartRetryDoesNotReclassifyIdentifierFailure() {
        assertFalse(
            HceActivationRetryPolicy.isLikelyActiveLink(
                report(HceSession.Stage.ID),
                report(HceSession.Stage.SERVICE_RESTARTING),
                retryingLinkRelease = false
            )
        )
    }

    @Test fun unrelatedFailuresAreNeverClassifiedAsAnActiveLink() {
        assertFalse(
            HceActivationRetryPolicy.isLikelyActiveLink(
                report(HceSession.Stage.ENABLE),
                report(HceSession.Stage.READY),
                retryingLinkRelease = true
            )
        )
    }

    private fun report(stage: HceSession.Stage) = HceSession.Report(stage)
}
