package io.github.umislat.aimesimulator.provisioning

/** Manual provisioning steps; no automatic restore, retry, reload or rollback. */
internal class NfcProvisionWorkflow(private val backend: Backend, private val ownerUid: Int) {
    class UncertainOperation : IllegalStateException("System operation outcome unknown")
    enum class State {
        IDLE, PREPARING, NEEDS_BASELINE, PREPARED, ALREADY_PRESENT, CHECKING, CONFIRMING,
        VERIFYING, VERIFIED, UNCHANGED, CONFLICT, RELOADING, RELOADED, ROLLING_BACK,
        ROLLED_BACK, AWAITING_RELOAD, FAILED, UNKNOWN
    }
    data class Result(val state: State, val token: String = "", val detail: String = "") {
        val busy get() = state in setOf(State.PREPARING, State.CHECKING, State.CONFIRMING,
            State.VERIFYING, State.RELOADING, State.ROLLING_BACK)
    }
    interface Backend {
        fun snapshot(): ByteArray
        fun runtimeSnapshot(): ByteArray = error("Runtime baseline unavailable")
        fun baseline() = Baseline(NfcProvisionPlan.BaselineSource.FILE, snapshot())
        fun metadata(): NfcBackupMetadata
        fun restore(archive: ByteArray): ProbeProcess.Result
        fun reload(): Boolean
    }
    class Baseline(val source: NfcProvisionPlan.BaselineSource, val xml: ByteArray)
    enum class Direction { FORWARD, ROLLBACK }
    class Checkpoint(val state: State = State.PREPARED, val direction: Direction = Direction.FORWARD,
        val rollback: NfcProvisionPlan.Replacement? = null)

    var plan: NfcProvisionPlan? = null
        private set
    var requestedIdm: String = ""
        private set
    var result = Result(State.IDLE)
        private set
    private var rollbackVerified = false
    var source = NfcProvisionPlan.BaselineSource.FILE
        private set
    private var direction = Direction.FORWARD
    private var rollbackReplacement: NfcProvisionPlan.Replacement? = null

    fun checkpoint() = Checkpoint(result.state, direction, rollbackReplacement)
    private fun snapshot() = when (source) {
        NfcProvisionPlan.BaselineSource.FILE -> backend.snapshot()
        NfcProvisionPlan.BaselineSource.RUNTIME -> backend.runtimeSnapshot()
    }

    fun prepare(token: String, idm: String, report: (Result) -> Unit): Result {
        require(RestoreProbeArchive.validToken(token))
        require(!result.busy && result.state !in setOf(State.UNKNOWN, State.AWAITING_RELOAD))
        val desired = NfcProvisionPlan.Identity("88B4", idm)
        require(desired.nfcid2 != "0".repeat(16) && desired.nfcid2 != "F".repeat(16))
        plan = null
        rollbackVerified = false
        direction = Direction.FORWARD
        rollbackReplacement = null
        requestedIdm = desired.nfcid2
        return step(State.PREPARING, token, report) {
            val baseline = backend.baseline()
            source = baseline.source
            val xml = baseline.xml
            val before = NfcProvisionPlan.currentIdentity(xml, NfcProvisionPlan.Target.AIME, ownerUid)
                ?: return@step State.NEEDS_BASELINE
            if (before.systemCode == desired.systemCode && before.nfcid2 == desired.nfcid2) {
                return@step State.ALREADY_PRESENT
            }
            plan = NfcProvisionPlan.prepare(xml, RestoreProbeArchive.sha256(xml),
                NfcProvisionPlan.Target.AIME, ownerUid, before, desired, source)
            State.PREPARED
        }
    }

    fun resume(token: String, saved: NfcProvisionPlan, checkpoint: Checkpoint = Checkpoint(),
        bootChanged: Boolean = false, report: (Result) -> Unit): Result {
        require(RestoreProbeArchive.validToken(token))
        require(!result.busy && result.state !in setOf(State.UNKNOWN, State.AWAITING_RELOAD))
        require(saved.target == NfcProvisionPlan.Target.AIME && saved.uid == ownerUid)
        plan = saved
        requestedIdm = saved.after.nfcid2
        source = saved.source
        direction = checkpoint.direction
        rollbackReplacement = checkpoint.rollback
        rollbackVerified = false
        return step(State.CHECKING, token, report) {
            // A lost completion is not cancellation, even if the old runtime cache is still visible.
            if (!bootChanged && checkpoint.state in setOf(State.CONFIRMING, State.ROLLING_BACK, State.RELOADING, State.UNKNOWN))
                return@step State.UNKNOWN
            if (source == NfcProvisionPlan.BaselineSource.RUNTIME && checkpoint.state == State.AWAITING_RELOAD)
                return@step State.AWAITING_RELOAD
            val current = snapshot()
            if (direction == Direction.ROLLBACK) {
                val matches = rollbackReplacement?.matchesReplacement(current)
                    ?: (saved.rollbackAgainst(current).check == NfcProvisionPlan.RollbackCheck.ALREADY_REVERTED)
                rollbackVerified = matches
                return@step if (matches) State.ROLLED_BACK else State.CONFLICT
            }
            when (saved.checkForward(current)) {
                NfcProvisionPlan.ForwardCheck.BASELINE_MATCH -> State.PREPARED
                NfcProvisionPlan.ForwardCheck.PROPOSED_PRESENT -> State.VERIFIED
                NfcProvisionPlan.ForwardCheck.CONFLICT -> {
                    if (saved.rollbackAgainst(current).check == NfcProvisionPlan.RollbackCheck.ALREADY_REVERTED) {
                        direction = Direction.ROLLBACK
                        rollbackVerified = true
                        State.ROLLED_BACK
                    } else State.CONFLICT
                }
            }
        }
    }

