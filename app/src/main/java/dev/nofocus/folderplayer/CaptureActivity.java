package dev.nofocus.folderplayer;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** A separate capture page keeps playback controls compact. */
public final class CaptureActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button start, stop, master, gemini;
    private Spinner size, bitrate, audio, delay;
    private EditText duration;
    private CheckBox floatingControl;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { showState(); handler.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(242, 247, 245));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(16)); body.setBackgroundColor(Color.rgb(242, 247, 245));
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        addButton(body, "Back", v -> finish());
        addText(body, "Capture lyrics video", 24);
        addText(body, "Lossless 48 kHz stereo audio master. Video settings never reduce its audio quality.", 18);
        addText(body, "Prepare the floating control, switch to Suno, then tap ▶. Recording stops after your chosen duration.", 16);
        android.content.SharedPreferences prefs = getSharedPreferences(CaptureService.PREFS, 0);
        floatingControl = new CheckBox(this); floatingControl.setText("Floating Start / Stop control"); floatingControl.setTextSize(17);
        floatingControl.setMinHeight(dp(48)); body.addView(floatingControl);
        floatingControl.setChecked(saved == null ? prefs.getBoolean("overlay", true) : saved.getBoolean("overlay", true));
        addText(body, "Start countdown", 16);
        delay = spinner(body, new String[]{"No countdown", "3 seconds", "5 seconds", "10 seconds", "30 seconds"});
        delay.setSelection(Math.min(4, Math.max(0, saved == null ? prefs.getInt("delay", 1) : saved.getInt("delay", 1))));
        addText(body, "Stop after · minutes:seconds", 16);
        duration = new EditText(this); duration.setTextSize(18); duration.setSingleLine(true);
        duration.setInputType(android.text.InputType.TYPE_CLASS_DATETIME | android.text.InputType.TYPE_DATETIME_VARIATION_TIME);
        duration.setHint("3:30 · blank = 10:00 maximum"); duration.setContentDescription("Stop after, minutes and seconds");
        duration.setText(saved == null ? prefs.getString("duration", "") : saved.getString("duration", ""));
        body.addView(duration, new LinearLayout.LayoutParams(-1, dp(56)));
        addText(body, "The stop timer starts when recording begins. Include a little extra time for pressing Play in Suno.", 15);
        addText(body, "Audio quality", 16);
        audio = spinner(body, new String[]{"32-bit float PCM · maximum", "16-bit FLAC · smaller"});
        addText(body, "Video resolution (short edge)", 16);
        size = spinner(body, new String[]{"720p", "1080p"});
        addText(body, "Video bitrate target · up to 30 fps", 16);
        bitrate = spinner(body, new String[]{"2 Mbps · smaller", "4 Mbps · clearer", "6 Mbps · highest"});
        audio.setSelection(Math.min(1, Math.max(0, saved == null ? prefs.getInt("audio", 0) : saved.getInt("audio", 0))));
        size.setSelection(Math.min(1, Math.max(0, saved == null ? prefs.getInt("size", 0) : saved.getInt("size", 0))));
        bitrate.setSelection(Math.min(2, Math.max(0, saved == null ? prefs.getInt("bitrate", 0) : saved.getInt("bitrate", 0))));
        start = addButton(body, "Start capture", v -> requestCapture());
        floatingControl.setOnCheckedChangeListener((view, checked) -> showState());
        stop = addButton(body, "Stop and save", v -> startService(new Intent(this, CaptureService.class).setAction(CaptureService.STOP)));
        status = addText(body, CaptureService.message, 18);
        addButton(body, "Open latest master in visualizer", v -> startActivity(new Intent(this, VisualMusicActivity.class).putExtra("latest", true)));
        status.setMaxLines(4); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(CaptureService.message).setPositiveButton("Close", null).show());
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        master = addButton(body, "Share master MKV", v -> share("master", "video/x-matroska"));
        gemini = addButton(body, "Share Gemini MP4", v -> share("gemini", "video/mp4"));
        addText(body, "MP4 → Gemini · MKV + JSON → visualizer.\nSaved in Download/NoFocus.", 16);
        addButton(body, "How to use", v -> new AlertDialog.Builder(this).setTitle("Capture and export")
                .setMessage("Set your song duration plus a few seconds for pressing Play. Choose a countdown and prepare the floating control. Android asks for display-over-other-apps permission once and screen-sharing consent for each session. Switch to Suno, tap ▶, wait for the recording timer, then play. Drag the timer label to move the control; ■ stops early and × cancels before recording. A ready control expires after 5 minutes.\n\nTurn floating control off to start the countdown from this page instead. Preparation and countdown do not use up the stop duration. The service stops and saves automatically even if this page is closed.\n\nChoose only Suno in Android's sharing prompt to avoid other windows in your video. With entire-screen capture, the floating control can appear in the recording. Keep the same orientation and pause other apps' media.\n\nTap Open latest master in visualizer, open Lyrics, copy the Gemini prompt and share the matching MP4. Paste or import Gemini JSON, Save & preview, then Export video with Preserve original audio. You can also use the MKV + JSON in Visual-Music-Lyrics on your PC.\n\nAndroid 10+ · 10-minute recording limit. Internal audio only; no microphone, audio focus, gain boost or normalization. Android's mixer and Suno's capture policy still apply. Try a short recording first.")
                .setPositiveButton("Close", null).show());
        showState();
    }

    private void requestCapture() {
        if (PhoneAudioService.running) {
            Toast.makeText(this, "Stop phone audio sharing before capturing a lyrics video.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT < 29 || CaptureService.running) return;
        try {
            new CaptureTiming(CaptureTiming.START_DELAYS[delay.getSelectedItemPosition()], CaptureTiming.parseDuration(duration.getText().toString()));
            duration.setError(null);
        } catch (IllegalArgumentException error) { duration.setError(error.getMessage()); duration.requestFocus(); return; }
        saveOptions();
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(duration.getWindowToken(), 0);
        if (floatingControl.isChecked() && !Settings.canDrawOverlays(this)) {
            CaptureService.message = "Allow NoFocus to display over other apps, then return here. Or turn floating control off.";
            try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), 52); }
            catch (ActivityNotFoundException error) { CaptureService.message = "Overlay settings unavailable. Turn floating control off to use the timers."; }
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO}, 50);
            return;
        }
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 51);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 50) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) requestCapture();
            else CaptureService.message = "Android requires audio permission for internal playback capture. The microphone is never used.";
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 52) {
            if (Settings.canDrawOverlays(this)) requestCapture();
            else CaptureService.message = "Floating control needs display-over-other-apps permission. You can turn it off and use the timers alone.";
            return;
        }
        if (Build.VERSION.SDK_INT < 29 || request != 51 || result != RESULT_OK || data == null || CaptureService.running) return;
        android.graphics.Rect bounds;
        if (Build.VERSION.SDK_INT >= 30) bounds = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        else {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            bounds = new android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
        }
        int shortEdge = size.getSelectedItemPosition() == 0 ? 720 : 1080;
        double scale = Math.min(1.0, (double)shortEdge / Math.min(bounds.width(), bounds.height()));
        int width = (int)(bounds.width() * scale) / 2 * 2, height = (int)(bounds.height() * scale) / 2 * 2;
        saveOptions();
        Intent intent = new Intent(this, CaptureService.class).putExtra("consent", data).putExtra("width", width).putExtra("height", height)
                .putExtra("density", getResources().getConfiguration().densityDpi).putExtra("bitrate", (bitrate.getSelectedItemPosition() + 1) * 2000000)
                .putExtra("floating", audio.getSelectedItemPosition() == 0)
                .putExtra("overlay", floatingControl.isChecked()).putExtra("delay_seconds", CaptureTiming.START_DELAYS[delay.getSelectedItemPosition()])
                .putExtra("duration_millis", CaptureTiming.parseDuration(duration.getText().toString()));
        try { startForegroundService(intent); }
        catch (Exception error) { CaptureService.message = "Could not start capture: " + error.getMessage(); }
    }

    private void showState() {
        boolean available = Build.VERSION.SDK_INT >= 29, busy = CaptureService.running;
        start.setText(floatingControl.isChecked() ? "Prepare floating control" : "Start capture");
        start.setEnabled(available && !busy);
        stop.setText(CaptureService.recording ? "Stop and save" : "Cancel capture");
        stop.setEnabled(busy && CaptureService.phase != CaptureService.Phase.SAVING && CaptureService.phase != CaptureService.Phase.IDLE);
        size.setEnabled(!busy); bitrate.setEnabled(!busy); audio.setEnabled(!busy);
        delay.setEnabled(!busy); duration.setEnabled(!busy); floatingControl.setEnabled(!busy);
        if (!available) status.setText("Internal playback capture needs Android 10 or newer.");
        else status.setText(CaptureService.message);
        android.content.SharedPreferences prefs = getSharedPreferences(CaptureService.PREFS, 0);
        master.setEnabled(!busy && prefs.contains("master")); gemini.setEnabled(!busy && prefs.contains("gemini"));
        for (Button button : new Button[]{start, stop, master, gemini}) button.setAlpha(button.isEnabled() ? 1f : .4f);
    }

    private void share(String key, String mime) {
        String value = getSharedPreferences(CaptureService.PREFS, 0).getString(key, "");
        if (value.isEmpty()) return;
        Uri uri = Uri.parse(value);
        Intent intent = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Capture", uri));
        try { startActivity(Intent.createChooser(intent, "Share capture")); }
        catch (ActivityNotFoundException error) { status.setText("Open Download/NoFocus in your files app to copy the recording."); }
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private void saveOptions() {
        getSharedPreferences(CaptureService.PREFS, 0).edit().putInt("audio", audio.getSelectedItemPosition()).putInt("size", size.getSelectedItemPosition())
                .putInt("bitrate", bitrate.getSelectedItemPosition()).putBoolean("overlay", floatingControl.isChecked())
                .putInt("delay", delay.getSelectedItemPosition()).putString("duration", duration.getText().toString()).apply();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("audio", audio.getSelectedItemPosition()); out.putInt("size", size.getSelectedItemPosition()); out.putInt("bitrate", bitrate.getSelectedItemPosition());
        out.putBoolean("overlay", floatingControl.isChecked()); out.putInt("delay", delay.getSelectedItemPosition()); out.putString("duration", duration.getText().toString());
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView addText(LinearLayout body, String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(Color.rgb(24, 32, 43));
        view.setPadding(0, dp(8), 0, dp(8)); body.addView(view); return view;
    }
    private Button addButton(LinearLayout body, String text, View.OnClickListener action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextSize(18);
        button.setMinHeight(dp(48)); button.setOnClickListener(action); body.addView(button, new LinearLayout.LayoutParams(-1, -2)); return button;
    }
    private Spinner spinner(LinearLayout body, String[] options) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options);
        spinner.setAdapter(adapter); body.addView(spinner, new LinearLayout.LayoutParams(-1, dp(56))); return spinner;
    }
}
