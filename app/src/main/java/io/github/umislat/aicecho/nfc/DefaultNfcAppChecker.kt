package io.github.umislat.aicecho.nfc

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

internal object DefaultNfcAppChecker {
    enum class Result {
        ALREADY_DEFAULT,
        NOT_DEFAULT,
        REQUESTED,
        NFC_NOT_READY,
        UNSUPPORTED,
        FAILED
    }

    fun check(activity: Activity): Result {
        if (!activity.packageManager.hasSystemFeature(
                PackageManager.FEATURE_NFC_HOST_CARD_EMULATION
            )) {
            return Result.UNSUPPORTED
        }
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull()
            ?: return Result.NFC_NOT_READY
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        if (!enabled) return Result.NFC_NOT_READY

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                walletRoleManager(activity)?.let { roleManager ->
                    return if (roleManager.isRoleHeld(RoleManager.ROLE_WALLET)) {
                        Result.ALREADY_DEFAULT
                    } else {
                        Result.NOT_DEFAULT
                    }
                }
            }
            val manager = CardEmulation.getInstance(adapter)
            val service = ComponentName(activity, DefaultNfcService::class.java)
            if (manager.isDefaultServiceForCategory(service, CardEmulation.CATEGORY_PAYMENT)) {
                Result.ALREADY_DEFAULT
            } else {
                Result.NOT_DEFAULT
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to check the default NFC application", error)
            Result.FAILED
        }
    }

    fun request(activity: Activity): Result {
        val current = check(activity)
        if (current != Result.NOT_DEFAULT) return current
        return if (openWalletSettings(activity)) Result.REQUESTED else Result.FAILED
    }

    fun openWalletSettings(activity: Activity): Boolean {
        val actions = listOf(
            Settings.ACTION_NFC_PAYMENT_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            Settings.ACTION_NFC_SETTINGS
        )
        var lastError: RuntimeException? = null
        actions.forEach { action ->
            try {
                activity.startActivity(Intent(action))
                return true
            } catch (error: RuntimeException) {
                lastError = error
            }
        }
        Log.w(TAG, "Unable to open wallet settings", lastError)
        return false
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun walletRoleManager(activity: Activity): RoleManager? =
        activity.getSystemService(RoleManager::class.java)
            ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_WALLET) }

    private const val TAG = "AICEchoDefaultNfc"
}
