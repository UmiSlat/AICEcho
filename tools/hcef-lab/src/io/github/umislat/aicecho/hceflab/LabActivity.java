package io.github.umislat.aicecho.hceflab;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Insets;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.NfcFCardEmulation;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class LabActivity extends Activity {
    private NfcFCardEmulation manager;
    private TextView state;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int gap = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(gap, gap, gap, gap);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(gap + bars.left, gap + bars.top, gap + bars.right, gap + bars.bottom);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("HCE-F Lab");
        title.setTextSize(26);
        content.addView(title);
        state = new TextView(this);
        state.setTextSize(17);
        state.setPadding(0, gap, 0, gap);
        content.addView(state);
        Button start = new Button(this);
        start.setText("Start 4000 baseline");
        start.setOnClickListener(view -> selectMode(false));
        content.addView(start);
        Button provisioned = new Button(this);
        provisioned.setText("Use saved 88B4 / 012E lab identity");
        provisioned.setOnClickListener(view -> selectMode(true));
        content.addView(provisioned);
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(view -> stop());
        content.addView(stop);
        setContentView(content);
    }

    @Override public void onResume() {
        super.onResume();
        start();
    }

    private void start() {
        try {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("NFC is off");
            manager = NfcFCardEmulation.getInstance(adapter);
            ComponentName component = new ComponentName(this, LabService.class);
            manager.disableService(this);
            boolean provisioned = getSharedPreferences("lab", MODE_PRIVATE).getBoolean("provisioned", false);
            if (provisioned) {
                // Never register here: a legal setter would overwrite the provisioned identity.
                if (!LabProtocol.PROVISIONED_ID.equalsIgnoreCase(manager.getNfcid2ForService(component))
                        || !"88B4".equalsIgnoreCase(manager.getSystemCodeForService(component))) {
                    throw new IllegalStateException("Saved 88B4 / 012E lab identity missing; no registration attempted");
                }
            } else {
                boolean id = manager.setNfcid2ForService(component, LabProtocol.ID);
                boolean sc = manager.registerSystemCodeForService(component, "4000");
                if (!id || !sc) throw new IllegalStateException("Registration rejected: id=" + id + " sc=" + sc);
                if (!getSharedPreferences("lab", MODE_PRIVATE).edit().putInt("image_sc", 0x4000).commit()) {
                    throw new IllegalStateException("Image reset failed");
                }
            }
            boolean enabled = manager.enableService(this, component);
            if (!enabled) throw new IllegalStateException("Foreground enable rejected");
            String result = "NFCID2 " + manager.getNfcid2ForService(component)
                    + "\nSystem Code " + manager.getSystemCodeForService(component)
                    + "\nForeground enabled: " + enabled;
            state.setText(result);
            Log.i("HcefLab", result);
        } catch (Exception error) {
            stop();
            state.setText(error.toString());
            Log.e("HcefLab", "start failed", error);
        }
    }

    private void selectMode(boolean provisioned) {
        stop();
        if (!getSharedPreferences("lab", MODE_PRIVATE).edit().putBoolean("provisioned", provisioned).commit()) {
            state.setText("Mode save failed; service stopped");
            return;
        }
        start();
    }

    private void stop() {
        if (manager != null) {
            try { Log.i("HcefLab", "disable=" + manager.disableService(this)); }
            catch (Exception error) { Log.e("HcefLab", "disable failed", error); }
        }
        if (state != null) state.setText("Stopped");
    }

    @Override public void onPause() {
        stop();
        super.onPause();
    }
}
