package io.github.umislat.aimesimulator.ui

import io.github.umislat.aimesimulator.R
import io.github.umislat.aimesimulator.provisioning.AospBackupCapabilities
import io.github.umislat.aimesimulator.provisioning.NfcSnapshotWorkflow
import io.github.umislat.aimesimulator.provisioning.ShizukuProbeModel

internal object CapabilityText {
    fun readiness(screen: ShizukuProbeModel.Screen): Int = when {
        screen.connection != ShizukuProbeModel.Connection.READY -> connection(screen.connection)
        screen.pending || screen.snapshotPending || screen.provisionPending -> R.string.shizuku_pending
        screen.savedPlanRequiresResume && !screen.planSaved -> R.string.shizuku_card_resume_required
        screen.canActivate -> R.string.capability_card_ready
        screen.aosp?.canExport == true -> R.string.capability_setup_available
        else -> summary(screen)
    }

    fun connection(state: ShizukuProbeModel.Connection) = when (state) {
        ShizukuProbeModel.Connection.MISSING -> R.string.shizuku_missing
        ShizukuProbeModel.Connection.AVAILABLE -> R.string.shizuku_available
        ShizukuProbeModel.Connection.PERMISSION -> R.string.shizuku_permission
        ShizukuProbeModel.Connection.DENIED -> R.string.shizuku_denied
        ShizukuProbeModel.Connection.OLD_VERSION -> R.string.shizuku_old_version
        ShizukuProbeModel.Connection.ROOT -> R.string.shizuku_root
        ShizukuProbeModel.Connection.CONNECTING -> R.string.shizuku_connecting
        ShizukuProbeModel.Connection.READY -> R.string.shizuku_connected
        ShizukuProbeModel.Connection.ERROR -> R.string.shizuku_error
    }

    fun summary(screen: ShizukuProbeModel.Screen): Int {
        val capabilities = screen.aosp ?: return R.string.shizuku_unchecked
        return when (capabilities.blocker) {
            AospBackupCapabilities.Blocker.SHELL -> R.string.shizuku_root
            AospBackupCapabilities.Blocker.USER -> R.string.capability_user_blocked
            AospBackupCapabilities.Blocker.HARDWARE -> R.string.hce_unsupported
            AospBackupCapabilities.Blocker.COMMAND -> R.string.capability_no_bu
            AospBackupCapabilities.Blocker.SERVICE_INACTIVE -> R.string.capability_service_inactive
            AospBackupCapabilities.Blocker.SERVICE_UNKNOWN -> R.string.capability_service_unknown
            AospBackupCapabilities.Blocker.TARGET -> target(capabilities.target)
            AospBackupCapabilities.Blocker.NONE -> when {
                screen.snapshot.state == NfcSnapshotWorkflow.State.CAPTURED -> R.string.capability_export_verified
                capabilities.keyValueOnly -> R.string.capability_key_value
                else -> R.string.capability_export_candidate
            }
        }
    }

    fun target(state: AospBackupCapabilities.Target) = when (state) {
        AospBackupCapabilities.Target.READY -> R.string.capability_target_ready
        AospBackupCapabilities.Target.MISSING -> R.string.capability_target_missing
        AospBackupCapabilities.Target.NOT_SYSTEM -> R.string.capability_target_not_system
        AospBackupCapabilities.Target.DISABLED -> R.string.capability_target_disabled
        AospBackupCapabilities.Target.STOPPED -> R.string.capability_target_stopped
        AospBackupCapabilities.Target.BACKUP_DISALLOWED -> R.string.capability_target_disallowed
        AospBackupCapabilities.Target.NO_AGENT -> R.string.capability_target_no_agent
        AospBackupCapabilities.Target.NO_METADATA -> R.string.capability_target_no_metadata
    }

    fun failure(detail: String): Int? = when (detail) {
        "EMPTY_BACKUP" -> R.string.capability_empty_backup
        "ENCRYPTED_BACKUP" -> R.string.capability_encrypted_backup
        "IDENTITY_MISSING" -> R.string.capability_identity_missing
        "BACKUP_FAILED" -> R.string.capability_backup_failed
        "UNSUPPORTED" -> R.string.capability_precheck_failed
        "INVALID_ARCHIVE" -> R.string.capability_invalid_archive
        "STORAGE_DOMAIN_UNAVAILABLE" -> R.string.capability_storage_domain_unavailable
        "STORAGE_DOMAIN_CHANGED" -> R.string.capability_storage_domain_changed
        "RUNTIME_BASELINE_USER_SCOPE" -> R.string.capability_runtime_scope_blocked
        "RUNTIME_BASELINE_PLATFORM" -> R.string.capability_runtime_platform_blocked
        "RUNTIME_BASELINE_USER_INACTIVE" -> R.string.capability_runtime_user_inactive
        "RUNTIME_BASELINE_EFFECTIVE_ONLY", "RUNTIME_BASELINE_DYNAMIC_UNKNOWN" -> R.string.capability_runtime_dynamic_blocked
        "RUNTIME_BASELINE_CACHE_EMPTY", "RUNTIME_BASELINE_CACHE_MISMATCH",
        "RUNTIME_BASELINE_RANDOM_PENDING", "RUNTIME_BASELINE_CHANGED" -> R.string.capability_runtime_cache_blocked
        "RUNTIME_BASELINE_TIMEOUT", "RUNTIME_BASELINE_PENDING" -> R.string.capability_runtime_pending
        "RUNTIME_BASELINE_USERS_UNAVAILABLE", "RUNTIME_BASELINE_SERVICES_UNAVAILABLE",
        "RUNTIME_BASELINE_XML_UNSUPPORTED", "RUNTIME_BASELINE_UNAVAILABLE" -> R.string.capability_runtime_unavailable
        "RUNTIME_BASELINE_SHELL" -> R.string.shizuku_root
        "RUNTIME_BASELINE_HARDWARE" -> R.string.hce_unsupported
        else -> null
    }

    fun snapshot(result: NfcSnapshotWorkflow.Result): Int = failure(result.detail) ?: when (result.state) {
        NfcSnapshotWorkflow.State.IDLE -> R.string.capability_snapshot_unchecked
        NfcSnapshotWorkflow.State.PREPARING -> R.string.shizuku_snapshot_preparing
        NfcSnapshotWorkflow.State.EXPORTING -> R.string.shizuku_snapshot_exporting
        NfcSnapshotWorkflow.State.VERIFYING -> R.string.shizuku_snapshot_verifying
        NfcSnapshotWorkflow.State.CAPTURED -> R.string.capability_export_verified
        NfcSnapshotWorkflow.State.REJECTED -> R.string.shizuku_snapshot_rejected
        NfcSnapshotWorkflow.State.FAILED -> R.string.shizuku_snapshot_failed
        NfcSnapshotWorkflow.State.UNKNOWN -> R.string.shizuku_snapshot_unknown
    }
}
