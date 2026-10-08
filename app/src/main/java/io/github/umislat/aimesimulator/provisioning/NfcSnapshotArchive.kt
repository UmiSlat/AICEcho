package io.github.umislat.aimesimulator.provisioning

import android.util.Xml
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Closed export format, not a general archive importer. No tar path is extracted to disk. */
internal object NfcSnapshotArchive {
    const val PACKAGE = "com.android.nfc"
    const val SIGNER = "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025"
    const val MAX_ARCHIVE = 8 * 1024 * 1024
    const val MAX_XML = 128 * 1024
    private const val PREFIX = "apps/$PACKAGE/"
    private const val IDENTITY = "dynamic_systemcode_nfcid2.xml"
    class Parsed(val xml: ByteArray, val records: Int, val archiveHash: String) {
        val identityHash: String get() = RestoreProbeArchive.sha256(xml)
    }

    fun parse(raw: ByteArray): Parsed {
        require(raw.size <= MAX_ARCHIVE)
        val input = ByteArrayInputStream(raw)
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val byte = input.read()
                require(byte >= 0 && byte != 13 && bytes.size < 512)
                if (byte == 10) break
                bytes.add(byte.toByte())
            }
            return utf8(bytes.toByteArray())
        }
        require(line() == "MIUI BACKUP" && line() == "2")
        val label = line()
        require(label.startsWith("$PACKAGE ") && label.none { it.code < 32 })
        require(List(6) { line() } == listOf("0", "0", "ANDROID BACKUP", "5", "0", "none"))
        val payload = input.readBytes()
        require(payload.size % 512 == 0)
        val files = linkedMapOf<String, ByteArray>()
        var end = 0
        TarArchiveInputStream(ByteArrayInputStream(payload), 512, 512, "UTF-8").use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name
                val parts = name.split('/')
                require(files.size < 64 && name.startsWith(PREFIX) && name !in files)
                require(parts.none { it.isEmpty() || it == "." || it == ".." } &&
                    name.none { it == '\\' || it.code < 32 })
                require(entry.isFile && !entry.isSparse && !entry.isLink && !entry.isSymbolicLink &&
                    entry.isCheckSumOK && entry.extraPaxHeaders.isEmpty())
                require(entry.size in 0..MAX_XML.toLong() && tar.bytesRead == end + 512L)
                require(name == PREFIX + "_manifest" ||
                    (parts.size >= 4 && parts[2] in setOf("f", "d_f", "sp", "d_sp")))
                val bytes = ByteArray(entry.size.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val count = tar.read(bytes, offset, bytes.size - offset)
                    require(count > 0)
                    offset += count
                }
                require(tar.read() == -1)
                files[name] = bytes
                end += 512 + ((bytes.size + 511) / 512) * 512
            }
        }
        require(payload.size - end >= 1024 && (end until payload.size).all { payload[it] == 0.toByte() })
        val manifest = utf8(checkNotNull(files[PREFIX + "_manifest"])).trimEnd('\n').split('\n')
        require(manifest.size == 8 && manifest.take(7) == listOf("1", PACKAGE, "36", "36", "", "0", "1"))
        require(manifest[7].matches(Regex("(?:[0-9a-fA-F]{2})+")))
        val signer = manifest[7].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(RestoreProbeArchive.sha256(signer) == SIGNER)
        require(files.keys.filter { it.substringAfterLast('/') == IDENTITY } == listOf(PREFIX + "f/" + IDENTITY))
        val xml = checkNotNull(files[PREFIX + "f/" + IDENTITY])
        return Parsed(xml, validateXml(xml), RestoreProbeArchive.sha256(raw))
    }

    fun validateXml(raw: ByteArray): Int {
        require(raw.isNotEmpty() && raw.size <= MAX_XML)
        val text = utf8(raw).removePrefix("\uFEFF")
        require("<!" !in text && '\u0000' !in text)
        val withoutDeclaration = text.replaceFirst(Regex("^\\s*<\\?xml\\s+[^?]*\\?>"), "")
        require("<?" !in withoutDeclaration)
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(text.reader())
        }
        val components = hashSetOf<String>()
        var root = false
        var closed = false
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> { require(root && closed); return components.size }
                XmlPullParser.START_TAG -> {
                    require(parser.namespace.isNullOrEmpty() && parser.getNamespaceCount(parser.depth) == 0)
                    if (parser.depth == 1) {
                        require(!root && parser.name == "services" && parser.attributeCount == 0)
                        root = true
                    } else {
                        require(root && parser.depth == 2 && parser.name == "service" && components.size < 128)
                        val attrs = (0 until parser.attributeCount).associate { index ->
                            require(parser.getAttributeNamespace(index).isNullOrEmpty())
                            parser.getAttributeName(index) to parser.getAttributeValue(index)
                        }
                        require(attrs.keys.all { it in setOf("component", "uid", "system-code", "nfcid2", "description") })
                        val parts = attrs.getValue("component").split('/')
                        require(parts.size == 2 && parts[0].matches(Regex("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+")))
                        val cls = if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1]
                        require(cls.matches(Regex("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+")))
                        require(components.add(parts[0] + "/" + cls))
                        val uid = attrs.getValue("uid")
                        require(uid.matches(Regex("0|[1-9][0-9]{0,9}")) && uid.toLong() <= Int.MAX_VALUE)
                        require("system-code" in attrs || "nfcid2" in attrs)
                        for ((key, size) in listOf("system-code" to 4, "nfcid2" to 16)) {
                            attrs[key]?.let { value -> require(value == "NULL" || (key == "nfcid2" && value == "RANDOM") ||
                                value.matches(Regex("[0-9a-fA-F]{$size}"))) }
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.IGNORABLE_WHITESPACE -> require(parser.text.isBlank())
                XmlPullParser.END_TAG -> if (parser.depth == 1) {
                    require(root && !closed && parser.name == "services")
                    closed = true
                }
                else -> error("Unsupported XML token")
            }
        }
    }

    private fun utf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
