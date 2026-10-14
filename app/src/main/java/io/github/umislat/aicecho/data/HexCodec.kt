package io.github.umislat.aicecho.data

internal object HexCodec {
    fun normalize(value: String, expectedBytes: Int): String? {
        val compact = value.filterNot(Char::isWhitespace).uppercase()
        if (compact.length != expectedBytes * 2 || compact.any { it.digitToIntOrNull(16) == null }) {
            return null
        }
        return compact
    }

    fun decode(value: String, expectedBytes: Int? = null): ByteArray? {
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.length % 2 != 0 || compact.any { it.digitToIntOrNull(16) == null }) return null
        if (expectedBytes != null && compact.length != expectedBytes * 2) return null
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun encode(value: ByteArray): String = value.joinToString("") {
        "%02X".format(it.toInt() and 0xFF)
    }
}
