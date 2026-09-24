package dev.nofocus.folderplayer;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.Locale;

/** A small, separate screen keeps the everyday music controls uncluttered. */
public final class SongDownloadActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText link;
    private Button format;
    private Button action;
    private Button paste;
    private TextView status;
    private TextView title;
    private TextView heading;
    private LinearLayout body;
    private LinearLayout formatChoices;
    private boolean choosingFormat;
    private LinearLayout results;
    private int selectedFormat;
    private String resultFile = "";
    private SongDownloadService.State displayed;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { showState(); handler.postDelayed(this, 300); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) selectedFormat = state.getInt("format");
        else selectedFormat = getSharedPreferences(SongDownloadService.PREFS, 0).getInt("format", 0);
        selectedFormat = Math.max(0, Math.min(2, selectedFormat));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        boolean landscape = getResources().getConfiguration().orientation == 2;
        LinearLayout root = column();
        root.setBackgroundColor(Color.rgb(242, 247, 245));
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(dp(12) + safe.left, dp(8) + safe.top, dp(12) + safe.right, dp(8) + safe.bottom);
            }
            else v.setPadding(dp(12) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(12) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        LinearLayout header = new LinearLayout(this);
        header.setBaselineAligned(false);
        Button back = button("Back", v -> onBackPressed());
        header.addView(back, new LinearLayout.LayoutParams(dp(88), dp(48)));
        heading = label("Download", 21);
        heading.setPadding(dp(8), 0, 0, 0);
        heading.setSingleLine(true); heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        body = new LinearLayout(this);
        body.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout form = column();
        LinearLayout details = column();
        body.addView(form, landscape ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, -2));
        body.addView(details, landscape ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, 0, 1));
        if (landscape) details.setPadding(dp(12), 0, 0, 0);
        LinearLayout input = new LinearLayout(this);
        input.setBaselineAligned(false);
        link = new EditText(this);
        link.setHint("YouTube link"); link.setTextSize(18); link.setSingleLine(true);
        link.setPadding(dp(4), 0, dp(4), 0);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        link.setContentDescription("YouTube link");
        input.addView(link, new LinearLayout.LayoutParams(0, -1, 1));
        paste = button("Paste", v -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0)
                link.setText(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this));
        });
        input.addView(paste, new LinearLayout.LayoutParams(dp(100), -1));
        form.addView(input, controlRow(56));
        format = button("", v -> chooseFormat());
        updateFormat(); form.addView(format, controlRow(48));
        action = button("Download", v -> startOrCancel());
        action.setTextColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable primary = new android.graphics.drawable.GradientDrawable();
        primary.setColor(Color.rgb(0, 100, 91)); primary.setCornerRadius(dp(16));
        action.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3382b4a5), primary, null));
        form.addView(action, controlRow(48));
        status = label("", 18);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setMaxLines(landscape ? 2 : 3); status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(status.getText()).setPositiveButton("Close", null).show());
        details.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
        title = label("", 16);
        title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(resultFile).setPositiveButton("Close", null).show());
        details.addView(title, new LinearLayout.LayoutParams(-1, -2));
        results = new LinearLayout(this);
        results.setBaselineAligned(false);
        Button saveButton = button("Save", v -> save());
        saveButton.setContentDescription("Save file");
        results.addView(saveButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        results.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        results.addView(button("Share", v -> share()), new LinearLayout.LayoutParams(0, dp(48), 1));
        details.addView(results, new LinearLayout.LayoutParams(-1, dp(48)));
        if (state != null) link.setText(state.getString("link", ""));
        root.setFocusableInTouchMode(true); root.requestFocus();
        showState();
        if (state != null && state.getBoolean("choosing_format")) chooseFormat();
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("link", link.getText().toString()); out.putInt("format", selectedFormat);
        out.putBoolean("choosing_format", choosingFormat);
    }

    private void chooseFormat() {
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(link.getWindowToken(), 0);
        choosingFormat = true;
        heading.setText("Format");
        body.setVisibility(View.GONE);
        formatChoices = column();
        for (int index = 0; index < SongDownloadSpec.FORMATS.length; index++) {
            final int choice = index;
            Button button = button(SongDownloadSpec.LABELS[index], v -> {
                selectedFormat = choice; updateFormat();
                getSharedPreferences(SongDownloadService.PREFS, 0).edit().putInt("format", choice).apply();
                onBackPressed();
            });
            button.setSelected(index == selectedFormat);
            formatChoices.addView(button, controlRow(48));
        }
        ((LinearLayout)body.getParent()).addView(formatChoices, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    @Override public void onBackPressed() {
        if (!choosingFormat) { finish(); return; }
        choosingFormat = false;
        ((LinearLayout)body.getParent()).removeView(formatChoices);
        heading.setText("Download"); body.setVisibility(View.VISIBLE);
    }

    private void updateFormat() {
        format.setText(SongDownloadSpec.FORMATS[selectedFormat].toUpperCase(Locale.ROOT) + " ▾");
        format.setContentDescription("Audio format: " + SongDownloadSpec.LABELS[selectedFormat]);
    }

    private void showState() {
        SongDownloadService.State state = SongDownloadService.current(this);
        if (displayed != null && displayed.running == state.running && displayed.message.equals(state.message) && displayed.file.equals(state.file)) return;
        displayed = state;
        resultFile = state.file;
        boolean available = Build.VERSION.SDK_INT >= 24;
        link.setEnabled(!state.running); paste.setEnabled(!state.running); format.setEnabled(!state.running);
        action.setEnabled(available); action.setText(state.running ? "Cancel" : "Download");
        status.setText(available ? state.message : "Downloads need Android 7 or newer.");
        title.setText(resultFile); title.setVisibility(resultFile.isEmpty() ? View.GONE : View.VISIBLE);
        results.setVisibility(resultFile.isEmpty() || state.running ? View.GONE : View.VISIBLE);
    }

    private void startOrCancel() {
        if (SongDownloadService.current(this).running) {
            startService(new Intent(this, SongDownloadService.class).setAction(SongDownloadService.CANCEL)); return;
        }
        try {
            String url = SongDownloadSpec.videoUrl(link.getText().toString());
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(link.getWindowToken(), 0);
            Intent request = new Intent(this, SongDownloadService.class).putExtra("url", url).putExtra("format", SongDownloadSpec.FORMATS[selectedFormat]);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(request); else startService(request);
            action.setEnabled(false);
            displayed = null;
        } catch (Exception error) { status.setText(error.getMessage()); }
    }

    private void save() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(resultMime()).putExtra(Intent.EXTRA_TITLE, resultFile);
        try { startActivityForResult(intent, 41); } catch (ActivityNotFoundException error) { status.setText("No file picker found. Try Share."); }
    }

    private String resultMime() { return SongDownloadSpec.mime(resultFile.substring(resultFile.lastIndexOf('.') + 1)); }

    private void share() {
        Uri uri = SongFileProvider.uri(this, resultFile);
        Intent intent = new Intent(Intent.ACTION_SEND).setType(resultMime()).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Song", uri));
        try { startActivity(Intent.createChooser(intent, "Share song")); } catch (ActivityNotFoundException error) { status.setText("No sharing app found. Use Save file."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 41 || result != RESULT_OK || data == null || data.getData() == null) return;
        Intent save = new Intent(this, SongDownloadService.class).setAction(SongDownloadService.SAVE).setData(data.getData()).putExtra("file", resultFile);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(save); else startService(save);
    }

    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout.LayoutParams controlRow(int height) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(height)); layout.topMargin = dp(4); return layout;
    }
    private TextView label(String value, float size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(24, 32, 43)); return view; }
    private Button button(String value, View.OnClickListener click) {
        Button button = new Button(this); button.setText(value); button.setTextSize(18); button.setAllCaps(false);
        button.setTextColor(Color.rgb(0, 100, 91)); button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setBackgroundTintList(null);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(Color.rgb(226, 239, 233)); shape.setCornerRadius(dp(16));
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3382b4a5), shape, null));
        button.setPadding(dp(4), 0, dp(4), 0); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setOnClickListener(click); return button;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
