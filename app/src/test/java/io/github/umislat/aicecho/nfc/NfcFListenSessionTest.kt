package io.github.umislat.aicecho.nfc

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNfcAdapter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36], application = Application::class,
    shadows = [NfcFListenSessionTest.DiscoveryShadow::class])
class NfcFListenSessionTest {
    @Before fun setUp() {
        DiscoveryShadow.clear()
        ShadowNfcAdapter.setNfcHardwareExists(true)
        val app = RuntimeEnvironment.getApplication()
        for (feature in listOf(PackageManager.FEATURE_NFC,
            PackageManager.FEATURE_NFC_HOST_CARD_EMULATION,
            PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF)) {
            shadowOf(app.packageManager).setSystemFeature(feature, true)
        }
        val adapter = NfcAdapter.getDefaultAdapter(app)
        assertNotNull(adapter)
        shadowOf(adapter).setEnabled(true)
    }

    @Test fun restrictionKeepsPollingAndRequestsOnlyNfcFWhileForegrounded() = withSession { session, activity ->
        session.restrict()
        if (Build.VERSION.SDK_INT >= 35) {
            assertEquals(listOf("restrict"), DiscoveryShadow.calls)
            assertEquals(listOf(NfcAdapter.FLAG_READER_KEEP to NfcAdapter.FLAG_LISTEN_NFC_PASSIVE_F),
                DiscoveryShadow.masks)
            assertSame(activity, DiscoveryShadow.activities.single())
        } else assertTrue(DiscoveryShadow.calls.isEmpty())
    }

    @Test fun repeatedRestrictionDoesNotRestartDiscovery() = withSession { session, _ ->
        repeat(4) { session.restrict() }
        assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict") else emptyList<String>(),
            DiscoveryShadow.calls)
    }

    @Test fun explicitRefreshReappliesWithoutOpeningDefaultListeningBetweenRequests() = withSession { session, _ ->
        session.restrict()
        session.restrict(refresh = true)
        assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict", "restrict") else emptyList<String>(),
            DiscoveryShadow.calls)
    }

    @Test fun releaseRestoresDefaultsOnceAndNextSessionCanRestrictAgain() = withSession { session, _ ->
        session.restrict()
        session.release()
        session.release()
        session.restrict()
        assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict", "reset", "restrict") else emptyList<String>(),
            DiscoveryShadow.calls)
    }

    @Test fun rejectedRestrictionResetsPartialStateAndFallsBackWithoutAutomaticRetries() = withSession { session, _ ->
        DiscoveryShadow.failure = UnsupportedOperationException("Unsupported discovery filter")
        session.restrict()
        session.restrict()
        session.release()
        assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict", "reset") else emptyList<String>(),
            DiscoveryShadow.calls)
    }

    @Test fun missingFlaggedApiDoesNotCrashOrAttemptToResetAnUnmadeRequest() = withSession { session, _ ->
        DiscoveryShadow.failure = NoSuchMethodError("Flagged API not available")
        session.restrict()
        session.restrict()
        session.release()
        assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict") else emptyList<String>(),
            DiscoveryShadow.calls)
    }

    @Test fun resetFailureDoesNotCrashOrRepeatAnOperationAfterPause() = withSession { session, _ ->
        session.restrict()
        DiscoveryShadow.resetFailure = IllegalStateException("NFC restarting")
        session.release()
        session.release()
        assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict", "reset") else emptyList<String>(),
            DiscoveryShadow.calls)
    }

    private fun withSession(test: (NfcFListenSession, Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val session = NfcFListenSession(controller.get())
        try { test(session, controller.get()) }
        finally {
            session.release()
            controller.pause().stop().destroy()
        }
    }

    @Implements(NfcAdapter::class)
    class DiscoveryShadow : ShadowNfcAdapter() {
        @Implementation(minSdk = 35)
        fun setDiscoveryTechnology(activity: Activity, poll: Int, listen: Int) {
            calls += "restrict"
            masks += poll to listen
            activities += activity
            failure?.let { throw it }
        }

        @Implementation(minSdk = 35)
        fun resetDiscoveryTechnology(activity: Activity) {
            calls += "reset"
            activities += activity
            resetFailure?.let { throw it }
        }

        companion object {
            val calls = mutableListOf<String>()
            val masks = mutableListOf<Pair<Int, Int>>()
            val activities = mutableListOf<Activity>()
            var failure: Throwable? = null
            var resetFailure: Throwable? = null

            fun clear() {
                calls.clear()
                masks.clear()
                activities.clear()
                failure = null
                resetFailure = null
            }
        }
    }
}
