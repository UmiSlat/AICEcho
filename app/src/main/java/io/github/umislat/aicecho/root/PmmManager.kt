package io.github.umislat.aicecho.root

import android.os.Build

internal object PmmManager {
    enum class Status { UNAVAILABLE, DISABLED, WAITING, ACTIVE, ERROR }

    data class Snapshot(
        val status: Status,
        val detail: String,
        val modernModule: Boolean = Build.VERSION.SDK_INT >= 35
    )

    fun inspect(): Snapshot = if (Build.VERSION.SDK_INT >= 35) inspectModule() else inspectLegacy()

    fun setEnabled(enabled: Boolean): Snapshot {
        val command = if (Build.VERSION.SDK_INT >= 35) {
            if (enabled) "$MODULE_SERVICE enable" else "$MODULE_SERVICE disable"
        } else {
            val flag = if (enabled) "true" else "false"
            "setprop $LEGACY_ENABLED $flag; setprop ctl.restart nfc"
        }
        val result = CommandRunner.shell(command, root = true, timeoutSeconds = 45)
        if (!result.succeeded) {
            return Snapshot(Status.ERROR, result.output.ifBlank { "Root command failed (${result.exitCode})" })
        }
        return inspect()
    }

    private fun inspectLegacy(): Snapshot {
        val result = CommandRunner.shell(
            "getprop $LEGACY_ENABLED; getprop $LEGACY_ACTIVE",
            root = false
        )
        if (!result.succeeded) return Snapshot(Status.UNAVAILABLE, "Android property query failed", false)
        val values = result.output.lineSequence().map(String::trim).toList()
        val enabled = values.getOrNull(0).equals("true", ignoreCase = true)
        val active = values.getOrNull(1).equals("true", ignoreCase = true)
        return when {
            active -> Snapshot(Status.ACTIVE, "Framework hook is active", false)
            enabled -> Snapshot(Status.WAITING, "Enabled; waiting for the NFC process", false)
            else -> Snapshot(Status.DISABLED, "Framework hook is disabled", false)
        }
    }

    private fun inspectModule(): Snapshot {
        val result = CommandRunner.shell("$MODULE_SERVICE status", root = true)
        if (!result.succeeded) {
            val missing = result.output.contains("not found", true) || result.exitCode == 127
            return Snapshot(if (missing) Status.UNAVAILABLE else Status.ERROR,
                result.output.ifBlank { "KernelSU module is not available" })
        }
        val fields = result.output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        val state = fields["state"].orEmpty()
        val detail = fields["detail"].orEmpty().ifBlank { state }
        val status = when (state) {
            "active" -> Status.ACTIVE
            "waiting", "injecting" -> Status.WAITING
            "disabled" -> Status.DISABLED
            "error" -> Status.ERROR
            else -> Status.UNAVAILABLE
        }
        return Snapshot(status, detail.ifBlank { "No module state" })
    }

    private const val MODULE_SERVICE = "/data/adb/modules/aicecho_pmm/service.sh"
    private const val LEGACY_ENABLED = "tmp.aicecho.pmm.enabled"
    private const val LEGACY_ACTIVE = "tmp.aicecho.pmm.active"
}
