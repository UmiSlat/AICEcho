package io.github.umislat.aimesimulator.provisioning

import io.github.umislat.aimesimulator.provisioning.RestoreProbeWorkflow.State
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

class RestoreProbeTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private fun certificate() = checkNotNull(javaClass.getResourceAsStream("/restore-probe-signer.hex"))
        .bufferedReader().use { it.readText().trim().chunked(2).map { byte -> byte.toInt(16).toByte() }.toByteArray() }

    private inner class Backend : RestoreProbeWorkflow.Backend {
        var calls = 0
        var signer = this@RestoreProbeTest.certificate()
        var completion = RestoreProbeWorkflow.Completion(0, false)
        var ce: ByteArray? = RestoreProbeArchive.marker(token, false)
        var de: ByteArray? = RestoreProbeArchive.marker(token, true)
        var failRestore = false
        override fun certificate() = signer
        override fun restore(archive: ByteArray): RestoreProbeWorkflow.Completion {
            calls++
            if (failRestore) error("Disconnected after dispatch")
            return completion
        }
        override fun readMarker(deviceProtected: Boolean) = if (deviceProtected) de else ce
    }

    @Test fun requiresCommandCompletionAndBothExactMarkers() {
        val backend = Backend()
        val phases = mutableListOf<State>()
        val result = RestoreProbeWorkflow(backend).run(token) { phases += it.state }
        assertEquals(State.VERIFIED, result.state)
        assertEquals(listOf(State.PREPARING, State.CONFIRMING, State.VERIFYING), phases)
        assertEquals(1, backend.calls)
    }

    @Test fun cancelledRestoreWithZeroExitIsNotSuccess() {
        val backend = Backend().apply { ce = "baseline-ce\n".toByteArray(); de = "baseline-de\n".toByteArray() }
        assertEquals(State.NOT_VERIFIED, RestoreProbeWorkflow(backend).run(token) {}.state)
    }

    @Test fun requiresBothDomainsCurrentNonceAndExactNewline() {
        val variants = listOf<ByteArray?>(null, byteArrayOf(), "restored-d_f:$token".toByteArray(),
            RestoreProbeArchive.marker("f".repeat(32), true), RestoreProbeArchive.marker(token, false))
        for (variant in variants) {
            val backend = Backend().apply { de = variant }
            assertEquals(State.NOT_VERIFIED, RestoreProbeWorkflow(backend).run(token) {}.state)
        }
        val backend = Backend().apply { ce = null }
        assertFalse(RestoreProbeWorkflow(backend).matches(token))
    }

    @Test fun timeoutIsUnknownEvenIfMarkersMatch() {
        val backend = Backend().apply { completion = RestoreProbeWorkflow.Completion(0, true) }
        assertEquals(State.UNKNOWN, RestoreProbeWorkflow(backend).run(token) {}.state)
        assertEquals(1, backend.calls)
    }

    @Test fun dispatchExceptionIsUnknownAndNotRetried() {
        val backend = Backend().apply { failRestore = true }
        assertEquals(State.UNKNOWN, RestoreProbeWorkflow(backend).run(token) {}.state)
        assertEquals(1, backend.calls)
    }

    @Test fun nonZeroExitDoesNotVerify() {
        val backend = Backend().apply { completion = RestoreProbeWorkflow.Completion(1, false) }
        assertEquals(State.NOT_VERIFIED, RestoreProbeWorkflow(backend).run(token) {}.state)
    }

    @Test fun wrongSignerFailsBeforeSubmitting() {
        val backend = Backend().apply { signer = ByteArray(128).apply { this[0] = 0x30 } }
        assertEquals(State.FAILED, RestoreProbeWorkflow(backend).run(token) {}.state)
        assertEquals(0, backend.calls)
    }

    @Test fun invalidTokensCannotReachBackend() {
        for (invalid in listOf("", "0".repeat(31), "F".repeat(32), "../" + "0".repeat(29), "$token\n")) {
            val backend = Backend()
            assertThrows(IllegalArgumentException::class.java) { RestoreProbeWorkflow(backend).run(invalid) {} }
            assertEquals(0, backend.calls)
        }
    }

    @Test fun readBackDoesNotStartRestore() {
        val backend = Backend()
        assertTrue(RestoreProbeWorkflow(backend).matches(token))
        assertEquals(0, backend.calls)
    }

    @Test fun archiveContainsOnlyOwnedManifestAndBothMarkers() {
        val cert = certificate()
        assertEquals(RestoreProbeArchive.SIGNER_SHA256, RestoreProbeArchive.sha256(cert))
        val archive = RestoreProbeArchive.build(cert, token)
        java.io.File("build/reports/restore-probe").apply { mkdirs() }
            .resolve("owned-marker.ab").writeBytes(archive)
        val prefix = "ANDROID BACKUP\n5\n1\nnone\n".toByteArray()
        assertArrayEquals(prefix, archive.copyOfRange(0, prefix.size))
        assertTrue(archive.size <= 4096)
        val tar = InflaterInputStream(ByteArrayInputStream(archive, prefix.size, archive.size - prefix.size)).readBytes()
        var offset = 0
        val files = linkedMapOf<String, ByteArray>()
        while (tar[offset] != 0.toByte()) {
            val header = tar.copyOfRange(offset, offset + 512)
            fun field(start: Int, size: Int) = String(header, start, size, Charsets.US_ASCII).trimEnd('\u0000', ' ')
            val storedChecksum = field(148, 8).toInt(8)
            repeat(8) { header[148 + it] = 32 }
            assertEquals(storedChecksum, header.sumOf { it.toInt() and 255 })
            assertEquals("ustar", field(257, 6))
            assertEquals('0'.code.toByte(), header[156])
            assertEquals(384, field(100, 8).toInt(8))
            val size = field(124, 12).toInt(8)
            files[field(0, 100)] = tar.copyOfRange(offset + 512, offset + 512 + size)
            offset += 512 + ((size + 511) / 512) * 512
        }
        assertEquals(1024, tar.size - offset)
        assertTrue(tar.drop(offset).all { it == 0.toByte() })
        val base = "apps/${RestoreProbeArchive.PACKAGE}"
        assertEquals(listOf("$base/_manifest", "$base/f/restore_probe.txt", "$base/d_f/restore_probe.txt"), files.keys.toList())
        assertArrayEquals(RestoreProbeArchive.marker(token, false), files["$base/f/restore_probe.txt"])
        assertArrayEquals(RestoreProbeArchive.marker(token, true), files["$base/d_f/restore_probe.txt"])
        assertEquals("1\n${RestoreProbeArchive.PACKAGE}\n1\n36\n\n0\n1\n${RestoreProbeArchive.hex(cert)}\n",
            String(checkNotNull(files["$base/_manifest"])))
    }
}
