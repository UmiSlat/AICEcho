package io.github.umislat.aicecho.provisioning

import org.junit.Assert.*
import org.junit.Test

class ShellBootCountTest {
    private fun result(text: String) = ProbeProcess.Result(0, text.toByteArray(Charsets.US_ASCII), false)

    @Test fun acceptsOnlyNonNegativeCountsWithOptionalLineEnding() {
        for (text in listOf("185", "185\n", "185\r\n")) assertEquals(185, ShellBootCount.parse(result(text)))
        assertEquals(0, ShellBootCount.parse(result("0\n")))
        assertEquals(Int.MAX_VALUE, ShellBootCount.parse(result("2147483647\n")))
    }

    @Test fun missingMalformedAndOverflowingValuesFailClosed() {
        for (text in listOf("", "null\n", "-1\n", "01\n", " 185\n", "185 \n", "185\n0\n", "185\r",
            "2147483648\n", "99999999999\n", "SecurityException\n")) {
            assertThrows(Exception::class.java) { ShellBootCount.parse(result(text)) }
        }
    }

    @Test fun uncertainOrNonzeroCommandCannotSupplyBootIdentity() {
        assertThrows(Exception::class.java) { ShellBootCount.parse(result("185\n").copy(uncertain = true)) }
        assertThrows(Exception::class.java) { ShellBootCount.parse(result("185\n").copy(exitCode = 1)) }
    }
}
