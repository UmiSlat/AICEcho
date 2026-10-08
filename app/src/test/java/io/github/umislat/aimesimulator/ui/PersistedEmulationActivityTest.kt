package io.github.umislat.aimesimulator.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.NfcFCardEmulation
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.umislat.aimesimulator.BuildConfig
import io.github.umislat.aimesimulator.R
import io.github.umislat.aimesimulator.nfc.AimeHostService
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
    shadows = [PersistedEmulationActivityTest.NfcFShadow::class, PersistedEmulationActivityTest.ApplicationShadow::class])
class PersistedEmulationActivityTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val idm = "012E112233445566"

    @Before fun setUp() {
        NfcFShadow.calls.clear()
        NfcFShadow.idm = idm
        NfcFShadow.systemCode = "88B4"
        NfcFShadow.enableResult = true
        ApplicationShadow.processName = app.packageName + PersistedEmulationActivity.PROCESS_SUFFIX
        ShadowProcess.setPid(4321)
        ShadowProcess.clearKilledProcesses()
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF, true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
        app.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun manifestIsPrivateAndSeparatesOnlyTheForegroundActivity() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, PersistedEmulationActivity::class.java), 0)
        assertFalse(info.exported)
        assertEquals(app.packageName + ":nfc_session", info.processName)
        val service = app.packageManager.getServiceInfo(ComponentName(app, AimeHostService::class.java), 0)
        assertEquals(app.packageName, service.processName)
    }

    @Test fun activationOnlyReadsSavedIdentityAndReleasesOnPause() {
        val controller = controller().setup()
        try {
            assertEquals(listOf("disable", "systemCode", "nfcid2", "enable"), NfcFShadow.calls)
            assertTrue(texts(controller.get()).contains(app.getString(R.string.shizuku_card_enabled)))
            assertTrue(app.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE).all.isEmpty())
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
            assertTrue(texts(controller.get()).any { it.contains("ENABLE_FAILED") })
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

    private fun controller() = Robolectric.buildActivity(PersistedEmulationActivity::class.java,
        PersistedEmulationActivity.intent(app, "Synthetic", idm))

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
        @Implementation fun getSystemCodeForService(component: ComponentName): String {
            assertEquals(AimeHostService::class.java.name, component.className)
            calls += "systemCode"
            return systemCode
        }
        @Implementation fun getNfcid2ForService(component: ComponentName): String {
            calls += "nfcid2"
            return idm
        }
        @Implementation fun enableService(activity: Activity, component: ComponentName): Boolean {
            assertEquals(AimeHostService::class.java.name, component.className)
            calls += "enable"
            return enableResult
        }
        @Implementation fun setNfcid2ForService(component: ComponentName, id: String): Boolean =
            throw AssertionError("The saved route must not be registered again")
        @Implementation fun registerSystemCodeForService(component: ComponentName, code: String): Boolean =
            throw AssertionError("The saved system code must not be registered again")

        companion object {
            val calls = mutableListOf<String>()
            var idm = ""
            var systemCode = ""
            var enableResult = true
            @JvmStatic @Implementation fun getInstance(adapter: NfcAdapter): NfcFCardEmulation =
                Shadow.newInstanceOf(NfcFCardEmulation::class.java)
        }
    }
}