    fun apply(token: String, report: (Result) -> Unit): Result {
        require(result.token == token && result.state in setOf(State.PREPARED, State.UNCHANGED))
        val prepared = checkNotNull(plan)
        var submitted = false
        return step(State.CHECKING, token, report, { submitted }) {
            when (prepared.checkForward(snapshot())) {
                NfcProvisionPlan.ForwardCheck.CONFLICT -> return@step State.CONFLICT
                NfcProvisionPlan.ForwardCheck.PROPOSED_PRESENT -> return@step State.VERIFIED
                NfcProvisionPlan.ForwardCheck.BASELINE_MATCH -> Unit
            }
            val archive = NfcRestoreArchive.forward(prepared, backend.metadata())
            direction = Direction.FORWARD
            rollbackReplacement = null
            emit(State.CONFIRMING, token, report)
            submitted = true
            val completion = backend.restore(archive)
            if (completion.uncertain || completion.exitCode != 0) return@step State.UNKNOWN
            if (source == NfcProvisionPlan.BaselineSource.RUNTIME) return@step State.AWAITING_RELOAD
            emit(State.VERIFYING, token, report)
            when (prepared.checkForward(snapshot())) {
                NfcProvisionPlan.ForwardCheck.PROPOSED_PRESENT -> State.VERIFIED
                NfcProvisionPlan.ForwardCheck.BASELINE_MATCH -> State.UNCHANGED
                NfcProvisionPlan.ForwardCheck.CONFLICT -> State.CONFLICT
            }
        }
    }

    fun rollback(token: String, retain: (NfcProvisionPlan.Replacement) -> Unit = {}, report: (Result) -> Unit): Result {
        require(result.token == token && !result.busy && result.state !in setOf(State.UNKNOWN, State.AWAITING_RELOAD))
        val prepared = checkNotNull(plan)
        var submitted = false
        return step(State.ROLLING_BACK, token, report, { submitted }) {
            val rollback = prepared.rollbackAgainst(snapshot())
            direction = Direction.ROLLBACK
            if (rollback.check == NfcProvisionPlan.RollbackCheck.ALREADY_REVERTED) {
                rollbackVerified = true
                return@step State.ROLLED_BACK
            }
            val replacement = rollback.candidate ?: return@step State.CONFLICT
            rollbackReplacement = replacement
            val archive = NfcRestoreArchive.rollback(replacement, backend.metadata())
            retain(replacement)
            emit(State.CONFIRMING, token, report)
            submitted = true
            val completion = backend.restore(archive)
            if (completion.uncertain || completion.exitCode != 0) return@step State.UNKNOWN
            if (source == NfcProvisionPlan.BaselineSource.RUNTIME) return@step State.AWAITING_RELOAD
            emit(State.VERIFYING, token, report)
            if (!replacement.matchesReplacement(snapshot())) return@step State.CONFLICT
            rollbackVerified = true
            State.ROLLED_BACK
        }
    }

    fun reload(token: String, report: (Result) -> Unit): Result {
        require(result.token == token && result.state in setOf(State.VERIFIED, State.ALREADY_PRESENT,
            State.ROLLED_BACK, State.RELOADED, State.AWAITING_RELOAD))
        return step(State.RELOADING, token, report, { true }) {
            if (!backend.reload()) return@step State.UNKNOWN
            if (source == NfcProvisionPlan.BaselineSource.FILE) return@step State.RELOADED
            emit(State.VERIFYING, token, report)
            val current = snapshot()
            if (direction == Direction.ROLLBACK) {
                val replacement = rollbackReplacement
                if (replacement?.matchesReplacement(current) == true || (replacement == null &&
                    plan?.rollbackAgainst(current)?.check == NfcProvisionPlan.RollbackCheck.ALREADY_REVERTED)) {
                    rollbackVerified = true
                    State.RELOADED
                } else State.CONFLICT
            } else if (plan != null) {
                when (plan!!.checkForward(current)) {
                    NfcProvisionPlan.ForwardCheck.PROPOSED_PRESENT -> State.RELOADED
                    NfcProvisionPlan.ForwardCheck.BASELINE_MATCH -> State.UNCHANGED
                    NfcProvisionPlan.ForwardCheck.CONFLICT -> State.CONFLICT
                }
            } else {
                val identity = NfcProvisionPlan.currentIdentity(current, NfcProvisionPlan.Target.AIME, ownerUid)
                if (identity?.systemCode == "88B4" && identity.nfcid2 == requestedIdm) State.RELOADED else State.CONFLICT
            }
        }
    }

    fun canActivate() = direction == Direction.FORWARD && !rollbackVerified &&
        result.state in setOf(State.ALREADY_PRESENT, State.RELOADED)

    private fun emit(state: State, token: String, report: (Result) -> Unit) {
        result = Result(state, token)
        report(result)
    }

    private fun step(state: State, token: String, report: (Result) -> Unit,
        submitted: () -> Boolean = { false }, body: () -> State): Result {
        emit(state, token, report)
        result = try { Result(body(), token) }
        catch (error: Exception) { Result(if (submitted() || error is UncertainOperation) State.UNKNOWN else State.FAILED,
            token, (error as? NfcBackupException)?.reason?.name ?: (error as? NfcRuntimeBaseline.Failure)?.reason?.name
                ?: error.javaClass.simpleName) }
        catch (error: LinkageError) { Result(if (submitted()) State.UNKNOWN else State.FAILED, token, error.javaClass.simpleName) }
        return result
    }
}
