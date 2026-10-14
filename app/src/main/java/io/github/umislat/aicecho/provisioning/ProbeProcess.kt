package io.github.umislat.aicecho.provisioning

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Internal bounded binary transport; no command arguments are accepted over Binder. */
internal object ProbeProcess {
    data class Result(val exitCode: Int, val output: ByteArray, val uncertain: Boolean)

    fun run(arguments: List<String>, input: ByteArray? = null, seconds: Long = 5,
        binaryOutputLimit: Int? = null): Result {
        require(seconds in 1..300 && (input == null || input.size <= 256 * 1024))
        require(binaryOutputLimit == null || binaryOutputLimit in 1..8 * 1024 * 1024)
        if (Thread.currentThread().isInterrupted) return Result(-1, byteArrayOf(), true)
        val bytes = input?.copyOf()
        val process = ProcessBuilder(arguments).redirectErrorStream(binaryOutputLimit == null).start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        val collected = AtomicReference<ByteArray?>(null)
        val failed = AtomicBoolean()
        val streams = CountDownLatch(if (binaryOutputLimit == null) 2 else 3)
        val reader = Thread {
            try {
                process.inputStream.use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > (binaryOutputLimit ?: 4096)) throw IllegalStateException("Output limit")
                        out.write(buffer, 0, count)
                    }
                    collected.set(out.toByteArray())
                }
            } catch (_: Exception) { failed.set(true) }
            finally { streams.countDown() }
        }.apply { isDaemon = true; name = "restore-probe-output"; start() }
        // bu writes the backup to stdout. Drain stderr separately so diagnostics cannot corrupt it.
        val errors = if (binaryOutputLimit == null) null else Thread {
            try {
                process.errorStream.use { stream ->
                    val buffer = ByteArray(1024)
                    while (stream.read(buffer) >= 0) { /* Diagnostics are not archive bytes. */ }
                }
            } catch (_: Exception) { failed.set(true) }
            finally { streams.countDown() }
        }.apply { isDaemon = true; name = "backup-probe-errors"; start() }
        // Writing can block while bu waits for user confirmation without consuming stdin.
        val writer = Thread {
            try { process.outputStream.use { if (bytes != null) it.write(bytes) } }
            catch (_: Exception) { failed.set(true) }
            finally { streams.countDown() }
        }.apply { isDaemon = true; name = "restore-probe-input"; start() }
        var completed = false
        var interrupted = false
        try {
            while (!failed.get()) {
                if (streams.count == 0L && !process.isAlive) { completed = true; break }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                val slice = minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50))
                if (streams.count == 0L) process.waitFor(slice, TimeUnit.NANOSECONDS)
                else streams.await(slice, TimeUnit.NANOSECONDS)
            }
        } catch (_: InterruptedException) {
            interrupted = true
        } finally {
            // Killing this local command does not prove that BackupManager cancelled its work.
            if (process.isAlive) process.destroyForcibly()
            try {
                process.waitFor(500, TimeUnit.MILLISECONDS)
                reader.join(250)
                writer.join(250)
                errors?.join(250)
            } catch (_: InterruptedException) { interrupted = true }
            if (interrupted) Thread.currentThread().interrupt()
        }
        val output = collected.get()
        val uncertain = !completed || failed.get() || interrupted || output == null
        return Result(if (completed) process.exitValue() else -1,
            if (uncertain) byteArrayOf() else checkNotNull(output), uncertain)
    }
}
