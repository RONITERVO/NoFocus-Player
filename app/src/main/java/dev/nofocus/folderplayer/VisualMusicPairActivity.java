package dev.nofocus.folderplayer;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;

/** Camera-scanned pairing links require a visible tap before saving a trusted PC. */
public final class VisualMusicPairActivity extends Activity {
    private VisualMusicPc client;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            if (android.os.Build.VERSION.SDK_INT < 29) throw new java.io.IOException("Visual music needs Android 10 or newer.");
            String code = getIntent().getDataString();
            JSONObject pair = VisualMusicPc.parsePairing(code == null ? "" : code);
            LinearLayout layout = new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
            ScrollView scroll = new ScrollView(this);scroll.setFillViewport(true);scroll.addView(layout);
            final int padding = Math.round(20 * getResources().getDisplayMetrics().density);
            scroll.setOnApplyWindowInsetsListener((view,insets) -> {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                    view.setPadding(safe.left + padding,safe.top + padding,safe.right + padding,safe.bottom + padding);
                } else view.setPadding(insets.getSystemWindowInsetLeft() + padding,insets.getSystemWindowInsetTop() + padding,insets.getSystemWindowInsetRight() + padding,insets.getSystemWindowInsetBottom() + padding);
                return insets;
            });
            TextView label = new TextView(this);label.setTextSize(20);label.setText("Pair NoFocus PC export\n\n" + pair.optString("name") + "\n" + pair.getString("url") + "\n\nOnly pair a code displayed by your own PC companion.");layout.addView(label);
            Button button = new Button(this);button.setText("Pair PC");layout.addView(button);
            Button cancel = new Button(this);cancel.setText("Cancel");cancel.setOnClickListener(v -> finish());layout.addView(cancel);setContentView(scroll);scroll.requestApplyInsets();
            client = new VisualMusicPc(new VisualMusicLibrary(this));
            button.setOnClickListener(v -> {
                button.setEnabled(false);label.setText("Connecting securely to " + pair.optString("name") + "…");
                new Thread(() -> {
                    try {
                        client.pair(code);
                        runOnUiThread(() -> {if (!isFinishing()) {startActivity(new Intent(this,VisualMusicActivity.class));finish();}});
                    } catch (Exception error) {runOnUiThread(() -> {label.setText(error.getMessage());button.setEnabled(true);});}
                },"visual-pc-pair").start();
            });
        } catch (Exception error) {new AlertDialog.Builder(this).setMessage("Invalid NoFocus PC pairing link.").setPositiveButton("Close",(d,w) -> finish()).setOnCancelListener(d -> finish()).show();}
    }
    @Override protected void onDestroy() {if (client != null) client.cancelIo();super.onDestroy();}
}
