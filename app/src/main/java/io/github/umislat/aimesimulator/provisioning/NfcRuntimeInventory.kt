package io.github.umislat.aimesimulator.provisioning

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Runtime observations are not disk snapshots and cannot authorize a restore. */
internal object NfcRuntimeInventory {
    private val structuredRead = NfcRuntimeReadGate()
    enum class Source { STRUCTURED, DUMPSYS }
    enum class State { UNCHECKED, READ, FAILED }
    enum class Failure { NONE, SHELL, USER, HARDWARE, UNAVAILABLE }

    class Record(
        component: String, val uid: Int, systemCode: String, nfcid2: String, pmm: String,
        val dynamicKnown: Boolean = false, dynamicSystemCode: String? = null, dynamicNfcid2: String? = null
    ) {
        val component = requireNotNull(ComponentName.unflattenFromString(component)).flattenToString()
        val systemCode = systemCode.uppercase()
        val nfcid2 = nfcid2.uppercase()
        val pmm = pmm.uppercase()
        val dynamicSystemCode = dynamicSystemCode?.uppercase()
        val dynamicNfcid2 = dynamicNfcid2?.uppercase()
        init {
            require(uid >= 0 && this.component.length <= 512)
            val parts = this.component.split('/')
            require(parts.size == 2 && parts[0].matches(Regex("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*")) &&
                parts[1].matches(Regex("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")))
            require(code(this.systemCode) && idm(this.nfcid2) && this.pmm.matches(Regex("[0-9A-F]{16}")))
            require(dynamicKnown || (dynamicSystemCode == null && dynamicNfcid2 == null))
            require(this.dynamicSystemCode == null || (code(this.dynamicSystemCode) && this.dynamicSystemCode == this.systemCode))
            require(this.dynamicNfcid2 == null || (idm(this.dynamicNfcid2) && this.dynamicNfcid2 == this.nfcid2))
        }
        override fun toString() = "NfcRuntimeRecord(redacted)"
    }

    data class Summary(
        val state: State = State.UNCHECKED,
        val source: Source? = null,
        val records: Int = 0,
        val users: Int = 0,
        val dynamicFieldsKnown: Boolean = false,
        val previewRecords: Int = -1,
        val fallback: Boolean = false,
        val failure: Failure = Failure.NONE
    ) {
        fun bundle() = Bundle().apply {
            putString("state", state.name); putString("source", source?.name)
            putInt("records", records); putInt("users", users)
            putBoolean("dynamicFieldsKnown", dynamicFieldsKnown); putInt("previewRecords", previewRecords)
            putBoolean("fallback", fallback); putString("failure", failure.name)
        }
        companion object {
            fun decode(value: Bundle) = Summary(State.valueOf(value.getString("state").orEmpty()),
                value.getString("source")?.let(Source::valueOf), value.getInt("records"), value.getInt("users"),
                value.getBoolean("dynamicFieldsKnown"), value.getInt("previewRecords", -1),
                value.getBoolean("fallback"), Failure.valueOf(value.getString("failure").orEmpty()))
        }
    }

    class Observation(val source: Source, records: List<Record>) {
        val records = records.toList()
        init {
            require(this.records.size <= 128)
            require(this.records.map { (it.uid / 100000) to it.component }.toSet().size == this.records.size)
            require(source != Source.DUMPSYS || this.records.none { it.dynamicKnown })
        }

        /** Only the observed user-0 cache is serialized; this is never a complete-file claim. */
        fun previewXml(): ByteArray? {
            if (source != Source.STRUCTURED || records.isEmpty() ||
                records.any { !it.dynamicKnown || it.uid / 100000 != 0 }) return null
            val output = ByteArrayOutputStream()
            Xml.newSerializer().apply {
                setOutput(output, "UTF-8"); startDocument("UTF-8", true); startTag(null, "services")
                for (record in records.sortedWith(compareBy({ it.uid }, { it.component }))) {
                    if (record.dynamicSystemCode == null && record.dynamicNfcid2 == null) continue
                    startTag(null, "service")
                    attribute(null, "component", record.component); attribute(null, "uid", record.uid.toString())
                    record.dynamicSystemCode?.let { attribute(null, "system-code", it) }
                    record.dynamicNfcid2?.let { attribute(null, "nfcid2", it) }
                    endTag(null, "service")
                }
                endTag(null, "services"); endDocument(); flush()
            }
            return output.toByteArray().takeIf { runCatching { NfcSnapshotArchive.validateXml(it) }.isSuccess }
        }

        fun summary() = Summary(State.READ, source, records.size, records.map { it.uid / 100000 }.toSet().size,
            records.isNotEmpty() && records.all { it.dynamicKnown },
            previewXml()?.let { NfcSnapshotArchive.validateXml(it) } ?: -1, source == Source.DUMPSYS)
        override fun toString() = "NfcRuntimeObservation(source=$source, records=${records.size})"
    }

