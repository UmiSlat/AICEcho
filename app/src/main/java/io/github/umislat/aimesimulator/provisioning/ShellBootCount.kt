package io.github.umislat.aimesimulator.provisioning

import android.os.Process

/** A UserService Context can carry the app's attribution, which Settings rejects for shell. */
internal object ShellBootCount {
    fun read(): Int {
        check(Process.myUid() == 2000)
        return parse(ProbeProcess.run(listOf("/system/bin/settings", "get", "global", "boot_count")))
    }

    fun parse(result: ProbeProcess.Result): Int {
        check(!result.uncertain && result.exitCode == 0 && result.output.size in 1..12)
        val text = result.output.toString(Charsets.US_ASCII)
        require(text.matches(Regex("(?:0|[1-9][0-9]{0,9})(?:\\r?\\n)?")))
        return text.trimEnd('\r', '\n').toInt().also { require(it >= 0) }
    }
}
