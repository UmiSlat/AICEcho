package io.github.umislat.aicecho.provisioning

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.nfc.cardemulation.HostNfcFService
import android.os.Build
import android.os.IBinder
import android.os.Process
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Reconstructs dynamic overrides, not original file bytes. The supported scope is deliberately
 * one unlocked, foreground user (0), with a nonempty, stable and fully matched service cache.
 * AOSP initializes only enabled profiles but writes all cached users' dynamic maps. An inactive
 * user's absent cache therefore cannot prove that its on-disk registrations are absent.
 */
internal object NfcRuntimeBaseline {
    enum class Reason {
        RUNTIME_BASELINE_SHELL, RUNTIME_BASELINE_PLATFORM, RUNTIME_BASELINE_HARDWARE,
        RUNTIME_BASELINE_USERS_UNAVAILABLE, RUNTIME_BASELINE_USER_SCOPE,
        RUNTIME_BASELINE_USER_INACTIVE, RUNTIME_BASELINE_SERVICES_UNAVAILABLE,
        RUNTIME_BASELINE_CACHE_EMPTY, RUNTIME_BASELINE_CACHE_MISMATCH,
        RUNTIME_BASELINE_EFFECTIVE_ONLY, RUNTIME_BASELINE_DYNAMIC_UNKNOWN,
        RUNTIME_BASELINE_RANDOM_PENDING, RUNTIME_BASELINE_CHANGED,
        RUNTIME_BASELINE_XML_UNSUPPORTED, RUNTIME_BASELINE_UNAVAILABLE,
        RUNTIME_BASELINE_TIMEOUT, RUNTIME_BASELINE_PENDING
    }

    class Failure(val reason: Reason) : IllegalStateException(reason.name)

    class InstalledService(component: String, val uid: Int) {
        val component = checkNotNull(ComponentName.unflattenFromString(component)).flattenToString()
        init {
            require(uid in 0 until 100000 && this.component.length <= 512)
            val parts = this.component.split('/')
            require(parts.size == 2 && parts[0].matches(Regex("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*")) &&
                parts[1].matches(Regex("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")))
        }
        override fun toString() = "InstalledNfcFService(redacted)"
    }

    interface Reader {
        /** Every user, including disabled, partial, dying and pre-created users; no name parsing. */
        fun users(): List<Int>
        fun currentUser(): Int
        fun ownerUnlocked(): Boolean
        /** Complete installed candidates, not an app-visibility-filtered list. */
        fun installedServices(): List<InstalledService>
        fun runtime(): NfcRuntimeInventory.Observation
    }

    private val readGate = ReadGate()

