package io.github.umislat.aimesimulator.provisioning

import android.app.Application
import android.content.ServiceConnection
import android.content.ComponentName
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.cardemulation.NfcFCardEmulation
import android.content.pm.PackageManager
import android.os.Looper
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNfcAdapter
import org.robolectric.shadow.api.Shadow
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.ui.ShizukuProvisioningActivity
import java.time.Duration
import rikka.shizuku.Shizuku

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class,
    shadows = [ShizukuAutoConnectionTest.ShizukuShadow::class, ShizukuAutoConnectionTest.BaselineNfcShadow::class])
class ShizukuAutoConnectionTest {
    @Before fun reset() {
        ShizukuShadow.reset()
        BaselineNfcShadow.writes.clear()
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("aime_simulator", "shizuku_nfc_provision", "shizuku_nfc_snapshot", "shizuku_restore_probe")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        java.io.File(app.noBackupFilesDir, "nfc-provision-plan.json").delete()
        ShadowNfcAdapter.setNfcHardwareExists(true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF, true)
        shadowOf(NfcAdapter.getDefaultAdapter(app)).setEnabled(true)
    }

    @Test fun authorizedReentryBindsWithoutAnotherPermissionRequest() {
        repeat(2) {
            val model = ShizukuProbeModel(RuntimeEnvironment.getApplication())
            val owner = ViewModelStore().apply { put("model", model) }
            model.onForeground()
            shadowOf(Looper.getMainLooper()).idle()
            model.onForeground()
            assertEquals(it + 1, ShizukuShadow.binds)
            assertEquals(ShizukuProbeModel.Connection.CONNECTING, model.screen.value!!.connection)
            assertEquals(0, ShizukuShadow.permissionRequests)
            model.onBackground()
            owner.clear()
        }
        assertEquals(2, ShizukuShadow.unbinds)
    }

