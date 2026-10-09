package io.github.umislat.aimesimulator.provisioning

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

/** Fixed CE identity destination only. Building an archive neither submits nor authorizes restore. */
internal object NfcRestoreArchive {
    private const val PACKAGE = NfcSnapshotArchive.PACKAGE
    private const val IDENTITY = "apps/$PACKAGE/f/dynamic_systemcode_nfcid2.xml"

    fun forward(plan: NfcProvisionPlan, metadata: NfcBackupMetadata) = build(plan.proposedXml(), metadata)
    fun rollback(replacement: NfcProvisionPlan.Replacement, metadata: NfcBackupMetadata) = build(replacement.xml(), metadata)

    private fun build(xml: ByteArray, metadata: NfcBackupMetadata): ByteArray {
        NfcSnapshotArchive.validateXml(xml)
        val manifest = (listOf("1", PACKAGE, metadata.version.toString(), metadata.sdk.toString(), "", "0",
            metadata.certificates.size.toString()) + metadata.certificates.map(RestoreProbeArchive::hex))
            .joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
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
        return ByteArrayOutputStream().apply {
            write("ANDROID BACKUP\n5\n1\nnone\n".toByteArray(Charsets.US_ASCII))
            DeflaterOutputStream(this).use { it.write(payload) }
        }.toByteArray().also {
            require(it.size <= 256 * 1024)
            check(NfcSnapshotArchive.parse(it, metadata).xml.contentEquals(xml))
        }
    }
}
