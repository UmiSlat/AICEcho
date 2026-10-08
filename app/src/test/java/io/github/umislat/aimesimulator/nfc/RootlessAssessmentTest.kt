package io.github.umislat.aimesimulator.nfc

import io.github.umislat.aimesimulator.data.IdmRouteMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RootlessAssessmentTest {
    @Test fun acceptsCompletedAimeRegistration() {
        val assessment = RootlessAssessment.from(
            HceSession.Report(HceSession.Stage.READY),
            routeMode = IdmRouteMode.ORIGINAL,
            hasProfile = true
        )

        assertEquals(RootlessAssessment.Outcome.REGISTRATION_ACCEPTED, assessment.outcome)
    }

    @Test fun distinguishesDynamicAndCompatibilityIdentifierFailures() {
        val report = HceSession.Report(HceSession.Stage.ID)

        assertEquals(
            RootlessAssessment.Outcome.DYNAMIC_ID_REJECTED,
            RootlessAssessment.from(
                report,
                routeMode = IdmRouteMode.ORIGINAL,
                hasProfile = true
            ).outcome
        )
        assertEquals(
            RootlessAssessment.Outcome.COMPATIBILITY_ID_REJECTED,
            RootlessAssessment.from(
                report,
                routeMode = IdmRouteMode.GENERATED_COMPATIBILITY,
                hasProfile = true
            ).outcome
        )
        assertEquals(
            RootlessAssessment.Outcome.COMPATIBILITY_ID_REJECTED,
            RootlessAssessment.from(
                report,
                routeMode = IdmRouteMode.PREFIX_COMPATIBILITY,
                hasProfile = true
            ).outcome
        )
    }

    @Test fun reportsSystemCodeAsItsOwnBlocker() {
        val assessment = RootlessAssessment.from(
            HceSession.Report(HceSession.Stage.SYSTEM_CODE),
            routeMode = IdmRouteMode.PREFIX_COMPATIBILITY,
            hasProfile = true
        )

        assertEquals(RootlessAssessment.Outcome.SYSTEM_CODE_REJECTED, assessment.outcome)
    }

    @Test fun reportsAnActiveReaderLinkWithoutCallingItAnIdentifierRejection() {
        val assessment = RootlessAssessment.from(
            HceSession.Report(HceSession.Stage.LINK_ACTIVE),
            routeMode = IdmRouteMode.GENERATED_COMPATIBILITY,
            hasProfile = true
        )

        assertEquals(RootlessAssessment.Outcome.LINK_ACTIVE, assessment.outcome)
    }

    @Test fun requiresAProfileBeforeTesting() {
        val assessment = RootlessAssessment.from(
            report = null,
            routeMode = IdmRouteMode.ORIGINAL,
            hasProfile = false
        )

        assertEquals(RootlessAssessment.Outcome.PROFILE_REQUIRED, assessment.outcome)
    }
}
