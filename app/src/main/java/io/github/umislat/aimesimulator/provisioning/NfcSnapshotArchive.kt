package io.github.umislat.aimesimulator.provisioning

import android.util.Xml
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

/** Closed export format, not a general archive importer. No tar path is extracted to disk. */
internal object NfcSnapshotArchive {
    const val PACKAGE = "com.android.nfc"
    const val MAX_ARCHIVE = 8 * 1024 * 1024
    const val MAX_XML = 128 * 1024
    private const val PREFIX = "apps/$PACKAGE/"
    private const val IDENTITY = "dynamic_systemcode_nfcid2.xml"
    class Parsed(val xml: ByteArray, val records: Int, val archiveHash: String) {
        val identityHash: String get() = RestoreProbeArchive.sha256(xml)
    }

    fun parse(raw: ByteArray, expected: NfcBackupMetadata): Parsed {
        if (raw.isEmpty()) throw NfcBackupException(NfcBackupException.Reason.EMPTY_BACKUP)
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
        require(line() == "ANDROID BACKUP" && line() == "5")
        val compression = line().also { require(it == "0" || it == "1") }
        if (line() != "none") throw NfcBackupException(NfcBackupException.Reason.ENCRYPTED_BACKUP)
        val payload = if (compression == "0") input.readBytes() else InflaterInputStream(input).use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_ARCHIVE)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        require(payload.size % 512 == 0)
        val files = linkedMapOf<String, ByteArray>()
        var end = 0
        TarArchiveInputStream(ByteArrayInputStream(payload), 512, 512, "UTF-8").use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                val parts = name.split('/')
                require(files.size < 1024 && name.startsWith(PREFIX) && name !in files)
                require(parts.none { it.isEmpty() || it == "." || it == ".." } &&
                    name.none { it == '\\' || it.code < 32 })
                require((entry.isFile || entry.isDirectory) && !entry.isSparse && !entry.isLink && !entry.isSymbolicLink &&
                    entry.isCheckSumOK && entry.extraPaxHeaders.isEmpty())
                require(entry.size in 0..MAX_ARCHIVE.toLong() && tar.bytesRead == end + 512L)
                require(!entry.isDirectory || entry.size == 0L)
                require(name in setOf(PREFIX + "_manifest", PREFIX + "_meta") ||
                    (parts.size >= (if (entry.isDirectory) 3 else 4) &&
                        parts[2] in setOf("r", "f", "db", "sp", "d_r", "d_f", "d_db", "d_sp", "ef")))
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
        val manifestBytes = files[PREFIX + "_manifest"]
            ?: throw NfcBackupException(NfcBackupException.Reason.IDENTITY_MISSING)
        val manifest = utf8(manifestBytes).trimEnd('\n').split('\n')
        require(manifest.size == 7 + expected.certificates.size &&
            manifest.take(4) == listOf("1", PACKAGE, expected.version.toString(), expected.sdk.toString()) &&
            manifest[5] == "0" && manifest[6] == expected.certificates.size.toString())
        val hashes = manifest.drop(7).map { signer ->
            require(signer.matches(Regex("(?:[0-9a-fA-F]{2})+")))
            RestoreProbeArchive.sha256(signer.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }.sorted()
        require(hashes == expected.signerHashes)
        val identities = files.keys.filter { it.substringAfterLast('/') == IDENTITY }
        if (identities.isEmpty()) throw NfcBackupException(NfcBackupException.Reason.IDENTITY_MISSING)
        require(identities == listOf(PREFIX + "f/" + IDENTITY))
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
        val components = hashSetOf<Pair<Int, String>>()
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
                        val uid = attrs.getValue("uid")
                        require(uid.matches(Regex("0|[1-9][0-9]{0,9}")) && uid.toLong() <= Int.MAX_VALUE)
                        require(components.add((uid.toInt() / 100000) to (parts[0] + "/" + cls)))
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
