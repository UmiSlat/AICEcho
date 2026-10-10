package io.github.umislat.aimesimulator.provisioning

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import io.github.umislat.aimesimulator.BuildConfig
import java.io.File
import java.util.concurrent.Executors

/** Shizuku UserService, deliberately not an exported Android Service. */
class HceShellService(context: Context) : IHceShellService.Stub() {
    private val ownerUid = context.applicationInfo.uid.also {
        require(context.packageName == BuildConfig.APPLICATION_ID && it in 10000..99999)
    }
    private val packages = context.packageManager
    private val worker = Executors.newSingleThreadExecutor()
    private val exporter = AospNfcExporter(context)
    private val appContext = context
    private val provisioning = NfcProvisionWorkflow(NfcProvisionBackend(context) {
        snapshot.state == NfcSnapshotWorkflow.State.CAPTURED
    }, ownerUid)
    @Volatile private var provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.IDLE)
    @Volatile private var result = RestoreProbeWorkflow.Result(RestoreProbeWorkflow.State.IDLE)
    @Volatile private var snapshot = NfcSnapshotWorkflow.Result(NfcSnapshotWorkflow.State.IDLE)
    private val backend = object : RestoreProbeWorkflow.Backend {
        override fun certificate(): ByteArray = markerCertificate()
        override fun restore(archive: ByteArray): RestoreProbeWorkflow.Completion {
            val execution = ProbeProcess.run(listOf("/system/bin/bu", "restore", "-user", "0"),
                archive, 90)
            return RestoreProbeWorkflow.Completion(execution.exitCode, execution.uncertain)
        }
        override fun readMarker(deviceProtected: Boolean): ByteArray? {
            val root = if (deviceProtected) "/data/user_de/0" else "/data/user/0"
            val read = ProbeProcess.run(listOf("/system/bin/run-as", RestoreProbeArchive.PACKAGE,
                "/system/bin/cat", "$root/${RestoreProbeArchive.PACKAGE}/files/restore_probe.txt"))
            return read.output.takeIf { !read.uncertain && read.exitCode == 0 && it.size <= 256 }
        }
    }

    override fun inspect(): Bundle {
        enforceOwner()
        val identity = Binder.clearCallingIdentity()
        return try {
            val capabilities = AospBackupCapabilities.inspect(appContext)
            Bundle().apply {
                putInt("uid", Process.myUid())
                putBoolean("bu", File("/system/bin/bu").canExecute())
                putBoolean("markerApp", Process.myUid() == 2000 && runCatching {
                    RestoreProbeArchive.sha256(markerCertificate()) == RestoreProbeArchive.SIGNER_SHA256
                }.getOrDefault(false))
                // Readability is diagnostic only, never a claim of a complete fresh snapshot.
                putBoolean("identityReadable", Process.myUid() == 2000 && runCatching {
                    File(appContext.createPackageContext(NfcSnapshotArchive.PACKAGE, 0).dataDir,
                        "files/dynamic_systemcode_nfcid2.xml").canRead()
                }.getOrDefault(false))
                putBundle("aospCapabilities", capabilities.bundle())
                // Baseline preparation tries a scoped runtime read first; no export receipt is required.
                putBoolean("nfcProvisioning", capabilities.canExport)
                putBoolean("nfcSnapshotCandidate", capabilities.canExport)
            }
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    @Synchronized override fun startRestoreProbe(token: String): Bundle {
        enforceOwner()
        check(Process.myUid() == 2000) { "This experiment requires ADB-started Shizuku" }
        require(RestoreProbeArchive.validToken(token))
        if (token == result.token) return bundle(result)
        check(!probeUnresolved() && !snapshotUnresolved() && !provisionUnresolved()) { "Experiment unresolved" }
        result = RestoreProbeWorkflow.Result(RestoreProbeWorkflow.State.PREPARING, token)
        worker.execute {
            result = RestoreProbeWorkflow(backend).run(token) { result = it }
        }
        return bundle(result)
    }

    override fun probeStatus(): Bundle { enforceOwner(); return bundle(result) }

    @Synchronized override fun verifyPreviousProbe(token: String): Bundle {
        enforceOwner()
        check(Process.myUid() == 2000 && !result.busy && !snapshotUnresolved() && !provisionUnresolved())
        require(RestoreProbeArchive.validToken(token))
        val identity = Binder.clearCallingIdentity()
        return try {
            val matches = runCatching {
                RestoreProbeArchive.sha256(markerCertificate()) == RestoreProbeArchive.SIGNER_SHA256 &&
                    RestoreProbeWorkflow(backend).matches(token)
            }.getOrDefault(false)
            result = RestoreProbeWorkflow.Result(if (matches) RestoreProbeWorkflow.State.MARKERS_MATCH
                else RestoreProbeWorkflow.State.UNKNOWN, token)
            bundle(result)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    @Synchronized override fun startNfcSnapshot(token: String): Bundle {
        enforceOwner()
        check(Process.myUid() == 2000)
        require(RestoreProbeArchive.validToken(token))
        if (token == snapshot.token) return snapshotBundle(snapshot)
        check(!probeUnresolved() && !snapshotUnresolved() && !provisionUnresolved()) { "Experiment unresolved" }
        snapshot = NfcSnapshotWorkflow.Result(NfcSnapshotWorkflow.State.PREPARING, token)
        worker.execute { snapshot = NfcSnapshotWorkflow(exporter).run(token) { snapshot = it } }
        return snapshotBundle(snapshot)
    }

    override fun snapshotStatus(): Bundle { enforceOwner(); return snapshotBundle(snapshot) }

    @Synchronized override fun inspectNfcRuntime(): Bundle {
        enforceOwner()
        check(Process.myUid() == 2000 && !probeUnresolved() && !snapshotUnresolved() && !provisionUnresolved())
        val identity = Binder.clearCallingIdentity()
        return try { NfcRuntimeInventory.inspect(appContext).bundle() }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun readNfcSnapshot(token: String): Bundle {
        enforceOwner()
        check(Process.myUid() == 2000)
        require(RestoreProbeArchive.validToken(token))
        val value = snapshot
        check(value.token == token && value.state == NfcSnapshotWorkflow.State.CAPTURED)
        val capture = checkNotNull(value.capture)
        return snapshotBundle(value).apply {
            putByteArray("xml", capture.parsed.xml.copyOf())
            putString("identityHash", capture.parsed.identityHash)
            putString("archiveHash", capture.parsed.archiveHash)
            putString("domain", capture.parsed.domain.name)
            putInt("records", capture.parsed.records)
            putInt("boot", capture.boot)
            putLong("elapsed", capture.elapsed)
            putLong("wall", capture.wall)
            putString("fingerprint", capture.fingerprint)
        }
    }

    private fun probeUnresolved() = result.busy || result.state in setOf(RestoreProbeWorkflow.State.UNKNOWN,
        RestoreProbeWorkflow.State.NOT_VERIFIED, RestoreProbeWorkflow.State.MARKERS_MATCH)
    private fun snapshotUnresolved() = snapshot.busy || snapshot.state == NfcSnapshotWorkflow.State.UNKNOWN
    private fun provisionUnresolved() = provision.busy || provision.state in setOf(
        NfcProvisionWorkflow.State.UNKNOWN, NfcProvisionWorkflow.State.AWAITING_RELOAD)

    @Synchronized override fun prepareNfcProvision(token: String, nfcid2: String): Bundle {
        enforceOwner()
        check(Process.myUid() == 2000)
        require(RestoreProbeArchive.validToken(token))
        require(nfcid2.matches(Regex("[0-9A-F]{16}")) && nfcid2 != "0".repeat(16) && nfcid2 != "F".repeat(16))
        if (token == provision.token) return provisionBundle()
        check(!probeUnresolved() && !snapshotUnresolved() && !provisionUnresolved())
        provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.PREPARING, token)
        worker.execute { provision = provisioning.prepare(token, nfcid2) { provision = it } }
        return provisionBundle()
    }

    @Synchronized override fun resumeNfcProvision(savedPlan: Bundle): Bundle {
        enforceOwner()
        check(Process.myUid() == 2000)
        val saved = NfcProvisionStore.validate(savedPlan, ownerUid)
        val checkpoint = NfcProvisionStore.checkpoint(savedPlan, saved)
        val token = savedPlan.getString("token").orEmpty()
        if (token == provision.token) return provisionBundle()
        check(!probeUnresolved() && !snapshotUnresolved() && !provisionUnresolved())
        val live = provisioning.plan
        check(live == null || (live.originalHash == saved.originalHash && live.proposedHash == saved.proposedHash &&
            live.source == saved.source && live.domain == saved.domain)) {
            "A different live plan already exists"
        }
        provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.CHECKING, token)
        worker.execute {
            val previousBoot = savedPlan.getInt("boot", -1)
            val currentBoot = runCatching {
                Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
            }.getOrDefault(-1)
            provision = provisioning.resume(token, saved, checkpoint,
                previousBoot >= 0 && currentBoot >= 0 && previousBoot != currentBoot) { provision = it }
        }
        return provisionBundle()
    }

    override fun nfcProvisionStatus(): Bundle { enforceOwner(); return provisionBundle() }

    override fun readNfcProvisionPlan(token: String): Bundle {
        enforceOwner()
        check(Process.myUid() == 2000 && provision.token == token && !provision.busy)
        val plan = checkNotNull(provisioning.plan)
        return provisionBundle().apply {
            putByteArray("original", plan.originalXml())
            putByteArray("proposed", plan.proposedXml())
            putString("originalHash", plan.originalHash)
            putString("proposedHash", plan.proposedHash)
            putInt("uid", plan.uid)
            putString("oldSystemCode", plan.before.systemCode)
            putString("oldNfcid2", plan.before.nfcid2)
            putString("source", plan.source.name)
            putString("domain", plan.domain.name)
            val checkpoint = provisioning.checkpoint()
            putString("direction", checkpoint.direction.name)
            checkpoint.rollback?.let {
                putByteArray("rollbackObservation", it.observationXml())
                putByteArray("rollbackXml", it.xml())
            }
        }
    }

    @Synchronized override fun applyNfcProvision(token: String): Bundle = provisionAction(token,
        setOf(NfcProvisionWorkflow.State.PREPARED, NfcProvisionWorkflow.State.UNCHANGED),
        NfcProvisionWorkflow.State.CHECKING) { provisioning.apply(token) { provision = it } }

    @Synchronized override fun reloadNfcProvision(token: String): Bundle = provisionAction(token,
        setOf(NfcProvisionWorkflow.State.VERIFIED, NfcProvisionWorkflow.State.ALREADY_PRESENT,
            NfcProvisionWorkflow.State.ROLLED_BACK, NfcProvisionWorkflow.State.RELOADED,
            NfcProvisionWorkflow.State.AWAITING_RELOAD),
        NfcProvisionWorkflow.State.RELOADING) { provisioning.reload(token) { provision = it } }

    @Synchronized override fun rollbackNfcProvision(token: String, checkpoint: INfcRollbackCheckpoint): Bundle = provisionAction(token,
        setOf(NfcProvisionWorkflow.State.VERIFIED, NfcProvisionWorkflow.State.RELOADED,
            NfcProvisionWorkflow.State.CONFLICT, NfcProvisionWorkflow.State.UNCHANGED),
        NfcProvisionWorkflow.State.ROLLING_BACK) {
        provisioning.rollback(token, { candidate ->
            checkpoint.retain(token, candidate.observationXml(), candidate.xml())
        }) { provision = it }
    }

    private fun provisionAction(token: String, allowed: Set<NfcProvisionWorkflow.State>,
        next: NfcProvisionWorkflow.State, action: () -> NfcProvisionWorkflow.Result): Bundle {
        enforceOwner()
        check(Process.myUid() == 2000 && !probeUnresolved() && !snapshotUnresolved())
        require(RestoreProbeArchive.validToken(token) && token == provision.token)
        if (provision.busy) return provisionBundle()
        check(provision.state in allowed)
        provision = NfcProvisionWorkflow.Result(next, token)
        worker.execute { provision = action() }
        return provisionBundle()
    }

    private fun provisionBundle() = Bundle().apply {
        putString("state", provision.state.name)
        putString("token", provision.token)
        putString("detail", provision.detail)
        putString("nfcid2", provisioning.requestedIdm)
        putString("source", provisioning.source.name)
        putString("domain", provisioning.domain?.name)
        putBoolean("hasPlan", provisioning.plan != null)
        putBoolean("canActivate", !provision.busy && provisioning.canActivate())
    }
    private fun snapshotBundle(value: NfcSnapshotWorkflow.Result) = Bundle().apply {
        putString("state", value.state.name)
        putString("token", value.token)
        putString("detail", value.detail)
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        check(caller == ownerUid || caller == 0 || caller == 2000)
        worker.shutdownNow()
        // Termination is not evidence that an already accepted restore was cancelled.
        kotlin.system.exitProcess(0)
    }

    private fun enforceOwner() { check(Binder.getCallingUid() == ownerUid) { "Unexpected caller" } }

    @Suppress("DEPRECATION")
    private fun markerCertificate(): ByteArray {
        val info = packages.getPackageInfo(RestoreProbeArchive.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val application = checkNotNull(info.applicationInfo)
        check(application.uid in 10000..99999 && info.longVersionCode == 1L)
        check(application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(application.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        val signers = checkNotNull(info.signingInfo).apkContentsSigners
        check(signers.size == 1)
        return signers.single().toByteArray().also {
            check(RestoreProbeArchive.sha256(it) == RestoreProbeArchive.SIGNER_SHA256)
        }
    }

    private fun bundle(value: RestoreProbeWorkflow.Result) = Bundle().apply {
        putString("state", value.state.name)
        putString("token", value.token)
        putString("detail", value.detail)
    }
}
