package io.github.umislat.aimesimulator.provisioning

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ProbeProcessTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun arguments(mode: String, pid: File): List<String> {
        val java = File(System.getProperty("java.home"), "bin/java" +
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else "")
        val classes = File(checkNotNull(ProcessFixture::class.java.protectionDomain).codeSource.location.toURI())
        return listOf(java.absolutePath, "-cp", classes.absolutePath, ProcessFixture::class.java.name,
            mode, pid.absolutePath)
    }

    private fun assertChildStopped(pid: File) {
        val id = pid.readText().toLong()
        assertFalse("Child must be reaped", ProcessFixture.isAlive(id))
    }

    @Test fun binaryInputAndEofReachTheChildUnchanged() {
        val pid = temporary.newFile()
        val input = ByteArray(1024) { it.toByte() }
        val result = ProbeProcess.run(arguments("echo", pid), input)
        assertFalse(result.uncertain)
        assertEquals(0, result.exitCode)
        // A host JAVA_TOOL_OPTIONS announcement can precede the fixture's binary output.
        assertArrayEquals(input, result.output.takeLast(input.size).toByteArray())
        assertChildStopped(pid)
    }

    @Test fun blockedStdinIsInsideTheDeadline() {
        val pid = temporary.newFile()
        val start = System.nanoTime()
        val result = ProbeProcess.run(arguments("no-read", pid), ByteArray(256 * 1024), seconds = 1)
        assertTrue(result.uncertain)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000)
        assertChildStopped(pid)
    }

    @Test fun childThatNeverExitsIsStoppedEvenWithNoInput() {
        val pid = temporary.newFile()
        val result = ProbeProcess.run(arguments("no-read", pid), seconds = 1)
        assertTrue(result.uncertain)
        assertChildStopped(pid)
    }

    @Test fun outputOverflowDoesNotWaitForTheLongProcessDeadline() {
        val pid = temporary.newFile()
        val start = System.nanoTime()
        val result = ProbeProcess.run(arguments("flood", pid), seconds = 15)
        assertTrue(result.uncertain)
        assertTrue(result.output.size <= 4096)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000)
        assertChildStopped(pid)
    }

    @Test fun incompleteInputCannotBeReportedAsSettledSuccess() {
        val pid = temporary.newFile()
        val result = ProbeProcess.run(arguments("close-input", pid), ByteArray(256 * 1024))
        assertTrue(result.uncertain)
        assertChildStopped(pid)
    }

    @Test fun settledNonzeroExitIsNotTransportUncertainty() {
        val pid = temporary.newFile()
        val result = ProbeProcess.run(arguments("nonzero", pid))
        assertEquals(17, result.exitCode)
        assertFalse(result.uncertain)
        assertChildStopped(pid)
    }

    @Test fun invalidLimitsAreRejectedBeforeStartingAChild() {
        val pid = temporary.newFile()
        val command = arguments("echo", pid)
        for (seconds in listOf(0L, -1L, 301L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ProbeProcess.run(command, seconds = seconds) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProbeProcess.run(command, ByteArray(256 * 1024 + 1)) }
        assertEquals(0L, pid.length())
    }

    @Test fun anAlreadyInterruptedCallDoesNotStartAChild() {
        val pid = temporary.newFile()
        Thread.currentThread().interrupt()
        try {
            assertTrue(ProbeProcess.run(arguments("echo", pid)).uncertain)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0L, pid.length())
        } finally { Thread.interrupted() }
    }

    @Test fun interruptStopsChildAndPreservesCallerInterrupt() {
        val pid = temporary.newFile()
        val failure = AtomicReference<Throwable?>(null)
        val result = AtomicReference<ProbeProcess.Result?>(null)
        val interrupted = AtomicBoolean()
        val worker = Thread {
            try { result.set(ProbeProcess.run(arguments("no-read", pid), ByteArray(256 * 1024), 15)) }
            catch (error: Throwable) { failure.set(error) }
            finally { interrupted.set(Thread.currentThread().isInterrupted) }
        }
        worker.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (pid.length() == 0L && worker.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Fixture must have started", pid.length() > 0)
            worker.interrupt()
            worker.join(5000)
            assertFalse("Interrupted call must return", worker.isAlive)
            assertNull(failure.get())
            assertTrue(checkNotNull(result.get()).uncertain)
            assertTrue(interrupted.get())
            assertChildStopped(pid)
        } finally {
            worker.interrupt()
            worker.join(10_000)
        }
    }
}
