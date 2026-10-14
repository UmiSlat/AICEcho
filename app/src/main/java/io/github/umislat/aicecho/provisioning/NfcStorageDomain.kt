package io.github.umislat.aicecho.provisioning

import android.content.Context
import java.io.File

internal enum class NfcStorageDomain(val token: String) {
    CE("f"), DE("d_f");

    val archivePath get() = "apps/${NfcSnapshotArchive.PACKAGE}/$token/dynamic_systemcode_nfcid2.xml"

    companion object {
        // getDataDir describes the package's default context without opening or creating its files.
        fun inspect(context: Context): NfcStorageDomain = try {
            fromDataDirectory(context.createPackageContext(NfcSnapshotArchive.PACKAGE, 0).dataDir)
        } catch (error: Exception) {
            throw NfcBackupException(NfcBackupException.Reason.STORAGE_DOMAIN_UNAVAILABLE)
        }

        fun fromDataDirectory(directory: File): NfcStorageDomain {
            require(directory.path.startsWith(File.separator) && directory.name == NfcSnapshotArchive.PACKAGE)
            if (directory == File("/data/data/${NfcSnapshotArchive.PACKAGE}")) return CE
            val user = checkNotNull(directory.parentFile)
            require(user.name == "0")
            return when (user.parentFile?.name) {
                "user" -> CE
                "user_de" -> DE
                else -> error("Unknown NFC storage domain")
            }
        }
    }
}
