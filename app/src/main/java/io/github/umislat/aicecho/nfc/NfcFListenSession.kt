package io.github.umislat.aicecho.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.os.Build
import android.util.Log

internal class NfcFListenSession(private val activity: Activity) {
    private var adapter: NfcAdapter? = null
    private var requested = false
    private var unavailable = false

    fun restrict(refresh: Boolean = false) {
        if (Build.VERSION.SDK_INT < 35 || unavailable || requested && !refresh) return
        val current = adapter ?: runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull()
            ?: return
        adapter = current
        requested = true
        try {
            // Activity-scoped flags only: never change the controller's persistent defaults.
            current.setDiscoveryTechnology(activity, NfcAdapter.FLAG_READER_KEEP,
                NfcAdapter.FLAG_LISTEN_NFC_PASSIVE_F)
        } catch (error: RuntimeException) {
            unavailable = true
            Log.w(TAG, "NFC-F listen restriction unavailable; continuing HCE-F activation", error)
            release()
        } catch (error: NoSuchMethodError) {
            unavailable = true
            requested = false
            adapter = null
            Log.w(TAG, "NFC-F listen API is not available on this system", error)
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT < 35 || !requested) return
        try {
            adapter?.resetDiscoveryTechnology(activity)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not reset foreground discovery technologies", error)
        } catch (error: NoSuchMethodError) {
            Log.w(TAG, "Discovery reset API is not available on this system", error)
        } finally {
            requested = false
            adapter = null
        }
    }

    private companion object {
        const val TAG = "AICEchoNfcListen"
    }
}
