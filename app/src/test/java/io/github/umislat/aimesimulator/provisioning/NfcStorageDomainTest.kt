package io.github.umislat.aimesimulator.provisioning

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class)
class NfcStorageDomainTest {
    @Test fun recognizesOwnerDefaultDirectoriesAndExpandedVolumes() {
        for (root in listOf("/data", "/mnt/expand/volume")) {
            assertEquals(NfcStorageDomain.CE, NfcStorageDomain.fromDataDirectory(File("$root/user/0/com.android.nfc")))
            assertEquals(NfcStorageDomain.DE, NfcStorageDomain.fromDataDirectory(File("$root/user_de/0/com.android.nfc")))
        }
        assertEquals(NfcStorageDomain.CE, NfcStorageDomain.fromDataDirectory(File("/data/data/com.android.nfc")))
        assertEquals("apps/com.android.nfc/f/dynamic_systemcode_nfcid2.xml", NfcStorageDomain.CE.archivePath)
        assertEquals("apps/com.android.nfc/d_f/dynamic_systemcode_nfcid2.xml", NfcStorageDomain.DE.archivePath)
    }

    @Test fun neverGuessesFromOtherUsersPackagesOrUnknownPaths() {
        for (path in listOf("data/user/0/com.android.nfc", "/data/user/10/com.android.nfc",
            "/data/user_de/999/com.android.nfc", "/data/user/0/example.wallet", "/unknown/com.android.nfc")) {
            assertThrows(Exception::class.java) { NfcStorageDomain.fromDataDirectory(File(path)) }
        }
    }

    @Test fun inspectsOnlyPackageMetadataWithoutOpeningDataFiles() {
        val app = RuntimeEnvironment.getApplication()
        for (domain in NfcStorageDomain.entries) {
            var queries = 0
            val target = object : ContextWrapper(app) {
                override fun getDataDir() = File("/data/${if (domain == NfcStorageDomain.DE) "user_de" else "user"}/0/com.android.nfc")
                override fun getFilesDir(): File = error("Must not open or create NFC files")
            }
            val caller = object : ContextWrapper(app) {
                override fun createPackageContext(packageName: String, flags: Int): Context {
                    assertEquals("com.android.nfc", packageName)
                    assertEquals(0, flags)
                    queries++
                    return target
                }
            }
            assertEquals(domain, NfcStorageDomain.inspect(caller))
            assertEquals(1, queries)
        }
    }

    @Test fun unavailablePackageIsReportedInsteadOfDefaultingToCe() {
        val caller = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun createPackageContext(packageName: String, flags: Int): Context =
                throw PackageManager.NameNotFoundException(packageName)
        }
        assertEquals(NfcBackupException.Reason.STORAGE_DOMAIN_UNAVAILABLE,
            assertThrows(NfcBackupException::class.java) { NfcStorageDomain.inspect(caller) }.reason)
    }
}
