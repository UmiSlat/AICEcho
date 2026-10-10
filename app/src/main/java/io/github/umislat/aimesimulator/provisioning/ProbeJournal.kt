package io.github.umislat.aimesimulator.provisioning

import android.content.SharedPreferences

/** Persist intent before IPC. A new process must not interpret a lost reply as cancellation. */
internal class ProbeJournal(private val preferences: SharedPreferences, private val boot: Int) {
    data class Entry(val token: String, val boot: Int, val pending: Boolean)

    fun read(): Entry {
        val entry = Entry(preferences.getString("token", "").orEmpty(),
            preferences.getInt("boot", -1), preferences.getBoolean("pending", false))
        return if (entry.pending && boot >= 0 && entry.boot >= 0 && boot != entry.boot) {
            entry.copy(pending = false)
        } else entry
    }

    fun begin(token: String): Boolean {
        require(RestoreProbeArchive.validToken(token))
        check(!read().pending)
        return preferences.edit().putString("token", token).putInt("boot", boot)
            .putBoolean("pending", true).commit()
    }

    fun complete(result: RestoreProbeWorkflow.Result): Boolean {
        if (result.token != read().token) return false
        // Read-back alone after a lost service cannot prove that BackupManager has stopped.
        if (result.state !in setOf(RestoreProbeWorkflow.State.VERIFIED, RestoreProbeWorkflow.State.FAILED)) {
            return true
        }
        return preferences.edit().putBoolean("pending", false).commit()
    }

    fun completeSnapshot(result: NfcSnapshotWorkflow.Result): Boolean {
        if (result.token != read().token) return false
        return !result.settled || preferences.edit().putBoolean("pending", false).commit()
    }

    fun completeProvision(result: NfcProvisionWorkflow.Result): Boolean {
        if (result.token != read().token) return false
        if (result.busy || result.state in setOf(NfcProvisionWorkflow.State.UNKNOWN, NfcProvisionWorkflow.State.IDLE)) return true
        return preferences.edit().putBoolean("pending", false).commit()
    }
}