    fun capture(context: Context): ByteArray {
        if (Process.myUid() != 2000) reject(Reason.RUNTIME_BASELINE_SHELL)
        // Older ParceledListSlice implementations silently return partial lists on RemoteException.
        if (Build.VERSION.SDK_INT < 34) reject(Reason.RUNTIME_BASELINE_PLATFORM)
        return checked(Reason.RUNTIME_BASELINE_UNAVAILABLE) {
            readGate.read {
                val pm = context.packageManager
                if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC) ||
                    !pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF))
                    reject(Reason.RUNTIME_BASELINE_HARDWARE)
                if (context.applicationInfo.uid / 100000 != 0) reject(Reason.RUNTIME_BASELINE_USER_SCOPE)
                capture(ShellReader(context))
            }
        }
    }

    /** Pure reader entry point: does not use the phone, public registration APIs or any write path. */
    fun capture(reader: Reader): ByteArray = checked(Reason.RUNTIME_BASELINE_UNAVAILABLE) {
        checkScope(reader)
        val installed = installed(reader)
        val first = observation(reader, installed)
        val installedAgain = installed(reader)
        val second = observation(reader, installedAgain)
        checkScope(reader)
        if (installed != installedAgain || signature(first) != signature(second))
            reject(Reason.RUNTIME_BASELINE_CHANGED)
        second.previewXml() ?: reject(Reason.RUNTIME_BASELINE_XML_UNSUPPORTED)
    }

    private fun checkScope(reader: Reader) {
        val ids = checked(Reason.RUNTIME_BASELINE_USERS_UNAVAILABLE) {
            reader.users().toList().also {
                require(it.isNotEmpty() && it.size <= 128 && it.all { id -> id in 0..21473 })
                require(it.toSet().size == it.size)
            }
        }
        if (ids != listOf(0)) reject(Reason.RUNTIME_BASELINE_USER_SCOPE)
        val active = checked(Reason.RUNTIME_BASELINE_USERS_UNAVAILABLE) {
            reader.currentUser() == 0 && reader.ownerUnlocked()
        }
        if (!active) reject(Reason.RUNTIME_BASELINE_USER_INACTIVE)
    }

    private fun installed(reader: Reader): Map<String, Int> = checked(Reason.RUNTIME_BASELINE_SERVICES_UNAVAILABLE) {
        val values = reader.installedServices().toList()
        require(values.size <= 128)
        if (values.isEmpty()) reject(Reason.RUNTIME_BASELINE_CACHE_EMPTY)
        require(values.map { it.component }.toSet().size == values.size)
        values.associate { it.component to it.uid }
    }

    private fun observation(reader: Reader, installed: Map<String, Int>): NfcRuntimeInventory.Observation {
        val value = checked(Reason.RUNTIME_BASELINE_UNAVAILABLE) { reader.runtime() }
        if (value.source != NfcRuntimeInventory.Source.STRUCTURED) reject(Reason.RUNTIME_BASELINE_EFFECTIVE_ONLY)
        if (value.records.isEmpty()) reject(Reason.RUNTIME_BASELINE_CACHE_EMPTY)
        if (value.records.any { it.uid / 100000 != 0 }) reject(Reason.RUNTIME_BASELINE_USER_SCOPE)
        if (value.records.associate { it.component to it.uid } != installed) reject(Reason.RUNTIME_BASELINE_CACHE_MISMATCH)
        if (value.records.any { !it.dynamicKnown }) reject(Reason.RUNTIME_BASELINE_DYNAMIC_UNKNOWN)
        // An initialized AOSP cache replaces RANDOM with a generated dynamic ID before publishing.
        if (value.records.any { it.nfcid2 == "RANDOM" }) reject(Reason.RUNTIME_BASELINE_RANDOM_PENDING)
        return value
    }

    private fun signature(value: NfcRuntimeInventory.Observation) = value.records.associate { record ->
        record.component to listOf(record.uid, record.systemCode, record.nfcid2, record.pmm,
            record.dynamicKnown, record.dynamicSystemCode, record.dynamicNfcid2)
    }

    private class ShellReader(private val context: Context) : Reader {
        override fun users(): List<Int> = checked(Reason.RUNTIME_BASELINE_USERS_UNAVAILABLE) {
            val (type, service) = binderService("user", "android.os.IUserManager")
            val bool = Boolean::class.javaPrimitiveType
            val values = type.getMethod("getUsers", bool, bool, bool).invoke(service, false, false, false)
            decodeUserIds(values)
        }

        override fun currentUser(): Int = decodeCurrentUser(ProbeProcess.run(
            listOf("/system/bin/am", "get-current-user"), seconds = 2))

        override fun ownerUnlocked(): Boolean {
            val (type, service) = binderService("user", "android.os.IUserManager")
            return type.getMethod("isUserUnlocked", Int::class.javaPrimitiveType).invoke(service, 0) as Boolean
        }

        override fun installedServices(): List<InstalledService> {
            val (type, service) = binderService("package", "android.content.pm.IPackageManager")
            // Use shell's Binder identity and an explicit user, not the app PackageManager query.
            // Including disabled candidates is conservative: they block if absent from the cache.
            val flags = PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE or
                PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
            val slice = checkNotNull(type.getMethod("queryIntentServices", Intent::class.java, String::class.java,
                Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(service, Intent(HostNfcFService.SERVICE_INTERFACE), null, flags.toLong(), 0))
            return decodeServiceSlice(slice).also { services ->
                require(services.all { context.packageManager.checkPermission(Manifest.permission.NFC,
                    checkNotNull(ComponentName.unflattenFromString(it.component)).packageName) == PackageManager.PERMISSION_GRANTED })
            }
        }

        override fun runtime() = NfcRuntimeInventory.Observation(NfcRuntimeInventory.Source.STRUCTURED,
            NfcRuntimeInventory.readStructured())
    }

    /** UserInfo names may contain colons, braces or newlines. Only typed numeric IDs are read. */
    internal fun decodeUserIds(value: Any?): List<Int> = checked(Reason.RUNTIME_BASELINE_USERS_UNAVAILABLE) {
        val users = value as? List<*> ?: error("User list unavailable")
        require(users.isNotEmpty() && users.size <= 128)
        val ids = users.map {
            val info = checkNotNull(it)
            val field = info.javaClass.getField("id")
            require(field.type == Int::class.javaPrimitiveType)
            field.getInt(info).also { id -> require(id in 0..21473) }
        }
        require(ids.toSet().size == ids.size)
        ids
    }

    internal fun decodeCurrentUser(value: ProbeProcess.Result): Int = checked(Reason.RUNTIME_BASELINE_USERS_UNAVAILABLE) {
        require(!value.uncertain && value.exitCode == 0 && value.output.size in 1..32)
        // ProbeProcess merges stderr and rejects over-limit output; accept exactly one ASCII ID.
        require(value.output.all { it in 0..127 })
        val text = value.output.toString(Charsets.US_ASCII)
        require(text.matches(Regex("(?:0|[1-9][0-9]{0,4})(?:\\r?\\n)?")))
        text.trimEnd('\r', '\n').toInt().also { require(it in 0..21473) }
    }

    internal fun decodeInstalledServices(value: Any?): List<InstalledService> =
        checked(Reason.RUNTIME_BASELINE_SERVICES_UNAVAILABLE) {
            val services = value as? List<*> ?: error("Service list unavailable")
            require(services.size <= 128)
            services.map { item ->
                val info = checkNotNull((item as? ResolveInfo)?.serviceInfo)
                val app = checkNotNull(info.applicationInfo)
                require(info.permission == Manifest.permission.BIND_NFC_SERVICE && info.packageName == app.packageName)
                InstalledService(ComponentName(checkNotNull(info.packageName), checkNotNull(info.name)).flattenToString(), app.uid)
            }.also { result -> require(result.map { it.component }.toSet().size == result.size) }
        }

    internal fun decodeServiceSlice(value: Any?): List<InstalledService> =
        checked(Reason.RUNTIME_BASELINE_SERVICES_UNAVAILABLE) {
            val slice = checkNotNull(value)
            // getList is inherited from package-private BaseParceledListSlice on AOSP.
            decodeInstalledServices(slice.javaClass.getMethod("getList").apply { isAccessible = true }.invoke(slice))
        }

    private fun binderService(name: String, interfaceName: String): Pair<Class<*>, Any> {
        val binder = checkNotNull(Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name))
        val type = Class.forName(interfaceName)
        val service = checkNotNull(Class.forName("$interfaceName\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder))
        return type to service
    }

    /** A timed-out Binder request keeps its slot until finished; retries cannot accumulate workers. */
    internal class ReadGate(private val timeoutMillis: Long = 8000) {
        private var active: FutureTask<ByteArray>? = null
        init { require(timeoutMillis in 1..8000) }

        fun read(action: () -> ByteArray): ByteArray {
            val task = synchronized(this) {
                if (active?.isDone == false) reject(Reason.RUNTIME_BASELINE_PENDING)
                FutureTask(action).also { next ->
                    active = next
                    Thread({
                        try { next.run() }
                        finally { synchronized(this) { if (active === next) active = null } }
                    }, "nfc-baseline-read").apply { isDaemon = true; start() }
                }
            }
            return try { task.get(timeoutMillis, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { reject(Reason.RUNTIME_BASELINE_TIMEOUT) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); reject(Reason.RUNTIME_BASELINE_UNAVAILABLE) }
            catch (e: ExecutionException) {
                throw (e.cause as? Failure ?: Failure(Reason.RUNTIME_BASELINE_UNAVAILABLE))
            }
        }
    }

    private fun reject(reason: Reason): Nothing = throw Failure(reason)
    private inline fun <T> checked(reason: Reason, block: () -> T): T = try { block() }
    catch (e: Failure) { throw e }
    catch (_: InterruptedException) { Thread.currentThread().interrupt(); reject(reason) }
    catch (_: Exception) { reject(reason) }
}
