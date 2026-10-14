package io.github.umislat.aicecho.nfc

internal data class RootlessAssessment(
    val outcome: Outcome,
    val detail: String = ""
) {
    enum class Outcome {
        PROFILE_REQUIRED,
        REGISTRATION_ACCEPTED,
        UNSUPPORTED,
        NFC_DISABLED,
        SERVICE_RESTARTING,
        LINK_ACTIVE,
        DYNAMIC_ID_REJECTED,
        SYSTEM_CODE_REJECTED,
        ENABLE_FAILED,
        ERROR
    }

    companion object {
        fun from(
            report: HceSession.Report?,
            hasProfile: Boolean
        ): RootlessAssessment {
            if (!hasProfile) {
                return RootlessAssessment(Outcome.PROFILE_REQUIRED)
            }
            if (report == null) {
                return RootlessAssessment(Outcome.SERVICE_RESTARTING)
            }
            val outcome = when (report.stage) {
                HceSession.Stage.READY -> Outcome.REGISTRATION_ACCEPTED
                HceSession.Stage.UNSUPPORTED -> Outcome.UNSUPPORTED
                HceSession.Stage.NFC_DISABLED -> Outcome.NFC_DISABLED
                HceSession.Stage.SERVICE_RESTARTING -> Outcome.SERVICE_RESTARTING
                HceSession.Stage.LINK_ACTIVE -> Outcome.LINK_ACTIVE
                HceSession.Stage.ID -> Outcome.DYNAMIC_ID_REJECTED
                HceSession.Stage.SYSTEM_CODE -> Outcome.SYSTEM_CODE_REJECTED
                HceSession.Stage.ENABLE -> Outcome.ENABLE_FAILED
                HceSession.Stage.EXCEPTION -> Outcome.ERROR
            }
            return RootlessAssessment(outcome, report.detail)
        }
    }
}
