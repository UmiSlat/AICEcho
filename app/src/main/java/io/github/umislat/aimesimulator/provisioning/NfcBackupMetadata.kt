package io.github.umislat.aimesimulator.provisioning

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.cert.CertificateFactory

/** Metadata from this device's NFC package, not a vendor certificate or ROM allowlist. */
internal class NfcBackupMetadata(val version: Long, val sdk: Int, val certificates: List<ByteArray>) {
    init {
        require(version >= 0 && sdk >= 29 && certificates.size in 1..16)
        certificates.forEach { certificate ->
            require(certificate.size in 1..16384)
            CertificateFactory.getInstance("X.509").generateCertificate(certificate.inputStream())
        }
    }
    val signerHashes get() = certificates.map(RestoreProbeArchive::sha256).sorted()

    companion object {
        @Suppress("DEPRECATION")
        fun read(context: Context): NfcBackupMetadata {
            val info = context.packageManager.getPackageInfo(NfcSnapshotArchive.PACKAGE,
                PackageManager.GET_SIGNING_CERTIFICATES)
            return NfcBackupMetadata(info.longVersionCode, Build.VERSION.SDK_INT,
                checkNotNull(info.signingInfo).apkContentsSigners.map { it.toByteArray() })
        }
    }
}

internal class NfcBackupException(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason {
        EMPTY_BACKUP, ENCRYPTED_BACKUP, IDENTITY_MISSING, BACKUP_FAILED, UNSUPPORTED,
        STORAGE_DOMAIN_UNAVAILABLE, STORAGE_DOMAIN_CHANGED
    }
}
