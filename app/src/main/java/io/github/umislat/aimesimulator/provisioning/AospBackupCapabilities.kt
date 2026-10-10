package io.github.umislat.aimesimulator.provisioning

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.io.File

/** Read-only prerequisites. READY means an export can be attempted, not that restore works. */
internal data class AospBackupCapabilities(
    val shell: Boolean,
    val ownerUser: Int,
    val foregroundUser: Int,
    val bu: Boolean,
    val service: Service,
    val target: Target,
    val keyValueOnly: Boolean,
    val version: Long = -1,
    val agent: String = "",
    val hcef: Boolean = false
) {
    enum class Service { ACTIVE, INACTIVE, UNKNOWN }
    enum class Target { READY, MISSING, NOT_SYSTEM, DISABLED, STOPPED, BACKUP_DISALLOWED, NO_AGENT, NO_METADATA }
    enum class Blocker { NONE, SHELL, USER, HARDWARE, COMMAND, SERVICE_INACTIVE, SERVICE_UNKNOWN, TARGET }
    val blocker: Blocker get() = when {
        !shell -> Blocker.SHELL
        ownerUser != 0 || foregroundUser != 0 -> Blocker.USER
        !hcef -> Blocker.HARDWARE
        !bu -> Blocker.COMMAND
        service == Service.INACTIVE -> Blocker.SERVICE_INACTIVE
        service != Service.ACTIVE -> Blocker.SERVICE_UNKNOWN
        target != Target.READY -> Blocker.TARGET
        else -> Blocker.NONE
    }
    val canExport get() = blocker == Blocker.NONE
    fun canPrepare(completeSnapshot: Boolean) = canExport && (!keyValueOnly || completeSnapshot)

    fun bundle() = Bundle().apply {
        putBoolean("shell", shell); putInt("ownerUser", ownerUser); putInt("foregroundUser", foregroundUser)
        putBoolean("bu", bu); putString("service", service.name); putString("target", target.name)
        putBoolean("keyValueOnly", keyValueOnly); putLong("version", version); putString("agent", agent)
        putBoolean("hcef", hcef)
    }

    companion object {
        fun decode(value: Bundle) = AospBackupCapabilities(value.getBoolean("shell"),
            value.getInt("ownerUser", -1), value.getInt("foregroundUser", -1), value.getBoolean("bu"),
            Service.valueOf(value.getString("service").orEmpty()), Target.valueOf(value.getString("target").orEmpty()),
            value.getBoolean("keyValueOnly"), value.getLong("version", -1), value.getString("agent").orEmpty(), value.getBoolean("hcef"))

        fun serviceState(result: ProbeProcess.Result): Service {
            if (result.uncertain || result.exitCode != 0) return Service.UNKNOWN
            return when (result.output.toString(Charsets.UTF_8).trim()) {
                "Backup Manager currently activated" -> Service.ACTIVE
                "Backup Manager currently deactivated" -> Service.INACTIVE
                else -> Service.UNKNOWN
            }
        }

        @Suppress("DEPRECATION")
        fun inspect(context: Context): AospBackupCapabilities {
            val shell = Process.myUid() == 2000
            fun query(vararg args: String) = runCatching { ProbeProcess.run(args.toList(), seconds = 2) }
                .getOrDefault(ProbeProcess.Result(-1, byteArrayOf(), true))
            val currentUser = if (shell) query("/system/bin/am", "get-current-user").let {
                if (it.uncertain || it.exitCode != 0) -1 else it.output.toString(Charsets.US_ASCII).trim().toIntOrNull() ?: -1
            } else -1
            val service = if (shell) serviceState(query("/system/bin/bmgr", "--user", "0", "activated")) else Service.UNKNOWN
            val packages = context.packageManager
            val info = runCatching { packages.getPackageInfo(NfcSnapshotArchive.PACKAGE, 0) }.getOrNull()
            val app = info?.applicationInfo
            val agent = app?.backupAgentName.orEmpty()
            val allowProperty = if (Build.VERSION.SDK_INT >= 31 && (app?.targetSdkVersion ?: 0) >= 31) {
                runCatching { packages.getProperty("android.backup.ALLOW_ADB_BACKUP", NfcSnapshotArchive.PACKAGE).boolean }.getOrNull()
            } else null
            val enabledSetting = runCatching { packages.getApplicationEnabledSetting(NfcSnapshotArchive.PACKAGE) }.getOrNull()
            val target = when {
                app == null -> Target.MISSING
                app.flags and ApplicationInfo.FLAG_SYSTEM == 0 || app.uid !in 0..9999 -> Target.NOT_SYSTEM
                !app.enabled || enabledSetting in setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) -> Target.DISABLED
                app.flags and ApplicationInfo.FLAG_STOPPED != 0 -> Target.STOPPED
                !(allowProperty ?: (app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)) -> Target.BACKUP_DISALLOWED
                agent.isEmpty() -> Target.NO_AGENT
                runCatching { NfcBackupMetadata.read(context) }.isFailure -> Target.NO_METADATA
                else -> Target.READY
            }
            return AospBackupCapabilities(shell, context.applicationInfo.uid / 100000, currentUser,
                File("/system/bin/bu").canExecute(), service, target,
                agent.isNotEmpty() && app != null && app.flags and ApplicationInfo.FLAG_FULL_BACKUP_ONLY == 0,
                info?.longVersionCode ?: -1, agent,
                packages.hasSystemFeature(PackageManager.FEATURE_NFC) &&
                    packages.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF))
        }
    }
}
