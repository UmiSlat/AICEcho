package io.github.umislat.aicecho.ui

import android.app.Activity
import android.content.Intent
import androidx.core.net.toUri
import io.github.umislat.aicecho.R
import io.github.umislat.aicecho.provisioning.ShizukuProbeModel

internal object ShizukuConnectionUi {
    fun label(state: ShizukuProbeModel.Connection): Int = when (state) {
        ShizukuProbeModel.Connection.AVAILABLE -> R.string.shizuku_authorize
        ShizukuProbeModel.Connection.PERMISSION -> R.string.shizuku_permission
        ShizukuProbeModel.Connection.CONNECTING -> R.string.shizuku_connecting
        ShizukuProbeModel.Connection.ERROR -> R.string.shizuku_retry_connection
        else -> R.string.shizuku_open
    }

    fun act(activity: Activity, model: ShizukuProbeModel) {
        when (model.screen.value?.connection) {
            ShizukuProbeModel.Connection.AVAILABLE, ShizukuProbeModel.Connection.ERROR -> model.connect()
            else -> {
                val launch = activity.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?: Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/zh-hans/guide/setup/".toUri())
                runCatching { activity.startActivity(launch) }
            }
        }
    }
}
