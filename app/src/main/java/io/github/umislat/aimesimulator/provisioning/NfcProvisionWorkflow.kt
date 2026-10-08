package io.github.umislat.aimesimulator.provisioning

/** Manual provisioning steps; no automatic restore, retry, reload or rollback. */
internal class NfcProvisionWorkflow(private val backend: Backend, private val ownerUid: Int) {
    class UncertainOperation : IllegalStateException("System operation outcome unknown")
    enum class State {
        IDLE, PREPARING, NEEDS_BASELINE, PREPARED, ALREADY_PRESENT, CHECKING, CONFIRMING,
        VERIFYING, VERIFIED, UNCHANGED, CONFLICT, RELOADING, RELOADED, ROLLING_BACK,
        ROLLED_BACK, FAILED, UNKNOWN
    }
    data class Result(val state: State, val token: String = "", val detail: String = "") {
        val busy get() = state in setOf(State.PREPARING, State.CHECKING, State.CONFIRMING,
            State.VERIFYING, State.RELOADING, State.ROLLING_BACK)
    }
    interface Backend {
        fun snapshot(): ByteArray
        fun certificate(): ByteArray
        fun restore(archive: ByteArray): ProbeProcess.Result
        fun reload(): Boolean
    }

    var plan: NfcProvisionPlan? = null
        private set
    var requestedIdm: String = ""
        private set
    var result = Result(State.IDLE)
        private set
    private var rollbackVerified = false

    fun prepare(token: String, idm: String, report: (Result) -> Unit): Result {
        require(RestoreProbeArchive.validToken(token))
        require(!result.busy && result.state != State.UNKNOWN)
        val desired = NfcProvisionPlan.Identity("88B4", idm)
        require(desired.nfcid2 != "0".repeat(16) && desired.nfcid2 != "F".repeat(16))
        plan = null
        rollbackVerified = false
        requestedIdm = desired.nfcid2
        return step(State.PREPARING, token, report) {
            val xml = backend.snapshot()
            val before = NfcProvisionPlan.currentIdentity(xml, NfcProvisionPlan.Target.AIME, ownerUid)
                ?: return@step State.NEEDS_BASELINE
            if (before.systemCode == desired.systemCode && before.nfcid2 == desired.nfcid2) {
                return@step State.ALREADY_PRESENT
            }
            plan = NfcProvisionPlan.prepare(xml, RestoreProbeArchive.sha256(xml),
                NfcProvisionPlan.Target.AIME, ownerUid, before, desired)
            State.PREPARED
        }
    }

    fun resume(token: String, saved: NfcProvisionPlan, report: (Result) -> Unit): Result {
        require(RestoreProbeArchive.validToken(token))
        require(!result.busy && result.state != State.UNKNOWN)
        require(saved.target == NfcProvisionPlan.Target.AIME && saved.uid == ownerUid)
        plan = saved
        requestedIdm = saved.after.nfcid2
        rollbackVerified = false
        return step(State.CHECKING, token, report) {
            val current = backend.snapshot()
            when (saved.checkForward(current)) {
                NfcProvisionPlan.ForwardCheck.BASELINE_MATCH -> State.PREPARED
                NfcProvisionPlan.ForwardCheck.PROPOSED_PRESENT -> State.VERIFIED
                NfcProvisionPlan.ForwardCheck.CONFLICT -> {
                    if (saved.rollbackAgainst(current).check == NfcProvisionPlan.RollbackCheck.ALREADY_REVERTED) {
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
            when (prepared.checkForward(backend.snapshot())) {
                NfcProvisionPlan.ForwardCheck.CONFLICT -> return@step State.CONFLICT
                NfcProvisionPlan.ForwardCheck.PROPOSED_PRESENT -> return@step State.VERIFIED
                NfcProvisionPlan.ForwardCheck.BASELINE_MATCH -> Unit
            }
            val archive = NfcRestoreArchive.forward(prepared, backend.certificate())
            emit(State.CONFIRMING, token, report)
            submitted = true
            val completion = backend.restore(archive)
            if (completion.uncertain || completion.exitCode != 0) return@step State.UNKNOWN
            emit(State.VERIFYING, token, report)
            when (prepared.checkForward(backend.snapshot())) {
                NfcProvisionPlan.ForwardCheck.PROPOSED_PRESENT -> State.VERIFIED
                NfcProvisionPlan.ForwardCheck.BASELINE_MATCH -> State.UNCHANGED
                NfcProvisionPlan.ForwardCheck.CONFLICT -> State.CONFLICT
            }
        }
    }

    fun rollback(token: String, report: (Result) -> Unit): Result {
        require(result.token == token && !result.busy && result.state != State.UNKNOWN)
        val prepared = checkNotNull(plan)
        var submitted = false
        return step(State.ROLLING_BACK, token, report, { submitted }) {
            val rollback = prepared.rollbackAgainst(backend.snapshot())
            if (rollback.check == NfcProvisionPlan.RollbackCheck.ALREADY_REVERTED) {
                rollbackVerified = true
                return@step State.ROLLED_BACK
            }
            val replacement = rollback.candidate ?: return@step State.CONFLICT
            val archive = NfcRestoreArchive.rollback(replacement, backend.certificate())
            emit(State.CONFIRMING, token, report)
            submitted = true
            val completion = backend.restore(archive)
            if (completion.uncertain || completion.exitCode != 0) return@step State.UNKNOWN
            emit(State.VERIFYING, token, report)
            if (!backend.snapshot().contentEquals(replacement.xml())) return@step State.CONFLICT
            rollbackVerified = true
            State.ROLLED_BACK
        }
    }

    fun reload(token: String, report: (Result) -> Unit): Result {
        require(result.token == token && result.state in setOf(State.VERIFIED, State.ALREADY_PRESENT,
            State.ROLLED_BACK, State.RELOADED))
        return step(State.RELOADING, token, report, { true }) {
            if (backend.reload()) State.RELOADED else State.UNKNOWN
        }
    }

    fun canActivate() = !rollbackVerified && result.state in setOf(State.ALREADY_PRESENT, State.RELOADED)

    private fun emit(state: State, token: String, report: (Result) -> Unit) {
        result = Result(state, token)
        report(result)
    }

    private fun step(state: State, token: String, report: (Result) -> Unit,
        submitted: () -> Boolean = { false }, body: () -> State): Result {
        emit(state, token, report)
        result = try { Result(body(), token) }
        catch (error: Exception) { Result(if (submitted() || error is UncertainOperation) State.UNKNOWN else State.FAILED, token, error.javaClass.simpleName) }
        catch (error: LinkageError) { Result(if (submitted()) State.UNKNOWN else State.FAILED, token, error.javaClass.simpleName) }
        return result
    }
}
