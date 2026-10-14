package io.github.umislat.aicecho.nfc

internal object HceActivationRetryPolicy {
    fun isLikelyActiveLink(
        report: HceSession.Report,
        previousReport: HceSession.Report?,
        retryingLinkRelease: Boolean
    ): Boolean {
        val registrationBlocked = report.stage == HceSession.Stage.ID ||
            report.stage == HceSession.Stage.SYSTEM_CODE
        if (!registrationBlocked) return false

        return retryingLinkRelease || previousReport?.stage == HceSession.Stage.READY ||
            previousReport?.stage == HceSession.Stage.LINK_ACTIVE
    }
}
