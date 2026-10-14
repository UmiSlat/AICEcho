package io.github.umislat.aicecho.root

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

internal object CommandRunner {
    data class Result(val exitCode: Int, val output: String, val timedOut: Boolean = false) {
        val succeeded: Boolean get() = !timedOut && exitCode == 0
    }

    fun shell(command: String, root: Boolean, timeoutSeconds: Long = 12): Result {
        val process = try {
            ProcessBuilder(if (root) listOf("su", "-c", command) else listOf("sh", "-c", command))
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            return Result(-1, error.message.orEmpty())
        }

        val output = StringBuilder()
        val readerThread = Thread {
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    if (output.length < MAX_OUTPUT) output.appendLine(line)
                }
            }
        }.apply {
            name = "aicecho-command-output"
            isDaemon = true
            start()
        }

        val completed = runCatching { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!completed) process.destroyForcibly()
        runCatching { readerThread.join(1_000) }
        return Result(if (completed) process.exitValue() else -1, output.toString().trim(), !completed)
    }

    private const val MAX_OUTPUT = 16 * 1024
}
