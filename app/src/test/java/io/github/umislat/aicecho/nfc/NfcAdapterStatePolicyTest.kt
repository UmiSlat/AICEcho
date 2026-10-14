package io.github.umislat.aicecho.nfc

import android.nfc.NfcAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

class NfcAdapterStatePolicyTest {
    @Test fun marksOffAndTurningOffAsDisabled() {
        assertEquals(
            NfcAdapterStatePolicy.Action.MARK_DISABLED,
            NfcAdapterStatePolicy.actionFor(NfcAdapter.STATE_OFF)
        )
        assertEquals(
            NfcAdapterStatePolicy.Action.MARK_DISABLED,
            NfcAdapterStatePolicy.actionFor(NfcAdapter.STATE_TURNING_OFF)
        )
    }

    @Test fun marksTurningOnAsEnabling() {
        assertEquals(
            NfcAdapterStatePolicy.Action.MARK_ENABLING,
            NfcAdapterStatePolicy.actionFor(NfcAdapter.STATE_TURNING_ON)
        )
    }

    @Test fun activatesWhenAdapterIsOn() {
        assertEquals(
            NfcAdapterStatePolicy.Action.ACTIVATE,
            NfcAdapterStatePolicy.actionFor(NfcAdapter.STATE_ON)
        )
    }

    @Test fun ignoresUnknownStates() {
        assertEquals(
            NfcAdapterStatePolicy.Action.IGNORE,
            NfcAdapterStatePolicy.actionFor(Int.MIN_VALUE)
        )
    }

    @Test fun verifiesStableBroadcastsAgainstCurrentEnabledState() {
        assertEquals(
            NfcAdapterStatePolicy.Action.MARK_DISABLED,
            NfcAdapterStatePolicy.actionFor(NfcAdapter.STATE_ON, adapterEnabled = false)
        )
        assertEquals(
            NfcAdapterStatePolicy.Action.ACTIVATE,
            NfcAdapterStatePolicy.actionFor(NfcAdapter.STATE_OFF, adapterEnabled = true)
        )
    }
}
