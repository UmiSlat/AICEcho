package io.github.umislat.aimesimulator.ui

import io.github.umislat.aimesimulator.R
import io.github.umislat.aimesimulator.provisioning.NfcProvisionWorkflow.State
import io.github.umislat.aimesimulator.provisioning.ShizukuProbeModel

internal enum class CardSetupStep(val stage: Int, val label: Int) {
    CONNECT(0, R.string.shizuku_connect),
    CHECK(0, R.string.card_configure_and_use),
    RESUME(0, R.string.card_continue_setup),
    INITIALIZE(0, R.string.card_continue_setup),
    APPLY(1, R.string.setup_apply),
    RELOAD(2, R.string.setup_load),
    START(3, R.string.emulation_start),
    BLOCKED(0, R.string.card_continue_setup),
    BUSY(0, R.string.setup_working);

    fun enabled(screen: ShizukuProbeModel.Screen, matches: Boolean, hasCard: Boolean): Boolean = when (this) {
        CONNECT -> screen.connection !in setOf(ShizukuProbeModel.Connection.CONNECTING, ShizukuProbeModel.Connection.PERMISSION)
        CHECK -> screen.canPrepare && hasCard
        RESUME -> screen.canResumePlan
        INITIALIZE -> screen.canPrepare && hasCard && matches
        APPLY -> screen.canApply && matches
        RELOAD -> screen.canReload
        START -> screen.canActivate && matches && !screen.busy && !screen.provisionPending
        BLOCKED, BUSY -> false
    }

    companion object {
        fun resolve(screen: ShizukuProbeModel.Screen, matches: Boolean): CardSetupStep = when {
            screen.busy -> BUSY
            screen.connection != ShizukuProbeModel.Connection.READY -> CONNECT
            screen.pending || screen.snapshotPending || screen.provisionPending || screen.provision.state == State.UNKNOWN -> BLOCKED
            screen.savedPlanRequiresResume && !screen.planSaved -> RESUME
            screen.provision.state in setOf(State.VERIFIED, State.ROLLED_BACK, State.AWAITING_RELOAD) -> RELOAD
            matches && screen.canActivate -> START
            matches && screen.provision.state in setOf(State.PREPARED, State.UNCHANGED) -> APPLY
            matches && screen.provision.state == State.NEEDS_BASELINE -> INITIALIZE
            else -> CHECK
        }

        fun stage(screen: ShizukuProbeModel.Screen, step: CardSetupStep): Int = when (screen.provision.state) {
            State.CHECKING, State.CONFIRMING, State.VERIFYING, State.ROLLING_BACK -> 1
            State.RELOADING, State.VERIFIED, State.AWAITING_RELOAD, State.ROLLED_BACK -> 2
            else -> step.stage
        }
    }
}
