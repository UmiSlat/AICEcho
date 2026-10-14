package io.github.umislat.aicecho.provisioning

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class)
class NfcFServiceDumpTest {
    private val start = "Registered HCE-F services for current user:"
    private val end = "Preferred services (in order of importance):"
    private val component = "example.synthetic/example.synthetic.Host"
    private val id = "02FE010203040506"
    private val pmm = "0011223344556677"

    private fun record(component: String = this.component, uid: Int = 10001,
        sc: String = "4000", nfcid2: String = id, pmm: String = this.pmm,
        description: String = "Synthetic service") =
        "    ComponentInfo{$component} (Description: $description) (UID: $uid)\n" +
            "    System Code: $sc\n    NFCID2: $nfcid2\n    T3tPmm: $pmm\n"

    private fun section(body: String = "User 0\n${record()}") = "$start \n$body\n$end\n"
    private fun rejects(text: String) = assertThrows(IllegalArgumentException::class.java) {
        NfcFServiceDump.parse(text)
    }

    @Test fun readsObservedFourLineShapeAndCanonicalizesShortClassName() {
        val actual = NfcFServiceDump.parse(section("User 0\n${record(component = "example.synthetic/.Host")}"))
        assertEquals(listOf(NfcFServiceDump.Entry(component, 10001, "4000", id, pmm)), actual)
    }

    @Test fun acceptsNumericMaskedAndShortUnicodeUserLabelsWithoutUsingThemAsIdentity() {
        for (label in listOf("0", "123", "Own***", "Work profile ***", "\u5408\u6210")) {
            val result = NfcFServiceDump.parse(section("User $label\n${record(uid = 110001)}"))
            assertEquals(110001, result.single().uid)
        }
    }

    @Test fun repeatedMaskedTitlesAndTheSameComponentInDifferentUsersAreAllowed() {
        val text = section("User Wor***\n${record()}\nUser Wor***\n${record(uid = 110001)}\nUser Empty***\n")
        val entries = NfcFServiceDump.parse(text)
        assertEquals(listOf(10001, 110001), entries.map { it.uid })
        assertEquals(listOf(component, component), entries.map { it.component })
    }

    @Test fun allowsSharedUidForDifferentComponentsAndNestedServiceClasses() {
        val other = "example.synthetic/.Outer\$Host"
        val entries = NfcFServiceDump.parse(section("User 0\n${record()}${record(component = other)}"))
        assertEquals(2, entries.size)
        assertEquals("example.synthetic/example.synthetic.Outer\$Host", entries[1].component)
    }

    @Test fun acceptsCrlfTrailingWhitespaceAndDescriptionsWithParentheses() {
        val text = section("User 0\n${record(description = "Synthetic (UID: 8) (secondary)")}")
            .replace("\n", " \t\r\n")
        assertEquals(id, NfcFServiceDump.parse(text).single().nfcid2)
    }

    @Test fun acceptsCompleteEmptySectionsAndEmptyProfileGroups() {
        assertTrue(NfcFServiceDump.parse(section("")).isEmpty())
        assertTrue(NfcFServiceDump.parse(section("User 0\n\nUser Emp***\n")).isEmpty())
    }

    @Test fun normalizesHexButPreservesNullAndRandomWithoutDroppingRecords() {
        val hex = record(sc = "88b4", nfcid2 = "012e01020304abcd", pmm = "aabbccddeeff0011")
        assertEquals(NfcFServiceDump.Entry(component, 10001, "88B4", "012E01020304ABCD", "AABBCCDDEEFF0011"),
            NfcFServiceDump.parse(section("User 0\n$hex")).single())
        for (value in listOf("NULL", "RANDOM")) {
            val entry = NfcFServiceDump.parse(section("User 0\n${record(sc = "NULL", nfcid2 = value)}")).single()
            assertEquals("NULL", entry.systemCode)
            assertEquals(value, entry.nfcid2)
        }
    }

    @Test fun knownFieldsMayBeReorderedButMustAllBePresent() {
        val lines = record().trimEnd().lines()
        val reordered = listOf(lines[0], lines[3], lines[1], lines[2]).joinToString("\n")
        assertEquals(id, NfcFServiceDump.parse(section("User 0\n$reordered")).single().nfcid2)
    }

    @Test fun ignoresOtherSectionsLogsAndNfcSnoopOutsideTheCompleteSection() {
        val outside = "Registered HCE services for current user:\nUser 0\n" +
            record(component = "example.outside/.Fake") + "$end\n" +
            "10-09 12:34:56 NfcLog: $start\n" +
            "NFCsnoop:\n    NFCID2: not-a-record\n    $start\n"
        val entries = NfcFServiceDump.parse(outside + section() + outside + record())
        assertEquals(listOf(NfcFServiceDump.Entry(component, 10001, "4000", id, pmm)), entries)
    }

    @Test fun refusesMissingMarkersTruncatedBodiesAndDuplicateSections() {
        for (text in listOf("", record(), "$end\n", "$start\n", "$start\nUser 0\n${record()}",
            section().substringBefore("    T3tPmm:"), section().replace(end, "Preferred services:"),
            section() + section(), "$start\n${section()}", section() + "$start\n")) {
            rejects(text)
        }
    }

    @Test fun prefixedOrIndentedHeadingsCannotTurnLogRecordsIntoASection() {
        for (prefix in listOf("    ", "NfcLog: ", "10-09 12:34:56 ", "> ")) {
            rejects(section().lineSequence().joinToString("\n") { prefix + it })
        }
        rejects(section().replace(end, "    $end"))
    }

