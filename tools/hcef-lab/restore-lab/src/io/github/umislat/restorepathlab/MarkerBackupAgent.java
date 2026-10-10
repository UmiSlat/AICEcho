package io.github.umislat.restorepathlab;

import android.app.backup.BackupAgentHelper;
import android.app.backup.SharedPreferencesBackupHelper;
import android.util.Log;

/** Deliberately inherits onRestoreFile while declaring only a preferences helper. */
public final class MarkerBackupAgent extends BackupAgentHelper {
    @Override public void onCreate() {
        addHelper("settings", new SharedPreferencesBackupHelper(this, "restore_lab"));
    }

    @Override public void onRestoreFinished() {
        super.onRestoreFinished();
        boolean saved = getSharedPreferences("restore_lab", MODE_PRIVATE).edit()
                .putLong("last_restore_ms", System.currentTimeMillis()).commit();
        Log.i("RestorePathLab", "restoreFinished recorded=" + saved);
    }
}