    interface Reader {
        fun structured(): List<Record>
        fun dump(): String
    }

    fun inspect(reader: Reader): Summary {
        val structured = runCatching { Observation(Source.STRUCTURED, reader.structured()).summary() }
        if (structured.isSuccess) return structured.getOrThrow()
        return runCatching {
            Observation(Source.DUMPSYS, NfcFServiceDump.parse(reader.dump()).map {
                Record(it.component, it.uid, it.systemCode, it.nfcid2, it.pmm)
            }).summary()
        }.getOrElse { Summary(State.FAILED, failure = Failure.UNAVAILABLE) }
    }

    fun inspect(context: Context): Summary {
        if (Process.myUid() != 2000) return Summary(State.FAILED, failure = Failure.SHELL)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF))
            return Summary(State.FAILED, failure = Failure.HARDWARE)
        val currentUser = runCatching { ProbeProcess.run(listOf("/system/bin/am", "get-current-user"), seconds = 2) }.getOrNull()
        if (context.applicationInfo.uid / 100000 != 0 || currentUser == null || currentUser.uncertain ||
            currentUser.exitCode != 0 || currentUser.output.toString(Charsets.US_ASCII).trim() != "0")
            return Summary(State.FAILED, failure = Failure.USER)
        return inspect(object : Reader {
            override fun structured() = readStructured()
            override fun dump(): String {
                val value = ProbeProcess.run(listOf("/system/bin/dumpsys", "-t", "4", "nfc"),
                    seconds = 5, binaryOutputLimit = 1024 * 1024)
                check(!value.uncertain && value.exitCode == 0)
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value.output)).toString()
            }
        })
    }

    /** A fresh user-0 Binder observation; completeness is checked separately by NfcRuntimeBaseline. */
    internal fun readStructured(): List<Record> = structuredRead.read { queryStructured() }

    private fun queryStructured(): List<Record> {
        val binderType = Class.forName("android.os.IBinder")
        val binder = checkNotNull(Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "nfc"))
        val adapterType = Class.forName("android.nfc.INfcAdapter")
        val adapter = Class.forName("android.nfc.INfcAdapter\$Stub").getMethod("asInterface", binderType).invoke(null, binder)
        val felica = checkNotNull(adapterType.getMethod("getNfcFCardEmulationInterface").invoke(adapter))
        val values = Class.forName("android.nfc.INfcFCardEmulation").getMethod("getNfcFServices", Int::class.javaPrimitiveType)
            .invoke(felica, 0) as? List<*> ?: error("Service list unavailable")
        require(values.size <= 128)
        return values.map { readServiceInfo(checkNotNull(it)) }.also { records ->
            require(records.all { it.uid / 100000 == 0 })
        }
    }

    internal fun readServiceInfo(value: Any): Record {
        val type = value.javaClass
        fun text(method: String) = type.getMethod(method).invoke(value) as String
        val effective = Record((type.getMethod("getComponent").invoke(value) as ComponentName).flattenToString(),
            type.getMethod("getUid").invoke(value) as Int, text("getSystemCode"), text("getNfcid2"), text("getT3tPmm"))
        return runCatching {
            val sc = type.getDeclaredField("mDynamicSystemCode").apply { isAccessible = true }.get(value)
            val id = type.getDeclaredField("mDynamicNfcid2").apply { isAccessible = true }.get(value)
            require(sc == null || sc is String); require(id == null || id is String)
            Record(effective.component, effective.uid, effective.systemCode, effective.nfcid2, effective.pmm, true, sc, id)
        }.getOrDefault(effective)
    }

    private fun code(value: String) = value == "NULL" || value.matches(Regex("[0-9A-F]{4}"))
    private fun idm(value: String) = value in setOf("NULL", "RANDOM") || value.matches(Regex("[0-9A-F]{16}"))
}

/** A stuck Binder read retains one isolated thread, never the RPC executor or an unbounded queue. */
internal class NfcRuntimeReadGate(private val timeoutMillis: Long = 2000) {
    private var active: FutureTask<List<NfcRuntimeInventory.Record>>? = null
    init { require(timeoutMillis in 1..2000) }

    fun read(action: () -> List<NfcRuntimeInventory.Record>): List<NfcRuntimeInventory.Record> {
        val task = synchronized(this) {
            check(active?.isDone != false) { "Previous runtime read still pending" }
            FutureTask(action).also { next ->
                active = next
                Thread({
                    try { next.run() }
                    finally { synchronized(this) { if (active === next) active = null } }
                }, "nfc-runtime-read").apply { isDaemon = true; start() }
            }
        }
        // Do not cancel: a blocked Binder call may ignore interruption. Keep its slot occupied
        // until it actually ends; subsequent inspections use dumpsys without adding more threads.
        return task.get(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}
