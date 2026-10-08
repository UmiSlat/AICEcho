package io.github.umislat.aimesimulator.data

import java.security.MessageDigest

internal object CompatibilityIdm {
    private val domain = "AimeSimulator/compatibility-idm/v1\u0000".toByteArray(Charsets.US_ASCII)

    fun generate(originalIdm: String): String {
        val identity = requireNotNull(HexCodec.decode(originalIdm, 8))
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(domain)
        return "02FE" + HexCodec.encode(digest.digest(identity).copyOf(6))
    }
}
