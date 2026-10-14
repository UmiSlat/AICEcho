package io.github.umislat.aicecho.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.NfcFCardEmulation
import android.os.DeadObjectException
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.umislat.aicecho.BuildConfig
import io.github.umislat.aicecho.R
import io.github.umislat.aicecho.nfc.AicHostService
import io.github.umislat.aicecho.nfc.NfcSessionEvent
import io.github.umislat.aicecho.nfc.NfcFListenSessionTest.DiscoveryShadow
import java.time.Duration
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class, qualifiers = "w360dp-h640dp",
    shadows = [PersistedEmulationActivityTest.NfcFShadow::class, PersistedEmulationActivityTest.ApplicationShadow::class,
        DiscoveryShadow::class])
class PersistedEmulationActivityTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val idm = "012E112233445566"

    @Before fun setUp() {
        DiscoveryShadow.clear()
        NfcFShadow.calls.clear()
        NfcFShadow.idm = idm
        NfcFShadow.systemCode = "88B4"
        NfcFShadow.enableResult = true
        NfcFShadow.failure = null
        NfcFShadow.onEnable = null
        ApplicationShadow.processName = app.packageName + PersistedEmulationActivity.PROCESS_SUFFIX
        ShadowProcess.setPid(4321)
        ShadowProcess.clearKilledProcesses()
        ShadowNfcAdapter.setNfcHardwareExists(true)
        val receiverPermission = app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        assertEquals(android.content.pm.PermissionInfo.PROTECTION_SIGNATURE,
            app.packageManager.getPermissionInfo(receiverPermission, 0).protection)
        shadowOf(app).grantPermissions(receiverPermission)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF, true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        app.getSharedPreferences("aicecho", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun manifestIsPrivateAndSeparatesOnlyTheForegroundActivity() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, PersistedEmulationActivity::class.java), 0)
        assertFalse(info.exported)
        assertEquals(app.packageName + ":nfc_session", info.processName)
        val service = app.packageManager.getServiceInfo(ComponentName(app, AicHostService::class.java), 0)
        assertEquals(app.packageName, service.processName)
    }

    @Test fun activationOnlyReadsSavedIdentityAndReleasesOnPause() {
        val controller = controller().setup()
        try {
            assertEquals(listOf("disable", "systemCode", "nfcid2", "enable"), NfcFShadow.calls)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
            assertTrue(app.getSharedPreferences("aicecho", Context.MODE_PRIVATE).all.isEmpty())
            controller.pause()
            assertEquals("disable", NfcFShadow.calls.last())
            assertEquals(2, NfcFShadow.calls.count { it == "disable" })
            controller.stop()
            assertTrue(controller.get().isFinishing)
        } finally { controller.destroy() }
        assertTrue(ShadowProcess.wasKilled(4321))
    }

    @Test fun missingOrMalformedIdentityNeverTouchesNfc() {
        for (id in listOf(null, "012E", "012E11223344556Z")) {
            val intent = Intent(app, PersistedEmulationActivity::class.java).putExtra(PersistedEmulationActivity.EXTRA_NFCID2, id)
            val controller = Robolectric.buildActivity(PersistedEmulationActivity::class.java, intent).setup()
            try {
                assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_invalid)))
            } finally { controller.pause().stop().destroy() }
            assertTrue(NfcFShadow.calls.isEmpty())
        }
    }

    @Test fun changedMappingNeverEnablesOrRegisters() {
        for ((code, id) in listOf(
            "4000" to idm,
            "88B4" to "02FE112233445566"
        )) {
            NfcFShadow.calls.clear()
            NfcFShadow.systemCode = code
            NfcFShadow.idm = id
            val controller = controller().setup()
            try {
                assertFalse(NfcFShadow.calls.contains("enable"))
                assertTrue(texts(controller.get()).contains(app.getString(R.string.emulation_setup_required)))
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun failedEnableDoesNotShowSuccessAndReleasesForeground() {
        NfcFShadow.enableResult = false
        val controller = controller().setup()
        try {
            assertEquals(listOf("disable", "systemCode", "nfcid2", "enable", "disable"), NfcFShadow.calls)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.simple_emulation_failed)))
            assertFalse(texts(controller.get()).any { it.contains("ENABLE_FAILED") })
            assertFalse(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun advancedSessionKeepsFailureDetails() {
        NfcFShadow.enableResult = false
        val controller = controller(details = true).setup()
        try {
            assertTrue(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enable_failed, "ENABLE_FAILED")))
            assertFalse(texts(controller.get()).contains(app.getString(R.string.simple_emulation_failed)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun rotationKeepsProcessAndRechecksIdentity() {
        val controller = controller().setup()
        try {
            controller.recreate()
            assertFalse(ShadowProcess.wasKilled(4321))
            assertFalse(controller.get().isFinishing)
            assertEquals(2, NfcFShadow.calls.count { it == "enable" })
        } finally { controller.pause().stop().destroy() }
        assertTrue(ShadowProcess.wasKilled(4321))
    }

    @Test fun closingCannotKillMainOrAnyOtherProcess() {
        for (name in listOf(app.packageName, app.packageName + ":shizuku")) {
            ApplicationShadow.processName = name
            val controller = controller().setup()
            controller.get().finish()
            controller.pause().stop().destroy()
            assertFalse(ShadowProcess.wasKilled(4321))
        }
    }

    @Test fun processIsNotRecycledUntilItsLastActivityFinishes() {
        val first = controller().setup()
        val second = controller().setup()
        first.pause().stop().destroy()
        assertFalse(ShadowProcess.wasKilled(4321))
        second.pause().stop().destroy()
        assertTrue(ShadowProcess.wasKilled(4321))
    }

    @Test fun ordinarySessionHidesTechnicalIdentityAndMismatchLinksToSetup() {
        NfcFShadow.systemCode = "4000"
        val controller = controller().setup()
        try {
            assertFalse(texts(controller.get()).any { it.contains(idm) })
            val setup = views(controller.get().findViewById(android.R.id.content))
                .filterIsInstance<com.google.android.material.button.MaterialButton>()
                .single { it.text == app.getString(R.string.emulation_setup) }
            assertEquals(View.VISIBLE, setup.visibility)
            setup.performClick()
            val launched = shadowOf(controller.get()).nextStartedActivity
            assertEquals(ShizukuProvisioningActivity::class.java.name, launched.component!!.className)
            assertTrue(launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            assertTrue(launched.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun adapterPowerCycleClearsSuccessAndEnablesAgainWithoutRegistration() {
        val controller = controller().setup()
        try {
            val adapter = shadowOf(NfcAdapter.getDefaultAdapter(app))
            adapter.setEnabled(false)
            adapterState(NfcAdapter.STATE_OFF)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.nfc_disabled)))
            assertFalse(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
            adapter.setEnabled(true)
            adapterState(NfcAdapter.STATE_ON)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
            assertEquals(2, NfcFShadow.calls.count { it == "enable" })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun healthyMonitoringOnlyReadsAndDoesNotInterruptTheReader() {
        val controller = controller().setup()
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PersistedEmulationActivity.INSPECT_DELAY_MS))
            assertEquals(1, NfcFShadow.calls.count { it == "enable" })
            assertEquals(1, NfcFShadow.calls.count { it == "disable" })
            assertEquals(2, NfcFShadow.calls.count { it == "nfcid2" })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun monitoringDetectsSilentPowerLossOrChangedIdentity() {
        for (powerLost in listOf(true, false)) {
            val controller = controller().setup()
            try {
                if (powerLost) shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(false)
                else NfcFShadow.systemCode = "4000"
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PersistedEmulationActivity.INSPECT_DELAY_MS))
                assertFalse(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
                assertTrue(texts(controller.get()).contains(app.getString(if (powerLost)
                    R.string.nfc_disabled else R.string.emulation_setup_required)))
            } finally { controller.pause().stop().destroy() }
            shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
            NfcFShadow.systemCode = "88B4"
        }
    }

    @Test fun temporaryBinderLossRecoversAndPermanentLossStopsRetrying() {
        val controller = controller().setup()
        try {
            NfcFShadow.failure = IllegalStateException("NFC service restarting", DeadObjectException())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PersistedEmulationActivity.INSPECT_DELAY_MS))
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_recovering)))
            NfcFShadow.failure = null
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PersistedEmulationActivity.RECOVERY_DELAY_MS))
            assertTrue(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
            NfcFShadow.failure = IllegalStateException("NFC service restarting", DeadObjectException())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PersistedEmulationActivity.INSPECT_DELAY_MS +
                (PersistedEmulationActivity.MAX_RECOVERY_ATTEMPTS + 2) * PersistedEmulationActivity.RECOVERY_DELAY_MS))
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_recovery_failed)))
            val calls = NfcFShadow.calls.size
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            assertEquals(calls, NfcFShadow.calls.size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun missingGetterResultsWaitForServiceInsteadOfReconfiguring() {
        NfcFShadow.idm = null
        val controller = controller().setup()
        try {
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_recovering)))
            assertFalse(texts(controller.get()).contains(app.getString(R.string.emulation_setup_required)))
            NfcFShadow.idm = idm
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PersistedEmulationActivity.RECOVERY_DELAY_MS))
            assertTrue(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun reconnectAndReaderFeedbackDoNotChangeProfilesOrRegisterIdentity() {
        val controller = controller(details = true).setup()
        try {
            traffic(responded = true)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_reader_seen)))
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_traffic, 1, 1)))
            button(controller.get(), R.string.persisted_emulation_reconnect).performClick()
            assertTrue(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_traffic, 0, 0)))
            traffic(responded = false)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_traffic, 1, 0)))
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_reader_unanswered)))
            assertEquals(2, NfcFShadow.calls.count { it == "enable" })
            assertTrue(app.getSharedPreferences("aicecho", Context.MODE_PRIVATE).all.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ordinaryModeHidesTrafficCountsAndOldEventsCannotClaimReaderContact() {
        val controller = controller().setup()
        try {
            traffic(responded = true, elapsed = -1)
            assertFalse(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_reader_seen)))
            val count = views(controller.get().findViewById(android.R.id.content)).filterIsInstance<TextView>()
                .single { it.text == app.getString(R.string.persisted_emulation_traffic, 0, 0) }
            assertEquals(View.GONE, count.visibility)
            traffic(responded = true)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_reader_seen)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun readerTrafficDuringActivationIsNotDiscardedAsAnOldSession() {
        NfcFShadow.onEnable = {
            app.sendBroadcast(Intent(NfcSessionEvent.ACTION).setPackage(app.packageName)
                .putExtra(NfcSessionEvent.TIME, SystemClock.elapsedRealtime())
                .putExtra(NfcSessionEvent.RESPONDED, true))
            SystemClock.sleep(10)
        }
        val controller = controller().setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_reader_seen)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pausedActivityCannotReactivateFromTimersOrBroadcasts() {
        val controller = controller().setup()
        try {
            NfcFShadow.failure = IllegalStateException("NFC service restarting", DeadObjectException())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PersistedEmulationActivity.INSPECT_DELAY_MS))
            controller.pause()
            val calls = NfcFShadow.calls.size
            adapterState(NfcAdapter.STATE_ON)
            traffic(responded = true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            assertEquals(calls, NfcFShadow.calls.size)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.emulation_stopped)))
        } finally { controller.stop().destroy() }
    }

    @Test fun listeningIsRestrictedBeforeEnableAndRemainsAfterSuccessfulReads() {
        NfcFShadow.onEnable = {
            if (Build.VERSION.SDK_INT >= 35) assertEquals(listOf("restrict"), DiscoveryShadow.calls)
            else assertTrue(DiscoveryShadow.calls.isEmpty())
        }
        val controller = controller().setup()
        try {
            repeat(3) {
                traffic(responded = true)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PersistedEmulationActivity.INSPECT_DELAY_MS))
            }
            assertEquals(1, NfcFShadow.calls.count { it == "enable" })
            assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict") else emptyList<String>(),
                DiscoveryShadow.calls)
            controller.pause()
            assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict", "reset") else emptyList<String>(),
                DiscoveryShadow.calls)
        } finally { controller.stop().destroy() }
    }

    @Test fun invalidOrMismatchedIdentityNeverChangesDiscoveryTechnology() {
        NfcFShadow.systemCode = "4000"
        val controller = controller().setup()
        try {
            assertTrue(DiscoveryShadow.calls.isEmpty())
            assertFalse(NfcFShadow.calls.contains("enable"))
        } finally { controller.pause().stop().destroy() }
        assertTrue(DiscoveryShadow.calls.isEmpty())
    }

    @Test fun failedActivationImmediatelyRestoresDefaultListening() {
        NfcFShadow.enableResult = false
        val controller = controller().setup()
        try {
            assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict", "reset") else emptyList<String>(),
                DiscoveryShadow.calls)
            assertFalse(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun manualReconnectRefreshesListeningWithoutResettingToNfcAFirst() {
        val controller = controller().setup()
        try {
            button(controller.get(), R.string.persisted_emulation_reconnect).performClick()
            assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict", "restrict") else emptyList<String>(),
                DiscoveryShadow.calls)
            assertEquals(2, NfcFShadow.calls.count { it == "enable" })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun powerCycleReleasesAndReappliesForegroundListening() {
        val controller = controller().setup()
        try {
            val adapter = shadowOf(NfcAdapter.getDefaultAdapter(app))
            adapter.setEnabled(false)
            adapterState(NfcAdapter.STATE_OFF)
            adapter.setEnabled(true)
            adapterState(NfcAdapter.STATE_ON)
            assertEquals(if (Build.VERSION.SDK_INT >= 35) listOf("restrict", "reset", "restrict") else emptyList<String>(),
                DiscoveryShadow.calls)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unavailableListenApiKeepsTheExistingSavedRouteUsable() {
        DiscoveryShadow.failure = UnsupportedOperationException("Vendor API unavailable")
        val controller = controller().setup()
        try {
            assertTrue(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
            traffic(responded = true)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.persisted_emulation_reader_seen)))
            assertTrue(app.getSharedPreferences("aicecho", Context.MODE_PRIVATE).all.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    private fun adapterState(state: Int) {
        app.sendBroadcast(Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED).putExtra(NfcAdapter.EXTRA_ADAPTER_STATE, state))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun traffic(responded: Boolean, elapsed: Long = SystemClock.elapsedRealtime()) {
        app.sendBroadcast(Intent(NfcSessionEvent.ACTION).setPackage(app.packageName)
            .putExtra(NfcSessionEvent.TIME, elapsed).putExtra(NfcSessionEvent.RESPONDED, responded))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun button(activity: Activity, label: Int) = views(activity.findViewById(android.R.id.content))
        .filterIsInstance<com.google.android.material.button.MaterialButton>()
        .single { it.text == activity.getString(label) }

    private fun controller(details: Boolean = false) = Robolectric.buildActivity(PersistedEmulationActivity::class.java,
        PersistedEmulationActivity.intent(app, "Synthetic", idm, details))

    private fun texts(activity: Activity) = views(activity.findViewById(android.R.id.content))
        .filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    @Implements(Application::class)
    class ApplicationShadow : ShadowApplication() {
        companion object {
            @JvmField var processName = BuildConfig.APPLICATION_ID
            @JvmStatic @Implementation fun getProcessName(): String = processName
        }
    }

    @Implements(NfcFCardEmulation::class)
    class NfcFShadow {
        @Implementation fun disableService(activity: Activity): Boolean {
            calls += "disable"
            return true
        }
        @Implementation fun getSystemCodeForService(component: ComponentName): String? {
            assertEquals(AicHostService::class.java.name, component.className)
            calls += "systemCode"
            failure?.let { throw it }
            return systemCode
        }
        @Implementation fun getNfcid2ForService(component: ComponentName): String? {
            calls += "nfcid2"
            return idm
        }
        @Implementation fun enableService(activity: Activity, component: ComponentName): Boolean {
            assertEquals(AicHostService::class.java.name, component.className)
            calls += "enable"
            onEnable?.invoke()
            return enableResult
        }
        @Implementation fun setNfcid2ForService(component: ComponentName, id: String): Boolean =
            throw AssertionError("The saved route must not be registered again")
        @Implementation fun registerSystemCodeForService(component: ComponentName, code: String): Boolean =
            throw AssertionError("The saved system code must not be registered again")

        companion object {
            val calls = mutableListOf<String>()
            var idm: String? = ""
            var systemCode: String? = ""
            var enableResult = true
            var failure: RuntimeException? = null
            var onEnable: (() -> Unit)? = null
            @JvmStatic @Implementation fun getInstance(adapter: NfcAdapter): NfcFCardEmulation =
                Shadow.newInstanceOf(NfcFCardEmulation::class.java)
        }
    }
}
