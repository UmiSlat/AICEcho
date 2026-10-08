package io.github.umislat.aimesimulator.provisioning

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

/** Fixed CE identity destination only. Building an archive neither submits nor authorizes restore. */
internal object NfcRestoreArchive {
    private const val PACKAGE = NfcSnapshotArchive.PACKAGE
    private const val IDENTITY = "apps/$PACKAGE/f/dynamic_systemcode_nfcid2.xml"

    fun forward(plan: NfcProvisionPlan, certificate: ByteArray) = build(plan.proposedXml(), certificate)
    fun rollback(replacement: NfcProvisionPlan.Replacement, certificate: ByteArray) = build(replacement.xml(), certificate)

    private fun build(xml: ByteArray, certificate: ByteArray): ByteArray {
        require(RestoreProbeArchive.sha256(certificate) == NfcSnapshotArchive.SIGNER)
        NfcSnapshotArchive.validateXml(xml)
        val manifest = "1\n$PACKAGE\n36\n36\n\n0\n1\n${RestoreProbeArchive.hex(certificate)}\n".toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output, 512, "UTF-8").use { tar ->
            for ((path, data) in listOf("apps/$PACKAGE/_manifest" to manifest, IDENTITY to xml)) {
                val entry = TarArchiveEntry(path).apply {
                    size = data.size.toLong()
                    mode = 384
                    setUserId(0)
                    setGroupId(0)
                    userName = ""
                    groupName = ""
                    setModTime(0L)
                }
                tar.putArchiveEntry(entry)
                tar.write(data)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        val payload = output.toByteArray()
        val envelope = "MIUI BACKUP\n2\n$PACKAGE NFC\n0\n0\nANDROID BACKUP\n5\n0\nnone\n".toByteArray(Charsets.US_ASCII)
        check(NfcSnapshotArchive.parse(envelope + payload).xml.contentEquals(xml))
        return ByteArrayOutputStream().apply {
            write("ANDROID BACKUP\n5\n1\nnone\n".toByteArray(Charsets.US_ASCII))
            DeflaterOutputStream(this).use { it.write(payload) }
        }.toByteArray().also { require(it.size <= 256 * 1024) }
    }
}
