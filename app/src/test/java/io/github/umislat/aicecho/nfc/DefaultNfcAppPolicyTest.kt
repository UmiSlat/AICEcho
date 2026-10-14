package io.github.umislat.aicecho.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNfcAppPolicyTest {
    @Test fun firstEligibleStatusShowsGuidance() {
        assertTrue(
            DefaultNfcAppPolicy.shouldShowGuidance(
                DefaultNfcAppPolicy.State.NOT_DEFAULT,
                guidanceShown = false
            )
        )
        assertTrue(
            DefaultNfcAppPolicy.shouldShowGuidance(
                DefaultNfcAppPolicy.State.DEFAULT,
                guidanceShown = false
            )
        )
    }

    @Test fun unavailableStatusWaitsAndCompletedGuidanceDoesNotRepeat() {
        assertFalse(
            DefaultNfcAppPolicy.shouldShowGuidance(
                DefaultNfcAppPolicy.State.UNAVAILABLE,
                guidanceShown = false
            )
        )
        assertFalse(
            DefaultNfcAppPolicy.shouldShowGuidance(
                DefaultNfcAppPolicy.State.NOT_DEFAULT,
                guidanceShown = true
            )
        )
    }

    @Test fun primaryActionMatchesCurrentDefaultState() {
        assertEquals(
            DefaultNfcAppPolicy.Action.REQUEST_DEFAULT,
            DefaultNfcAppPolicy.actionFor(DefaultNfcAppPolicy.State.NOT_DEFAULT)
        )
        assertEquals(
            DefaultNfcAppPolicy.Action.RESTORE_WALLET,
            DefaultNfcAppPolicy.actionFor(DefaultNfcAppPolicy.State.DEFAULT)
        )
        assertEquals(
            DefaultNfcAppPolicy.Action.NONE,
            DefaultNfcAppPolicy.actionFor(DefaultNfcAppPolicy.State.UNAVAILABLE)
        )
    }
}
