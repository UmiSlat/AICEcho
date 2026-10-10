package io.github.umislat.aimesimulator.nfc

import org.junit.Assert.assertEquals
import org.junit.Test

class RootlessAssessmentTest {
    @Test fun acceptsCompletedAimeRegistration() {
        val assessment = RootlessAssessment.from(
            HceSession.Report(HceSession.Stage.READY),
            hasProfile = true
        )

        assertEquals(RootlessAssessment.Outcome.REGISTRATION_ACCEPTED, assessment.outcome)
    }

    @Test fun reportsOriginalIdentifierRejection() {
        val report = HceSession.Report(HceSession.Stage.ID)

        assertEquals(
            RootlessAssessment.Outcome.DYNAMIC_ID_REJECTED,
            RootlessAssessment.from(
                report,
                hasProfile = true
            ).outcome
        )
    }

    @Test fun reportsSystemCodeAsItsOwnBlocker() {
        val assessment = RootlessAssessment.from(
            HceSession.Report(HceSession.Stage.SYSTEM_CODE),
            hasProfile = true
        )

        assertEquals(RootlessAssessment.Outcome.SYSTEM_CODE_REJECTED, assessment.outcome)
    }

    @Test fun reportsAnActiveReaderLinkWithoutCallingItAnIdentifierRejection() {
        val assessment = RootlessAssessment.from(
            HceSession.Report(HceSession.Stage.LINK_ACTIVE),
            hasProfile = true
        )

        assertEquals(RootlessAssessment.Outcome.LINK_ACTIVE, assessment.outcome)
    }

    @Test fun requiresAProfileBeforeTesting() {
        val assessment = RootlessAssessment.from(
            report = null,
            hasProfile = false
        )

        assertEquals(RootlessAssessment.Outcome.PROFILE_REQUIRED, assessment.outcome)
    }
}
