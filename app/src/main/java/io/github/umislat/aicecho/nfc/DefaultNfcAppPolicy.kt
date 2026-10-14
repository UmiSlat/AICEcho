package io.github.umislat.aicecho.nfc

internal object DefaultNfcAppPolicy {
    enum class State {
        DEFAULT,
        NOT_DEFAULT,
        UNAVAILABLE
    }

    enum class Action {
        REQUEST_DEFAULT,
        RESTORE_WALLET,
        NONE
    }

    fun shouldShowGuidance(state: State, guidanceShown: Boolean): Boolean =
        !guidanceShown && state != State.UNAVAILABLE

    fun actionFor(state: State): Action = when (state) {
        State.DEFAULT -> Action.RESTORE_WALLET
        State.NOT_DEFAULT -> Action.REQUEST_DEFAULT
        State.UNAVAILABLE -> Action.NONE
    }
}