    @Test fun openingWithoutPermissionNeverRequestsItAutomatically() {
        ShizukuShadow.granted = false
        val model = ShizukuProbeModel(RuntimeEnvironment.getApplication())
        val owner = ViewModelStore().apply { put("model", model) }
        try {
            model.onForeground()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ShizukuProbeModel.Connection.AVAILABLE, model.screen.value!!.connection)
            assertEquals(0, ShizukuShadow.binds)
            assertEquals(0, ShizukuShadow.permissionRequests)
            model.connect()
            assertEquals(1, ShizukuShadow.permissionRequests)
            ShizukuShadow.granted = true
            ShizukuShadow.permission!!.onRequestPermissionResult(8804, PackageManager.PERMISSION_GRANTED)
            assertEquals(1, ShizukuShadow.binds)
        } finally { owner.clear() }
    }

    @Test fun serverArrivalConnectsOnlyAnActivePage() {
        ShizukuShadow.running = false
        val model = ShizukuProbeModel(RuntimeEnvironment.getApplication())
        val owner = ViewModelStore().apply { put("model", model) }
        try {
            model.onForeground()
            assertEquals(ShizukuProbeModel.Connection.MISSING, model.screen.value!!.connection)
            model.onBackground()
            ShizukuShadow.running = true
            ShizukuShadow.received!!.onBinderReceived()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, ShizukuShadow.binds)
            model.onForeground()
            assertEquals(1, ShizukuShadow.binds)
        } finally { owner.clear() }
    }

    @Test fun missingOrRootServerNeverBinds() {
        for ((running, uid, expected) in listOf(
            Triple(false, 2000, ShizukuProbeModel.Connection.MISSING),
            Triple(true, 0, ShizukuProbeModel.Connection.ROOT)
        )) {
            ShizukuShadow.running = running
            ShizukuShadow.serverUid = uid
            val model = ShizukuProbeModel(RuntimeEnvironment.getApplication())
            val owner = ViewModelStore().apply { put("model", model) }
            try {
                model.onForeground()
                assertEquals(expected, model.screen.value!!.connection)
                assertEquals(0, ShizukuShadow.binds)
                assertEquals(0, ShizukuShadow.permissionRequests)
            } finally { owner.clear() }
        }
    }

    @Test fun automaticConnectionDoesNotStartSetupOrCreateRegistration() {
        val remote = FakeRemote()
        ShizukuShadow.remote = remote
        val model = ShizukuProbeModel(RuntimeEnvironment.getApplication())
        val owner = ViewModelStore().apply { put("model", model) }
        try {
            model.onForeground()
            await { model.screen.value!!.canPrepare }
            assertEquals(0, remote.prepares)
            assertTrue(BaselineNfcShadow.writes.isEmpty())
            assertEquals(0, ShizukuShadow.permissionRequests)
        } finally { owner.clear() }
    }

    @Test fun explicitGuidedSetupHandlesInitialRegistrationButNeverRestoresAutomatically() {
        val app = RuntimeEnvironment.getApplication()
        val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
        CardStore(app).apply { put(card); select(card.profileId) }
        val remote = FakeRemote()
        ShizukuShadow.remote = remote
        val model = ShizukuProbeModel(app)
        val owner = ViewModelStore().apply { put("model", model) }
        try {
            model.onForeground()
            await { model.screen.value!!.canPrepare }
            model.beginSetup()
            await { model.screen.value!!.canActivate }
            assertEquals(2, remote.prepares)
            assertEquals(listOf("02FE000000000000", "4000"), BaselineNfcShadow.writes)
            assertEquals(card, CardStore(app).selectedProfile())
            assertEquals(0, remote.restores)
        } finally { owner.clear() }
    }

    @Test fun homepageSetupRequestContinuesAfterAutoConnectionAndSurvivesRotation() {
        val app = RuntimeEnvironment.getApplication()
        val card = requireNotNull(CardProfile.create("Synthetic", "012E112233445566"))
        CardStore(app).apply { put(card); select(card.profileId) }
        val remote = FakeRemote()
        ShizukuShadow.remote = remote
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java,
            ShizukuProvisioningActivity.intent(app, beginSetup = true)).setup()
        try {
            val model = ViewModelProvider(controller.get())[ShizukuProbeModel::class.java]
            await { model.screen.value!!.canActivate }
            assertEquals(2, remote.prepares)
            assertEquals(0, remote.restores)
            controller.recreate()
            await { !model.screen.value!!.working }
            assertEquals(2, remote.prepares)
            assertEquals(1, ShizukuShadow.binds)
            assertEquals(0, ShizukuShadow.permissionRequests)
        } finally { controller.pause().stop().destroy() }
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
            Thread.sleep(10)
        }
        assertTrue("Model did not reach expected state", condition())
    }

    class FakeRemote : IHceShellService.Stub() {
        @Volatile var prepares = 0
        var restores = 0
        private var provision = Bundle().apply { putString("state", "IDLE"); putString("token", "") }
        override fun inspect() = Bundle().apply { putInt("uid", 2000); putBoolean("bu", true); putBoolean("nfcProvisioning", true) }
        override fun probeStatus() = Bundle().apply { putString("state", "IDLE"); putString("token", "") }
        override fun snapshotStatus() = probeStatus()
        override fun nfcProvisionStatus() = Bundle(provision)
        override fun prepareNfcProvision(token: String, nfcid2: String): Bundle {
            prepares++
            provision = Bundle().apply {
                putString("state", if (prepares == 1) "NEEDS_BASELINE" else "ALREADY_PRESENT")
                putString("token", token); putString("nfcid2", nfcid2)
                putBoolean("canActivate", prepares > 1)
            }
            return Bundle(provision)
        }
        override fun startRestoreProbe(token: String): Bundle = error("Unexpected restore probe")
        override fun verifyPreviousProbe(token: String): Bundle = error("Unexpected probe verification")
        override fun startNfcSnapshot(token: String): Bundle = error("Unexpected export")
        override fun readNfcSnapshot(token: String): Bundle = error("Unexpected snapshot read")
        override fun readNfcProvisionPlan(token: String): Bundle = error("Unexpected plan read")
        override fun applyNfcProvision(token: String): Bundle { restores++; return Bundle() }
        override fun reloadNfcProvision(token: String): Bundle = error("Unexpected NFC reload")
        override fun rollbackNfcProvision(token: String, checkpoint: INfcRollbackCheckpoint): Bundle = error("Unexpected rollback")
        override fun resumeNfcProvision(savedPlan: Bundle): Bundle = error("Unexpected resume")
        override fun inspectNfcRuntime(): Bundle = error("Unexpected runtime export")
        override fun destroy() = Unit
    }

    @Implements(NfcFCardEmulation::class)
    class BaselineNfcShadow {
        @Implementation fun setNfcid2ForService(component: ComponentName, idm: String): Boolean { writes.add(idm); return true }
        @Implementation fun registerSystemCodeForService(component: ComponentName, code: String): Boolean { writes.add(code); return true }
        companion object {
            val writes = mutableListOf<String>()
            @JvmStatic @Implementation fun getInstance(adapter: NfcAdapter): NfcFCardEmulation =
                Shadow.newInstanceOf(NfcFCardEmulation::class.java)
        }
    }

    @Implements(Shizuku::class)
    class ShizukuShadow {
        companion object {
            var running = true
            var granted = true
            var serverUid = 2000
            var binds = 0
            var unbinds = 0
            var permissionRequests = 0
            var remote: IHceShellService? = null
            var received: Shizuku.OnBinderReceivedListener? = null
            var permission: Shizuku.OnRequestPermissionResultListener? = null
            fun reset() {
                running = true; granted = true; serverUid = 2000
                binds = 0; unbinds = 0; permissionRequests = 0
                received = null; permission = null
                remote = null
            }
            @JvmStatic @Implementation fun pingBinder() = running
            @JvmStatic @Implementation fun isPreV11() = false
            @JvmStatic @Implementation fun getVersion() = 13
            @JvmStatic @Implementation fun getUid() = serverUid
            @JvmStatic @Implementation fun checkSelfPermission() =
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            @JvmStatic @Implementation fun shouldShowRequestPermissionRationale() = false
            @JvmStatic @Implementation fun requestPermission(request: Int) { permissionRequests++ }
            @JvmStatic @Implementation fun bindUserService(args: Shizuku.UserServiceArgs, connection: ServiceConnection) {
                binds++
                remote?.let { connection.onServiceConnected(ComponentName(RuntimeEnvironment.getApplication(), HceShellService::class.java), it.asBinder()) }
            }
            @JvmStatic @Implementation fun unbindUserService(args: Shizuku.UserServiceArgs, connection: ServiceConnection, remove: Boolean) { unbinds++ }
            @JvmStatic @Implementation fun addBinderReceivedListenerSticky(listener: Shizuku.OnBinderReceivedListener) { received = listener }
            @JvmStatic @Implementation fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) { received = null }
            @JvmStatic @Implementation fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) = Unit
            @JvmStatic @Implementation fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) = Unit
            @JvmStatic @Implementation fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) { permission = listener }
            @JvmStatic @Implementation fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) { permission = null }
        }
    }
}
