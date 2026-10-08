package io.github.umislat.aimesimulator.provisioning

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream

/** A closed three-file format for the separately installed, owned marker app only. */
internal object RestoreProbeArchive {
    const val PACKAGE = "io.github.umislat.restorepathlab"
    const val SIGNER_SHA256 = "3bf1577c690a4a57534afe7338b9534fdba659deedce1e6f3ed408ae750545dc"
    private val tokenPattern = Regex("[0-9a-f]{32}")

    fun validToken(token: String): Boolean = tokenPattern.matches(token)

    fun marker(token: String, deviceProtected: Boolean): ByteArray {
        require(validToken(token)) { "Invalid probe token" }
        return "restored-${if (deviceProtected) "d_f" else "f"}:$token\n".toByteArray(US_ASCII)
    }

    fun build(certificate: ByteArray, token: String): ByteArray {
        require(validToken(token)) { "Invalid probe token" }
        require(certificate.size in 128..2048 && certificate[0] == 0x30.toByte()) {
            "Invalid signer certificate"
        }
        val manifest = "1\n$PACKAGE\n1\n36\n\n0\n1\n${hex(certificate)}\n".toByteArray(US_ASCII)
        val tar = ByteArrayOutputStream()
        entry(tar, "apps/$PACKAGE/_manifest", manifest)
        entry(tar, "apps/$PACKAGE/f/restore_probe.txt", marker(token, false))
        entry(tar, "apps/$PACKAGE/d_f/restore_probe.txt", marker(token, true))
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream().apply {
            write("ANDROID BACKUP\n5\n1\nnone\n".toByteArray(US_ASCII))
            DeflaterOutputStream(this).use { it.write(tar.toByteArray()) }
        }.toByteArray().also { require(it.size <= 4096) { "Probe archive too large" } }
    }

    private fun entry(out: ByteArrayOutputStream, name: String, bytes: ByteArray) {
        val header = ByteArray(512)
        fun text(offset: Int, value: String) = value.toByteArray(US_ASCII).copyInto(header, offset)
        fun octal(offset: Int, width: Int, value: Long) {
            val encoded = value.toString(8).padStart(width - 1, '0') + '\u0000'
            require(encoded.length == width)
            text(offset, encoded)
        }
        require(name.length < 100)
        text(0, name)
        octal(100, 8, 384) // 0600
        octal(108, 8, 0)
        octal(116, 8, 0)
        octal(124, 12, bytes.size.toLong())
        octal(136, 12, 0)
        repeat(8) { header[148 + it] = 32 }
        header[156] = '0'.code.toByte()
        text(257, "ustar\u0000")
        text(263, "00")
        val checksum = header.sumOf { it.toInt() and 255 }
        text(148, checksum.toString(8).padStart(6, '0') + "\u0000 ")
        out.write(header)
        out.write(bytes)
        out.write(ByteArray((512 - bytes.size % 512) % 512))
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
