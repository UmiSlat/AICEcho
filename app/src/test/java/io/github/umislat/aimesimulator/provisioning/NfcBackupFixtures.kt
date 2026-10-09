package io.github.umislat.aimesimulator.provisioning

internal object NfcBackupFixtures {
    fun metadata(version: Long = 36, sdk: Int = 36, fixture: String = "miui-nfc-signer.hex") =
        NfcBackupMetadata(version, sdk, listOf(certificate(fixture)))

    fun certificate(fixture: String) = checkNotNull(javaClass.getResourceAsStream("/$fixture"))
        .bufferedReader().use { it.readText().trim().chunked(2).map { pair -> pair.toInt(16).toByte() }.toByteArray() }
}
