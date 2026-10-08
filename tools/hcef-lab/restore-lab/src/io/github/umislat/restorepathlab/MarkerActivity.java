package io.github.umislat.restorepathlab;

import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class MarkerActivity extends Activity {
    private static final String FILE = "restore_probe.txt";
    private TextView state;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("Restore Path Lab");
        title.setTextSize(24);
        content.addView(title);
        state = new TextView(this);
        state.setTextSize(16);
        state.setTextIsSelectable(true);
        state.setPadding(0, padding, 0, padding);
        content.addView(state);
        Button refresh = new Button(this);
        refresh.setText("Refresh markers");
        refresh.setOnClickListener(view -> refresh());
        content.addView(refresh);
        Button baseline = new Button(this);
        baseline.setText("Write baseline markers");
        baseline.setOnClickListener(view -> baseline());
        content.addView(baseline);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override public void onResume() {
        super.onResume();
        refresh();
    }

    private String read(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.isFile()) return "(missing)";
        byte[] data = new byte[257];
        try (FileInputStream input = new FileInputStream(file)) {
            int total = 0;
            while (total < data.length) {
                int count = input.read(data, total, data.length - total);
                if (count < 0) break;
                total += count;
            }
            if (total > 256) return "(oversized marker)";
            return new String(data, 0, total, StandardCharsets.UTF_8).trim();
        } catch (Exception error) {
            return "Read failed: " + error.getClass().getSimpleName();
        }
    }

    private void refresh() {
        if (state == null) return;
        state.setText("Package: " + getPackageName()
                + "\nCE: " + read(this)
                + "\nDE: " + read(createDeviceProtectedStorageContext())
                + "\nRestore callback: " + getSharedPreferences("restore_lab", MODE_PRIVATE)
                        .getLong("last_restore_ms", 0));
    }

    private void baseline() {
        try {
            write(this, "baseline-ce\n");
            write(createDeviceProtectedStorageContext(), "baseline-de\n");
            refresh();
        } catch (Exception error) {
            state.setText("Baseline write failed: " + error.getClass().getSimpleName());
        }
    }

    private static void write(Context context, String value) throws Exception {
        try (FileOutputStream output = context.openFileOutput(FILE, MODE_PRIVATE)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
