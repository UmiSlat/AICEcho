package io.github.umislat.aimesimulator.provisioning

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.NfcFCardEmulation
import android.os.Bundle
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import rikka.shizuku.Shizuku
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.nfc.AimeHostService
import java.security.SecureRandom
import java.util.concurrent.Executors

internal class ShizukuProbeModel(application: Application) : AndroidViewModel(application) {
    enum class Connection { MISSING, AVAILABLE, PERMISSION, DENIED, OLD_VERSION, ROOT, CONNECTING, READY, ERROR }
    data class Screen(
        val connection: Connection = Connection.MISSING,
        val uid: Int = -1,
        val bu: Boolean = false,
        val aosp: AospBackupCapabilities? = null,
        val runtime: NfcRuntimeInventory.Summary = NfcRuntimeInventory.Summary(),
        val markerApp: Boolean = false,
        val provisionCandidate: Boolean = false,
        val provision: NfcProvisionWorkflow.Result = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.IDLE),
        val provisionPending: Boolean = false,
        val provisionIdm: String = "",
        val provisionSource: NfcProvisionPlan.BaselineSource = NfcProvisionPlan.BaselineSource.FILE,
        val provisionDomain: NfcStorageDomain? = null,
        val planSaved: Boolean = false,
        val savedPlanAvailable: Boolean = false,
        val savedPlanRequiresResume: Boolean = false,
        val canActivate: Boolean = false,
        val identityReadable: Boolean = false,
        val snapshotCandidate: Boolean = false,
        val snapshot: NfcSnapshotWorkflow.Result = NfcSnapshotWorkflow.Result(NfcSnapshotWorkflow.State.IDLE),
        val snapshotPending: Boolean = false,
        val savedSnapshot: NfcSnapshotStore.Summary? = null,
        val probe: RestoreProbeWorkflow.Result = RestoreProbeWorkflow.Result(RestoreProbeWorkflow.State.IDLE),
        val pending: Boolean = false,
        val working: Boolean = false
    ) {
        private val probeUnresolved get() = probe.busy || probe.state in setOf(RestoreProbeWorkflow.State.UNKNOWN,
            RestoreProbeWorkflow.State.NOT_VERIFIED, RestoreProbeWorkflow.State.MARKERS_MATCH)
        private val snapshotUnresolved get() = snapshot.busy || snapshot.state == NfcSnapshotWorkflow.State.UNKNOWN
        private val provisionUnresolved get() = provision.busy || provisionPending || provision.state in setOf(
            NfcProvisionWorkflow.State.UNKNOWN, NfcProvisionWorkflow.State.AWAITING_RELOAD) ||
            (savedPlanRequiresResume && !planSaved)
        private val provisionReady get() = connection == Connection.READY && uid == 2000 && bu && provisionCandidate &&
            !pending && !snapshotPending && !working && !probeUnresolved && !snapshotUnresolved &&
            !provision.busy && !provisionPending && provision.state != NfcProvisionWorkflow.State.UNKNOWN
        val canStart: Boolean get() = connection == Connection.READY && uid == 2000 && bu && markerApp &&
            !pending && !snapshotPending && !working && !probeUnresolved && !snapshotUnresolved && !provisionUnresolved
        val canVerify: Boolean get() = connection == Connection.READY && uid == 2000 && pending &&
            !working && !probe.busy && !snapshotPending && !snapshotUnresolved && !provisionUnresolved && RestoreProbeArchive.validToken(probe.token)
        val canCapture: Boolean get() = connection == Connection.READY && uid == 2000 && snapshotCandidate &&
            !pending && !snapshotPending && !working && !probeUnresolved && !snapshotUnresolved && !provisionUnresolved
        val canPrepare: Boolean get() = provisionReady && provision.state != NfcProvisionWorkflow.State.AWAITING_RELOAD &&
            !(savedPlanRequiresResume && !planSaved)
        val canInspectRuntime: Boolean get() = connection == Connection.READY && uid == 2000 &&
            !pending && !snapshotPending && !working && !probeUnresolved && !snapshotUnresolved && !provisionUnresolved
        val canResumePlan: Boolean get() = provisionReady && savedPlanAvailable && !planSaved
        val canApply: Boolean get() = canPrepare && planSaved && provision.state in setOf(
            NfcProvisionWorkflow.State.PREPARED, NfcProvisionWorkflow.State.UNCHANGED)
        val canReload: Boolean get() = provisionReady && provision.state in setOf(NfcProvisionWorkflow.State.VERIFIED,
            NfcProvisionWorkflow.State.ALREADY_PRESENT, NfcProvisionWorkflow.State.ROLLED_BACK, NfcProvisionWorkflow.State.RELOADED,
            NfcProvisionWorkflow.State.AWAITING_RELOAD) &&
            (provision.state != NfcProvisionWorkflow.State.AWAITING_RELOAD || planSaved)
        val canRollback: Boolean get() = canPrepare && planSaved && provision.state in setOf(NfcProvisionWorkflow.State.VERIFIED,
            NfcProvisionWorkflow.State.RELOADED, NfcProvisionWorkflow.State.CONFLICT, NfcProvisionWorkflow.State.UNCHANGED)
        val busy: Boolean get() = working || probe.busy || snapshot.busy || provision.busy
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val boot = runCatching {
        Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
    private val journal = ProbeJournal(application.getSharedPreferences("shizuku_restore_probe", Context.MODE_PRIVATE),
        boot)
    private val snapshotJournal = ProbeJournal(application.getSharedPreferences("shizuku_nfc_snapshot", Context.MODE_PRIVATE), boot)
    private val snapshotStore = NfcSnapshotStore(application.noBackupFilesDir)
    private val provisionStore = NfcProvisionStore(application.noBackupFilesDir)
    private val provisionJournal = ProbeJournal(application.getSharedPreferences("shizuku_nfc_provision", Context.MODE_PRIVATE), boot)
    private val mutable = MutableLiveData(Screen(savedSnapshot = snapshotStore.read(),
        savedPlanAvailable = provisionStore.read(application.applicationInfo.uid) != null,
        savedPlanRequiresResume = NfcProvisionStore.requiresResume(provisionStore.read(application.applicationInfo.uid))).let { initial ->
        val capture = snapshotJournal.read()
        val screen = if (capture.pending) initial.copy(snapshotPending = true,
            snapshot = NfcSnapshotWorkflow.Result(NfcSnapshotWorkflow.State.UNKNOWN, capture.token)) else initial
        val entry = journal.read()
        val provision = provisionJournal.read()
        val previous = if (entry.pending) screen.copy(pending = true,
            probe = RestoreProbeWorkflow.Result(RestoreProbeWorkflow.State.UNKNOWN, entry.token)) else screen
        if (provision.pending) previous.copy(provisionPending = true,
            provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.UNKNOWN, provision.token)) else previous
    })
    val screen: LiveData<Screen> = mutable
    private var service: IHceShellService? = null
    private var closed = false
    private var binding = false
    private var generation = 0
    private var wantsPermission = false
    private var foreground = false
    private var guidedSetup = false
    private val args = Shizuku.UserServiceArgs(ComponentName(application, HceShellService::class.java))
        .daemon(false).tag("hcef-restore-probe").processNameSuffix("hcef_restore_probe").version(9)
    private val poll = Runnable { refresh() }
    private val ageTick = object : Runnable {
        override fun run() { if (!closed) { publish(current()); main.postDelayed(this, 30_000) } }
    }
    private val bindTimeout = Runnable { if (binding && service == null) disconnected() }
    private val rpcTimeout = Runnable { if (current().working) disconnected() }
    private val death = IBinder.DeathRecipient { main.post { disconnected() } }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            main.post {
                if (closed || !binding) return@post
                main.removeCallbacks(bindTimeout)
                service = IHceShellService.Stub.asInterface(binder)
                runCatching { binder.linkToDeath(death, 0) }.onFailure { disconnected(); return@post }
                publish(current().copy(connection = Connection.READY, working = false))
                refresh()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { main.post { disconnected() } }
    }
    private val received = Shizuku.OnBinderReceivedListener {
        main.post {
            if (!closed && service == null && !binding) {
                if (foreground) connect(askPermission = false)
                else publish(current().copy(connection = availability()))
            }
        }
    }
    private val dead = Shizuku.OnBinderDeadListener { disconnected() }
    private val permission = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (!closed && request == PERMISSION_REQUEST && wantsPermission) {
            wantsPermission = false
            if (result == PackageManager.PERMISSION_GRANTED) connect()
            else publish(current().copy(connection = Connection.DENIED))
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        main.postDelayed(ageTick, 30_000)
    }

    fun onForeground() {
        foreground = true
        if (service == null) connect(askPermission = false) else refresh()
    }

    fun onBackground() { foreground = false }

    fun connect() = connect(askPermission = true)

    private fun connect(askPermission: Boolean) {
        if (closed || binding || wantsPermission) return
        val available = availability()
        if (available != Connection.AVAILABLE) {
            publish(current().copy(connection = available))
            return
        }
        runCatching {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (!askPermission) {
                    publish(current().copy(connection = if (Shizuku.shouldShowRequestPermissionRationale())
                        Connection.DENIED else Connection.AVAILABLE))
                    return
                }
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    publish(current().copy(connection = Connection.DENIED))
                } else {
                    wantsPermission = true
                    publish(current().copy(connection = Connection.PERMISSION))
                    Shizuku.requestPermission(PERMISSION_REQUEST)
                }
                return
            }
            binding = true
            publish(current().copy(connection = Connection.CONNECTING))
            Shizuku.bindUserService(args, connection)
            main.postDelayed(bindTimeout, 15_000)
        }.onFailure { disconnected() }
    }

    fun refresh() {
        val remote = service ?: return
        if (current().working || closed) return
        val captureEntry = snapshotJournal.read()
        val provisionEntry = provisionJournal.read()
        val alreadySaved = current().planSaved
        rpc({
            val capabilities = remote.inspect()
            val status = remote.probeStatus()
            val captureStatus = remote.snapshotStatus()
            val provisionStatus = remote.nfcProvisionStatus()
            val provision = decodeProvision(provisionStatus)
            val hasPlan = provision.token == provisionEntry.token && provisionStatus.getBoolean("hasPlan")
            val saveCheckpoint = hasPlan && !provision.busy && provision.state != NfcProvisionWorkflow.State.IDLE
            val planSaved = hasPlan && (alreadySaved || saveCheckpoint)
            if (saveCheckpoint) {
                provisionStore.save(remote.readNfcProvisionPlan(provision.token), getApplication<Application>().applicationInfo.uid, boot)
            }
            val capture = decodeSnapshot(captureStatus)
            val saved = if (captureEntry.pending && capture.token == captureEntry.token &&
                capture.state == NfcSnapshotWorkflow.State.CAPTURED) {
                snapshotStore.save(remote.readNfcSnapshot(capture.token), capture.token, boot,
                    SystemClock.elapsedRealtime(), Build.FINGERPRINT)
            } else null
            val retainedPlan = provisionStore.read(getApplication<Application>().applicationInfo.uid)
            Refresh(capabilities, status, capture, saved, provisionStatus, planSaved,
                retainedPlan != null, NfcProvisionStore.requiresResume(retainedPlan))
        }) { response ->
            val capabilities = response.capabilities
            val previous = current()
            val value = decode(response.probe)
            val entry = journal.read()
            val relevant = value.token == entry.token && value.state != RestoreProbeWorkflow.State.IDLE
            if (relevant) journal.complete(value)
            val captureRelevant = response.snapshot.token == captureEntry.token &&
                response.snapshot.state != NfcSnapshotWorkflow.State.IDLE
            if (captureRelevant && (response.snapshot.state != NfcSnapshotWorkflow.State.CAPTURED || response.saved != null)) {
                snapshotJournal.completeSnapshot(response.snapshot)
            }
            val provision = decodeProvision(response.provision)
            val provisionRelevant = provision.token == provisionEntry.token && provision.state != NfcProvisionWorkflow.State.IDLE
            if (provisionRelevant) provisionJournal.completeProvision(provision)
            val lostSession = provision.state == NfcProvisionWorkflow.State.IDLE && !provisionJournal.read().pending
            val next = previous.copy(connection = Connection.READY, uid = capabilities.getInt("uid", -1),
                bu = capabilities.getBoolean("bu"), markerApp = capabilities.getBoolean("markerApp"),
                aosp = capabilities.getBundle("aospCapabilities")?.let(AospBackupCapabilities::decode),
                identityReadable = capabilities.getBoolean("identityReadable"),
                snapshotCandidate = capabilities.getBoolean("nfcSnapshotCandidate"),
                provisionCandidate = capabilities.getBoolean("nfcProvisioning"),
                provision = if (provisionRelevant || lostSession) provision else previous.provision,
                provisionPending = provisionJournal.read().pending,
                provisionIdm = if (provisionRelevant) response.provision.getString("nfcid2").orEmpty()
                    else if (lostSession) "" else previous.provisionIdm,
                provisionSource = if (provisionRelevant) NfcProvisionPlan.BaselineSource.valueOf(
                    response.provision.getString("source") ?: "FILE") else previous.provisionSource,
                provisionDomain = if (provisionRelevant) response.provision.getString("domain")?.let(NfcStorageDomain::valueOf)
                    else if (lostSession) null else previous.provisionDomain,
                planSaved = response.planSaved,
                savedPlanAvailable = response.savedPlanAvailable,
                savedPlanRequiresResume = response.savedPlanRequiresResume,
                canActivate = provisionRelevant && response.provision.getBoolean("canActivate"),
                snapshot = if (captureRelevant) response.snapshot else previous.snapshot,
                snapshotPending = snapshotJournal.read().pending,
                savedSnapshot = response.saved ?: previous.savedSnapshot,
                probe = if (relevant) value else previous.probe, pending = journal.read().pending, working = false)
            val continueGuidedSetup = guidedSetup && !next.busy
            publish(next)
            if ((next.probe.busy && relevant) || (next.snapshot.busy && captureRelevant) || (next.provision.busy && provisionRelevant)) main.postDelayed(poll, 1000)
            if ((next.probe.busy && !relevant) || (next.snapshot.busy && !captureRelevant)) {
                publish(next.copy(probe = if (next.probe.busy) next.probe.copy(state = RestoreProbeWorkflow.State.UNKNOWN) else next.probe,
                    snapshot = if (next.snapshot.busy) next.snapshot.copy(state = NfcSnapshotWorkflow.State.UNKNOWN) else next.snapshot))
            }
            if (continueGuidedSetup) {
                guidedSetup = false
                if (next.provision.state == NfcProvisionWorkflow.State.NEEDS_BASELINE &&
                    foreground && next.canPrepare && CardStore(getApplication()).selectedProfile()?.idm == next.provisionIdm) {
                    initializeBaseline()
                }
            }
        }
    }

    fun captureSnapshot() {
        if (!current().canCapture) return
        val remote = service ?: return
        val token = RestoreProbeArchive.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        if (!snapshotJournal.begin(token)) {
            publish(current().copy(snapshotPending = true,
                snapshot = NfcSnapshotWorkflow.Result(NfcSnapshotWorkflow.State.UNKNOWN, token)))
            return
        }
        publish(current().copy(snapshotPending = true,
            snapshot = NfcSnapshotWorkflow.Result(NfcSnapshotWorkflow.State.PREPARING, token)))
        rpc({ remote.startNfcSnapshot(token) }) { status ->
            val value = decodeSnapshot(status)
            check(value.token == token)
            if (value.state != NfcSnapshotWorkflow.State.CAPTURED) snapshotJournal.completeSnapshot(value)
            publish(current().copy(snapshot = value, snapshotPending = snapshotJournal.read().pending, working = false))
            if (value.busy || value.state == NfcSnapshotWorkflow.State.CAPTURED) main.postDelayed(poll, 1000)
        }
    }

    fun inspectRuntime() {
        if (!current().canInspectRuntime) return
        val remote = service ?: return
        rpc({ remote.inspectNfcRuntime() }) { result ->
            publish(current().copy(runtime = NfcRuntimeInventory.Summary.decode(result), working = false))
        }
    }

    fun deleteSnapshot() {
        if (current().busy || current().pending || current().snapshotPending || current().provisionPending) return
        snapshotStore.delete()
        publish(current().copy(savedSnapshot = null))
    }

    fun prepareCurrentCard() {
        if (!current().canPrepare) return
        val store = CardStore(getApplication())
        val profile = store.selectedProfile() ?: return
        val idm = profile.idm
        val token = RestoreProbeArchive.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        publish(current().copy(planSaved = false, canActivate = false, provisionIdm = idm))
        provisionRequest(token, NfcProvisionWorkflow.State.PREPARING) { it.prepareNfcProvision(token, idm) }
    }

    fun beginSetup() {
        if (!current().canPrepare || CardStore(getApplication()).selectedProfile() == null) return
        guidedSetup = true
        prepareCurrentCard()
    }

    fun resumePreviousPlan() {
        if (!current().canResumePlan || service == null) return
        val saved = provisionStore.read(getApplication<Application>().applicationInfo.uid)
        if (saved == null) {
            publish(current().copy(savedPlanAvailable = false))
            return
        }
        val token = RestoreProbeArchive.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        saved.putString("token", token)
        publish(current().copy(planSaved = false, canActivate = false,
            provisionIdm = saved.getString("nfcid2").orEmpty()))
        provisionRequest(token, NfcProvisionWorkflow.State.CHECKING) { it.resumeNfcProvision(saved) }
    }

    fun initializeBaseline() {
        if (!current().canPrepare || current().provision.state != NfcProvisionWorkflow.State.NEEDS_BASELINE) return
        runCatching {
            val app = getApplication<Application>()
            val adapter = checkNotNull(NfcAdapter.getDefaultAdapter(app))
            check(adapter.isEnabled)
            val manager = NfcFCardEmulation.getInstance(adapter)
            val component = ComponentName(app, AimeHostService::class.java)
            check(manager.setNfcid2ForService(component, "02FE000000000000"))
            check(manager.registerSystemCodeForService(component, "4000"))
        }.onSuccess { prepareCurrentCard() }.onFailure {
            publish(current().copy(provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.FAILED)))
        }
    }

    fun applyCurrentPlan() {
        if (!current().canApply) return
        val store = CardStore(getApplication())
        val selected = store.selectedProfile() ?: return
        if (selected.idm != current().provisionIdm) return
        store.setPersistedRouteOnlyMode(true)
        val token = current().provision.token
        provisionRequest(token, NfcProvisionWorkflow.State.CHECKING, NfcProvisionWorkflow.State.CONFIRMING,
            NfcProvisionWorkflow.Direction.FORWARD) { it.applyNfcProvision(token) }
    }

    fun reloadNfc() {
        if (!current().canReload) return
        val token = current().provision.token
        provisionRequest(token, NfcProvisionWorkflow.State.RELOADING,
            NfcProvisionWorkflow.State.RELOADING.takeIf { current().planSaved }) { it.reloadNfcProvision(token) }
    }

    fun rollbackNfc() {
        if (!current().canRollback) return
        val token = current().provision.token
        val checkpoint = object : INfcRollbackCheckpoint.Stub() {
            override fun retain(receivedToken: String, observation: ByteArray, replacement: ByteArray) {
                check(Binder.getCallingUid() == 2000 && receivedToken == token)
                provisionStore.retainRollback(token, getApplication<Application>().applicationInfo.uid, boot, observation, replacement)
            }
        }
        provisionRequest(token, NfcProvisionWorkflow.State.ROLLING_BACK, NfcProvisionWorkflow.State.ROLLING_BACK,
            NfcProvisionWorkflow.Direction.ROLLBACK) { it.rollbackNfcProvision(token, checkpoint) }
    }

    private fun provisionRequest(token: String, phase: NfcProvisionWorkflow.State,
        checkpointState: NfcProvisionWorkflow.State? = null, direction: NfcProvisionWorkflow.Direction? = null,
        action: (IHceShellService) -> Bundle) {
        val remote = service ?: return
        if (!provisionJournal.begin(token)) {
            publish(current().copy(provisionPending = true, provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.UNKNOWN, token)))
            return
        }
        publish(current().copy(provisionPending = true, canActivate = false, provision = NfcProvisionWorkflow.Result(phase, token)))
        rpc({
            if (checkpointState != null) provisionStore.mark(token, getApplication<Application>().applicationInfo.uid,
                boot, checkpointState, direction)
            action(remote).also { status ->
                val value = decodeProvision(status)
                if (value.token == token && !value.busy && status.getBoolean("hasPlan")) {
                    provisionStore.save(remote.readNfcProvisionPlan(token), getApplication<Application>().applicationInfo.uid, boot)
                }
            }
        }) { status ->
            val result = decodeProvision(status)
            check(result.token == token)
            provisionJournal.completeProvision(result)
            publish(current().copy(working = false, provision = result, provisionPending = provisionJournal.read().pending))
            main.postDelayed(poll, 300)
        }
    }

    /** Called only by the explicit confirmation dialog, never permission/connection callbacks. */
    fun startProbe() {
        if (!current().canStart) return
        val remote = service ?: return
        val token = RestoreProbeArchive.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        if (!journal.begin(token)) {
            publish(current().copy(pending = true,
                probe = RestoreProbeWorkflow.Result(RestoreProbeWorkflow.State.UNKNOWN, token)))
            return
        }
        publish(current().copy(pending = true, probe = RestoreProbeWorkflow.Result(RestoreProbeWorkflow.State.PREPARING, token)))
        rpc({ remote.startRestoreProbe(token) }) { status ->
            val value = decode(status)
            check(value.token == token)
            journal.complete(value)
            publish(current().copy(probe = value, pending = journal.read().pending, working = false))
            if (value.busy) main.postDelayed(poll, 1000)
        }
    }

    fun verifyPrevious() {
        if (!current().canVerify) return
        val remote = service ?: return
        val token = current().probe.token
        rpc({ remote.verifyPreviousProbe(token) }) { status ->
            val value = decode(status)
            check(value.token == token)
            // Leave the journal pending even if markers match: the original completion was lost.
            publish(current().copy(probe = value, working = false))
        }
    }

    private fun availability(): Connection = runCatching {
        when {
            !Shizuku.pingBinder() -> Connection.MISSING
            Shizuku.isPreV11() || Shizuku.getVersion() < 13 -> Connection.OLD_VERSION
            Shizuku.getUid() != 2000 -> Connection.ROOT
            else -> Connection.AVAILABLE
        }
    }.getOrDefault(Connection.ERROR)

    private fun <T> rpc(task: () -> T, success: (T) -> Unit) {
        val epoch = generation
        publish(current().copy(working = true))
        main.postDelayed(rpcTimeout, 15_000)
        worker.execute {
            val result = runCatching(task)
            main.post {
                if (closed || epoch != generation) return@post
                main.removeCallbacks(rpcTimeout)
                result.onSuccess { runCatching { success(it) }.onFailure { disconnected() } }
                    .onFailure { disconnected() }
            }
        }
    }

    private fun disconnected() {
        if (closed) return
        generation++
        wantsPermission = false
        main.removeCallbacks(poll)
        main.removeCallbacks(bindTimeout)
        main.removeCallbacks(rpcTimeout)
        releaseBinding()
        val previous = current()
        publish(previous.copy(connection = Connection.ERROR, uid = -1, bu = false, markerApp = false,
            snapshotCandidate = false, aosp = null, runtime = NfcRuntimeInventory.Summary(),
            provisionCandidate = false, canActivate = false,
            provision = if (previous.provisionPending || previous.provision.busy)
                previous.provision.copy(state = NfcProvisionWorkflow.State.UNKNOWN) else previous.provision,
            snapshot = if (previous.snapshotPending || previous.snapshot.busy)
                previous.snapshot.copy(state = NfcSnapshotWorkflow.State.UNKNOWN) else previous.snapshot,
            working = false, probe = if (previous.pending || previous.probe.busy)
                previous.probe.copy(state = RestoreProbeWorkflow.State.UNKNOWN) else previous.probe))
    }

    private fun releaseBinding() {
        service?.asBinder()?.let { runCatching { it.unlinkToDeath(death, 0) } }
        service = null
        if (binding) runCatching { Shizuku.unbindUserService(args, connection, false) }
        binding = false
    }

    private fun current(): Screen = checkNotNull(mutable.value)
    private fun publish(value: Screen) { if (!closed) mutable.value = value }
    private fun decode(bundle: Bundle) = RestoreProbeWorkflow.Result(
        RestoreProbeWorkflow.State.valueOf(bundle.getString("state").orEmpty()),
        bundle.getString("token").orEmpty())
    private fun decodeSnapshot(bundle: Bundle) = NfcSnapshotWorkflow.Result(
        NfcSnapshotWorkflow.State.valueOf(bundle.getString("state").orEmpty()), bundle.getString("token").orEmpty(),
        detail = bundle.getString("detail").orEmpty())
    private fun decodeProvision(bundle: Bundle) = NfcProvisionWorkflow.Result(
        NfcProvisionWorkflow.State.valueOf(bundle.getString("state").orEmpty()), bundle.getString("token").orEmpty(),
        bundle.getString("detail").orEmpty())
    private data class Refresh(val capabilities: Bundle, val probe: Bundle,
        val snapshot: NfcSnapshotWorkflow.Result, val saved: NfcSnapshotStore.Summary?, val provision: Bundle,
        val planSaved: Boolean, val savedPlanAvailable: Boolean, val savedPlanRequiresResume: Boolean)

    override fun onCleared() {
        closed = true
        generation++
        main.removeCallbacksAndMessages(null)
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        releaseBinding()
        worker.shutdown()
        super.onCleared()
    }

    companion object { private const val PERMISSION_REQUEST = 8804 }
}
