package io.github.umislat.aimesimulator.data

import java.util.UUID

internal data class CardProfile(
    val profileId: String,
    val label: String,
    val idm: String,
    val spad0: String? = null,
    val idBlock: String? = null,
    val accessCode: String? = null
) {
    fun routedIdm(routeMode: IdmRouteMode): String = when (routeMode) {
        IdmRouteMode.ORIGINAL -> idm
        IdmRouteMode.GENERATED_COMPATIBILITY -> CompatibilityIdm.generate(idm)
        IdmRouteMode.PREFIX_COMPATIBILITY -> COMPATIBILITY_PREFIX + idm.drop(4)
    }

    fun formattedAccessCode(): String? = accessCode?.chunked(4)?.joinToString(" ")

    companion object {
        const val DEFAULT_IDM = "02FE000000000000"
        const val COMPATIBILITY_PREFIX = "02FE"

        fun create(
            label: String,
            idm: String,
            spad0: String? = null,
            idBlock: String? = null,
            accessCode: String? = null,
            profileId: String = UUID.randomUUID().toString()
        ): CardProfile? {
            val cleanIdm = HexCodec.normalize(idm, 8) ?: return null
            val cleanSpad0 = spad0?.let { HexCodec.normalize(it, 16) ?: return null }
            val cleanIdBlock = idBlock?.let { HexCodec.normalize(it, 16) ?: return null }
            val cleanAccessCode = accessCode?.takeIf(String::isNotBlank)?.let { value ->
                value.filterNot { it.isWhitespace() || it == '-' }
                    .takeIf { it.length == 20 && it.all(Char::isDigit) }
                    ?: return null
            }
            val cleanLabel = label.trim().ifEmpty { "IDm ${cleanIdm.takeLast(4)}" }
            return CardProfile(
                profileId,
                cleanLabel,
                cleanIdm,
                cleanSpad0,
                cleanIdBlock,
                cleanAccessCode
            )
        }

        fun fallback(): CardProfile = CardProfile(
            profileId = "built-in-fallback",
            label = "IDm 0000",
            idm = DEFAULT_IDM
        )
    }
}