    @Test fun rejectsMissingFieldsAtEveryRecordBoundaryInsteadOfReturningPartialResults() {
        val lines = record().trimEnd().lines()
        for (index in 1..3) {
            val incomplete = lines.filterIndexed { i, _ -> i != index }.joinToString("\n") + "\n"
            for (tail in listOf("", record(component = "example.other/.Host"), "User 10\n")) {
                rejects(section("User 0\n$incomplete$tail"))
            }
            rejects(section("User 0\n${record(component = "example.other/.Host")}$incomplete"))
        }
    }

    @Test fun rejectsDuplicateKnownFieldsEvenIfValuesMatch() {
        for (field in record().trimEnd().lines().drop(1)) {
            rejects(section("User 0\n${record()}$field\n"))
        }
    }

    @Test fun rejectsUnknownFieldsStrayLinesAndPartiallyLoggedRecordsInsideSection() {
        for (extra in listOf("    Dynamic NFCID2: $id", "    Description: extra", "    Other: value",
            "System Code: 4000", "    NFCID2=$id", "    NFCsnoop:", "10-09 NfcLog: ${record().lineSequence().first()}",
            "Failed to dump HCE-F services: Synthetic error")) {
            rejects(section("User 0\n${record()}$extra\n"))
        }
        rejects(section("User 0\n    System Code: 4000\n${record()}"))
        rejects(section("User 0\n${record().replace("    NFCID2:", "        NFCID2:")}"))
    }

    @Test fun rejectsMissingUserHeadingsAndBlankUserLabels() {
        rejects(section(record()))
        rejects(section("User \n${record()}"))
        rejects(section("User \u2003\n${record()}"))
        rejects(section("    User 0\n${record()}"))
    }

    @Test fun rejectsCanonicalDuplicatesAcrossIdenticalOrDifferentUserTitles() {
        for (title in listOf("", "User 0\n", "User Dif***\n")) {
            rejects(section("User 0\n${record()}$title${record(component = "example.synthetic/.Host")}"))
        }
    }

    @Test fun rejectsDifferentUidsForOneComponentInTheSameUidUser() {
        for (uid in listOf(10002, 99999)) {
            rejects(section("User 0\n${record()}User 10\n${record(component = "example.synthetic/.Host", uid = uid)}"))
        }
        rejects(section("User Wor***\n${record(uid = 110001)}User Dup***\n${record(uid = 110002)}"))
    }

    @Test fun rejectsInvalidUidsAndMalformedEntryHeaders() {
        for (uid in listOf("-1", "+10001", "01", "2147483648", "99999999999", "1.5", "unknown", "")) {
            rejects(section("User 0\n${record().replace("(UID: 10001)", "(UID: $uid)")}"))
        }
        for (text in listOf(record().replace(" (Description: Synthetic service)", ""),
            record().replace(" (UID: 10001)", ""),
            record().replace("(UID: 10001)", "(UID: 10001) (Unknown: 1)"))) {
            rejects(section("User 0\n$text"))
        }
        assertEquals(Int.MAX_VALUE, NfcFServiceDump.parse(section("User 0\n${record(uid = Int.MAX_VALUE)}")).single().uid)
    }

    @Test fun rejectsMalformedComponentsRatherThanGuessingNames() {
        for (value in listOf("", "example.synthetic", "/.Host", "example.synthetic/", "example.synthetic//Host",
            "example..synthetic/.Host", "example.synthetic/..Host", "example.synthetic/.Bad-Name",
            "example.synthetic/.Bad Name", "1example.synthetic/.Host", "example.synthetic/.1Host")) {
            rejects(section("User 0\n${record(component = value)}"))
        }
    }

    @Test fun rejectsWrongHexLengthsAndInvalidPlaceholderLocations() {
        for (value in listOf("", "400", "40000", "0x40", "GGGG", "RANDOM", "null", " 4000")) {
            rejects(section("User 0\n${record(sc = value)}"))
        }
        for (value in listOf("", id.dropLast(1), id + "0", "G".repeat(16), "null", "random", "  $id")) {
            rejects(section("User 0\n${record(nfcid2 = value)}"))
        }
        for (value in listOf("", "NULL", "RANDOM", pmm.dropLast(1), pmm + "0", "G".repeat(16))) {
            rejects(section("User 0\n${record(pmm = value)}"))
        }
    }

    @Test fun rejectsControlCharactersAndEmbeddedLineBreaksInsideRecords() {
        for (value in listOf("bad\u0000description", "bad\u001bdescription", "bad\rdescription", "bad\tdescription",
            "bad\u007fdescription", "bad\ndescription")) {
            rejects(section("User 0\n${record(description = value)}"))
        }
    }

    @Test fun boundsWholeInputSectionLinesLabelsComponentsEntriesAndGroups() {
        rejects("x".repeat(1024 * 1024 + 1))
        rejects(section("User 0\n" + "\n".repeat(128 * 1024)))
        rejects(section("User 0\n${record(description = "x".repeat(4097))}"))
        rejects(section("User " + "x".repeat(129) + "\n" + record()))
        rejects(section("User 0\n${record(component = "x".repeat(1025) + "/.Host")}"))
        rejects(section((0..64).joinToString("\n") { "User $it" }))
        fun body(count: Int) = "User 0\n" + (0 until count).joinToString("\n") {
            record(component = "example.synthetic/.Host$it")
        }
        assertEquals(128, NfcFServiceDump.parse(section(body(128))).size)
        rejects(section(body(129)))
    }

    @Test fun entryAndFailureMessagesDoNotDiscloseIdentityData() {
        val entry = NfcFServiceDump.parse(section()).single()
        assertEquals("Entry(redacted)", entry.toString())
        val failure = rejects(section("User 0\n${record(nfcid2 = "invalid-$id")}"))
        for (value in listOf(component, "10001", id, pmm, "invalid-$id")) {
            assertFalse(entry.toString().contains(value))
            assertFalse(failure.toString().contains(value))
        }
    }
}
