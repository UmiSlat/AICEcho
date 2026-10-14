package io.github.umislat.aicecho.provisioning

/**
 * Reads effective values from one complete current-user HCE-F dump section, including any
 * profiles the service chose to print. This is neither an all-user inventory nor the dynamic
 * identity file: static declarations and dynamic overrides are indistinguishable here.
 */
internal object NfcFServiceDump {
    data class Entry(
        val component: String,
        val uid: Int,
        val systemCode: String,
        val nfcid2: String,
        val pmm: String
    ) {
        override fun toString() = "Entry(redacted)"
    }

    private const val START = "Registered HCE-F services for current user:"
    private const val END = "Preferred services (in order of importance):"
    private const val MAX_TEXT = 1024 * 1024
    private const val MAX_SECTION = 128 * 1024
    private const val MAX_LINE = 4096
    private const val MAX_ENTRIES = 128
    private const val MAX_USERS = 64
    private const val PER_USER_RANGE = 100000
    private val header = Regex("    ComponentInfo\\{([^{}\\s]+)\\} \\(Description: .*\\) \\(UID: (0|[1-9][0-9]{0,9})\\)")
    private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val className = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val systemCode = Regex("[0-9A-Fa-f]{4}")
    private val eightBytes = Regex("[0-9A-Fa-f]{16}")
    private val fields = listOf("System Code", "NFCID2", "T3tPmm")

    /**
     * Rejects incomplete or ambiguous sections with redacted IllegalArgumentExceptions.
     * Limits apply to String characters; the caller must also bound the incoming byte stream.
     * A complete, empty section returns no observations, not proof of an empty identity file.
     */
    fun parse(text: String): List<Entry> {
        require(text.length <= MAX_TEXT) { "NFC dump size limit" }
        val entries = mutableListOf<Entry>()
        val seen = mutableMapOf<Pair<Int, String>, Int>()
        var started = false
        var ended = false
        var sectionSize = 0
        var users = 0
        var pending: Pending? = null

        fun finishEntry() {
            val current = pending ?: return
            require(current.values.keys == fields.toSet()) { "Incomplete NFC-F entry" }
            entries.add(Entry(current.component, current.uid, current.values.getValue("System Code"),
                current.values.getValue("NFCID2"), current.values.getValue("T3tPmm")))
            pending = null
        }

        for (raw in text.splitToSequence('\n')) {
            val line = raw.removeSuffix("\r").trimEnd(' ', '\t')
            // Match whole, unprefixed headings, never copies embedded in a log message.
            if (line == START) {
                require(!started) { "Duplicate NFC-F section" }
                started = true
                continue
            }
            if (!started || ended) continue
            sectionSize += raw.length + 1
            require(sectionSize <= MAX_SECTION && raw.length <= MAX_LINE) { "NFC-F section size limit" }
            require(line.none { it.code < 32 || it.code == 127 }) { "Invalid NFC-F section character" }

            when {
                line == END -> {
                    finishEntry()
                    ended = true
                }
                line.isEmpty() -> Unit
                line.startsWith("User ") -> {
                    finishEntry()
                    val label = line.removePrefix("User ")
                    require(label.isNotBlank() && label.length <= 128 && ++users <= MAX_USERS) {
                        "Invalid NFC-F user heading"
                    }
                    // Names can be masked, duplicated or numeric; only an entry's UID identifies it.
                }
                line.startsWith("    ComponentInfo{") -> {
                    require(users > 0) { "Missing NFC-F user heading" }
                    finishEntry()
                    require(entries.size < MAX_ENTRIES) { "NFC-F entry count limit" }
                    val match = requireNotNull(header.matchEntire(line)) { "Invalid NFC-F entry header" }
                    val component = canonicalComponent(match.groupValues[1])
                    val uid = requireNotNull(match.groupValues[2].toIntOrNull()) { "Invalid NFC-F UID" }
                    val previous = seen.put(uid / PER_USER_RANGE to component, uid)
                    require(previous == null) {
                        if (previous == uid) "Duplicate NFC-F entry" else "Conflicting NFC-F UID"
                    }
                    pending = Pending(component, uid)
                }
                else -> {
                    val current = requireNotNull(pending) { "Unexpected NFC-F section line" }
                    val name = requireNotNull(fields.firstOrNull { line.startsWith("    $it: ") }) {
                        "Unknown NFC-F entry field"
                    }
                    val value = line.removePrefix("    $name: ")
                    require(!current.values.containsKey(name)) { "Duplicate NFC-F entry field" }
                    require(when (name) {
                        "System Code" -> value == "NULL" || systemCode.matches(value)
                        "NFCID2" -> value == "NULL" || value == "RANDOM" || eightBytes.matches(value)
                        else -> eightBytes.matches(value)
                    }) { "Invalid NFC-F entry value" }
                    current.values[name] = value.uppercase()
                }
            }
        }
        require(started && ended) { "Missing or truncated NFC-F section" }
        return entries.toList()
    }

    private fun canonicalComponent(value: String): String {
        require(value.length <= 1024) { "NFC-F component size limit" }
        val parts = value.split('/')
        require(parts.size == 2 && packageName.matches(parts[0])) { "Invalid NFC-F component" }
        val canonicalClass = if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1]
        require(className.matches(canonicalClass)) { "Invalid NFC-F component" }
        return parts[0] + "/" + canonicalClass
    }

    private class Pending(val component: String, val uid: Int) {
        val values = mutableMapOf<String, String>()
    }
}
