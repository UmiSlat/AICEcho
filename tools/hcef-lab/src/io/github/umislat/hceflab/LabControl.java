package io.github.umislat.hceflab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Protected by DUMP; changes only the synthetic block image, never controller configuration. */
public final class LabControl extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!"io.github.umislat.hceflab.IMAGE_SC".equals(intent.getAction())) return;
        int sc = intent.getIntExtra("sc", -1);
        if (sc != 0x4000 && sc != 0x88B4) {
            setResultCode(1);
            return;
        }
        boolean saved = context.getSharedPreferences("lab", Context.MODE_PRIVATE)
                .edit().putInt("image_sc", sc).commit();
        Log.i("HcefLab", "image_sc=" + Integer.toHexString(sc) + " saved=" + saved);
        setResultCode(saved ? 0 : 1);
    }
}
