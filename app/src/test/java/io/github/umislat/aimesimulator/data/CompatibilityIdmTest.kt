package io.github.umislat.aimesimulator.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CompatibilityIdmTest {
    @Test fun usesDomainSeparatedDigestAnd02fePrefix() {
        assertEquals("02FED782A6CF82BB", CompatibilityIdm.generate("012E112233445566"))
    }

    @Test fun normalizesIdentityBeforeHashing() {
        assertEquals(
            CompatibilityIdm.generate("012E112233445566"),
            CompatibilityIdm.generate("012e 1122 3344 5566")
        )
    }

    @Test fun includesTheOriginalPrefixInsteadOfOnlyItsSuffix() {
        assertNotEquals(
            CompatibilityIdm.generate("012E112233445566"),
            CompatibilityIdm.generate("02FE112233445566")
        )
    }

    @Test fun rejectsMalformedIdentity() {
        assertThrows(IllegalArgumentException::class.java) { CompatibilityIdm.generate("00") }
    }
}
