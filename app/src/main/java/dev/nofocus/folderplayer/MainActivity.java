package dev.nofocus.folderplayer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_TREE = 1001;
    private static final int REQUEST_NETWORK = 1003;
    private static final int REQUEST_NOTIFICATIONS = 1004;
    private static final String ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final String PREF_MODE = "ui_music_mode";
    private static final int INK = Color.rgb(23, 44, 47);
    private static final int MUTED = Color.rgb(77, 101, 104);
    private static final int ACCENT = Color.rgb(0, 100, 91);
    private static final int SURFACE = Color.rgb(242, 247, 245);
    private static final int TINT = Color.rgb(223, 238, 232);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService extractionExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView titleView;
    private TextView detailView;
    private TextView extractionView;
    private Button primaryButton;
    private Button stopButton;
    private Button extractButton;
    private ImageButton previousButton;
    private ImageButton nextButton;
    private boolean musicMode;
    private String page = "home";
    private boolean receiverRegistered;
    private boolean extractionRunning;
    private boolean pendingWifiStart;
    private String extractionText = "Saves audio in your music folder.";
    private Intent playerState;
    private Intent wifiState;
    private OnBackInvokedCallback backCallback;
    private boolean backCallbackRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PlayerService.ACTION_STATE.equals(intent.getAction())) {
                playerState = intent;
            } else if (WifiStreamService.ACTION_STATE.equals(intent.getAction())) {
                wifiState = intent;
            }
            updatePlayback();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().setNavigationBarColor(SURFACE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        prefs = getSharedPreferences(PlayerService.PREFS, MODE_PRIVATE);
        musicMode = prefs.getBoolean(PREF_MODE, false);
        if (savedInstanceState != null) {
            page = savedInstanceState.getString("page", "home");
            pendingWifiStart = savedInstanceState.getBoolean("pendingWifiStart");
        }
        ensurePairingCode();
        refreshState();
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("page", page);
        state.putBoolean("pendingWifiStart", pendingWifiStart);
    }

    @Override @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(PlayerService.ACTION_STATE);
        filter.addAction(WifiStreamService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            if (backCallback == null) backCallback = this::onBackPressed;
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        receiverRegistered = true;
        refreshState();
        if (getIntent().getBooleanExtra("pcSetup", false)) { musicMode = false; page = "setup"; getIntent().removeExtra("pcSetup"); }
        buildUi();
        if (Build.VERSION.SDK_INT >= 29) extractionExecutor.execute(() -> {
            try { new VisualMusicPc(new VisualMusicLibrary(getApplicationContext())).refreshConnection(); } catch (Exception ignored) { }
        });
    }

    @Override protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        extractionExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refreshState() {
        playerState = PlayerService.currentState();
        wifiState = WifiStreamService.currentState();
    }

    private void showPage(String destination) {
        page = destination;
        buildUi();
    }

    @Override public void onBackPressed() {
        if (!"home".equals(page)) {
            showPage("quality".equals(page) ? "options" : "extract".equals(page) ? "add"
                    : "add".equals(page) || "options".equals(page) ? "setup" : "home");
        } else {
            super.onBackPressed();
        }
    }

    private void buildUi() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (backCallbackRegistered) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallbackRegistered = !"home".equals(page);
            if (backCallbackRegistered) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        titleView = null;
        detailView = null;
        extractionView = null;
        extractButton = null;
        root = column();
        root.setBackgroundColor(SURFACE);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        // Android 15+ draws behind the system bars. Use the actual safe area, not fixed top padding.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left + dp(16), top + dp(8), right + dp(16), bottom + dp(8));
            return insets.consumeSystemWindowInsets();
        });
        if ("home".equals(page)) {
            buildHome();
        } else {
            buildHeader("add".equals(page) ? "Add music" : "quality".equals(page) ? "Sound quality" : "extract".equals(page)
                    ? "Video audio" : "options".equals(page) ? "PC options" : musicMode ? "Music setup" : "PC setup", true);
            if ("quality".equals(page)) buildQuality();
            else if ("options".equals(page)) buildWifiOptions();
            else if ("extract".equals(page)) buildExtraction();
            else if ("add".equals(page)) {
                root.addView(button("Download song", v -> startActivity(new Intent(this, SongDownloadActivity.class)), true), spaced(8));
                root.addView(button("Get video audio", v -> showPage("extract"), false), spaced(8));
                root.addView(button("Capture lyrics video", v -> startActivity(new Intent(this, CaptureActivity.class)), false), spaced(8));
            }
            else if (musicMode) buildMusicSetup();
            else buildWifiSetup();
        }
        setContentView(root);
        root.requestApplyInsets();
        updatePlayback();
    }

    private void buildHeader(String title, boolean back) {
        LinearLayout header = row();
        if (back) {
            Button backButton = button("Back", v -> onBackPressed(), false);
            header.addView(backButton, new LinearLayout.LayoutParams(-2, dp(48)));
            gap(header, 8);
        }
        TextView heading = text(title, 20, INK);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setSingleLine();
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (!back) {
            Button visuals = button("Visuals", v -> startActivity(new Intent(this, VisualMusicActivity.class)), false);
            visuals.setContentDescription("Visual music");
            header.addView(visuals, new LinearLayout.LayoutParams(-2, dp(48)));
            Button setup = button("Setup", v -> showPage("setup"), false);
            header.addView(setup, new LinearLayout.LayoutParams(-2, dp(48)));
        }
        root.addView(header, fullWidth());
    }

    private void buildHome() {
        buildHeader("NoFocus", false);
        LinearLayout informationColumn = root;
        LinearLayout controlsColumn = root;
        if (isLandscape()) {
            LinearLayout columns = row();
            informationColumn = column();
            controlsColumn = column();
            controlsColumn.setGravity(Gravity.CENTER_VERTICAL);
            columns.addView(informationColumn, new LinearLayout.LayoutParams(0, -1, 1));
            gap(columns, 16);
            columns.addView(controlsColumn, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout modes = row();
        boolean shortTabs = isLandscape() || getResources().getConfiguration().fontScale >= 1.75f;
        Button pc = button(shortTabs ? "PC" : "PC audio", v -> selectMode(false), !musicMode);
        pc.setSelected(!musicMode);
        pc.setContentDescription("PC audio");
        Button music = button(shortTabs ? "Music" : "My music", v -> selectMode(true), musicMode);
        music.setSelected(musicMode);
        music.setContentDescription("My music");
        if (isLandscape()) {
            int tabSize = getResources().getConfiguration().fontScale >= 1.75f ? 14 : 16;
            pc.setTextSize(tabSize);
            music.setTextSize(tabSize);
            pc.setPadding(0, 0, 0, 0);
            music.setPadding(0, 0, 0, 0);
        }
        modes.addView(pc, weighted(52));
        gap(modes, 8);
        modes.addView(music, weighted(52));
        informationColumn.addView(modes, spaced(4));

        LinearLayout information = column();
        information.setGravity(Gravity.CENTER);
        information.setPadding(dp(8), dp(4), dp(8), dp(4));
        titleView = text("", 24, INK);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        detailView = text("", 16, MUTED);
        detailView.setGravity(Gravity.CENTER);
        detailView.setSingleLine();
        detailView.setEllipsize(TextUtils.TruncateAt.END);
        information.addView(titleView, fullWidth());
        information.addView(detailView, fullWidth());
        TextView trackTitle = titleView;
        TextView status = detailView;
        information.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = bottom - top - view.getPaddingTop() - view.getPaddingBottom()
                    - status.getLineHeight() - fontPadding(status);
            int lines = available >= trackTitle.getLineHeight() * 2 + fontPadding(trackTitle) ? 2 : 1;
            if (trackTitle.getMaxLines() != lines) trackTitle.setMaxLines(lines);
        });
        information.setContentDescription(musicMode ? "Track and playback details" : "Connection details");
        information.setFocusable(true);
        information.setOnClickListener(v -> showDetails());
        informationColumn.addView(information, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout transport = row();
        if (musicMode && !isLandscape()) {
            previousButton = skipButton("Previous track", R.drawable.ic_skip_previous_24,
                    PlayerService.ACTION_PREVIOUS);
            transport.addView(previousButton, new LinearLayout.LayoutParams(dp(56), dp(64)));
            gap(transport, 8);
        }
        primaryButton = button("", v -> {
            if (musicMode) {
                if (!hasFolder()) chooseFolder();
                else startPlayerAction(PlayerService.ACTION_TOGGLE_PLAY_PAUSE);
            } else {
                startWifiAction(isWifiRunning() ? WifiStreamService.ACTION_STOP : WifiStreamService.ACTION_START);
            }
        }, true);
        primaryButton.setTextSize(22);
        transport.addView(primaryButton, weighted(64));
        if (musicMode && !isLandscape()) {
            gap(transport, 8);
            nextButton = skipButton("Next track", R.drawable.ic_skip_next_24, PlayerService.ACTION_NEXT);
            transport.addView(nextButton, new LinearLayout.LayoutParams(dp(56), dp(64)));
        }
        controlsColumn.addView(transport, spaced(isLandscape() ? 0 : 4));
        if (musicMode) {
            LinearLayout secondary = row();
            if (isLandscape()) {
                previousButton = skipButton("Previous track", R.drawable.ic_skip_previous_24, PlayerService.ACTION_PREVIOUS);
                secondary.addView(previousButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
            } else {
                secondary.addView(button("Folder", v -> chooseFolder(), false), weighted(48));
            }
            gap(secondary, 4);
            stopButton = button("Stop", v -> stopFolderPlayback(), false);
            if (isLandscape()) {
                stopButton.setTextSize(16);
                stopButton.setPadding(0, 0, 0, 0);
            }
            secondary.addView(stopButton, weighted(48));
            if (isLandscape()) {
                gap(secondary, 4);
                nextButton = skipButton("Next track", R.drawable.ic_skip_next_24, PlayerService.ACTION_NEXT);
                secondary.addView(nextButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            controlsColumn.addView(secondary, spaced(isLandscape() ? 0 : 4));
        } else {
            Button connect = button(isLandscape() ? "Connect" : "Connect PC", v -> showPage("setup"), false);
            connect.setContentDescription("Connect PC");
            controlsColumn.addView(connect, spaced(isLandscape() ? 0 : 4));
        }
        buildVolume(controlsColumn);
    }

    private void selectMode(boolean music) {
        musicMode = music;
        prefs.edit().putBoolean(PREF_MODE, music).apply();
        buildUi();
    }

    private void buildVolume(LinearLayout parent) {
        LinearLayout volumeRow = row();
        TextView label = text("Volume", 16, MUTED);
        volumeRow.addView(label);
        SeekBar seek = new SeekBar(this);
        seek.setContentDescription(musicMode ? "Music volume" : "PC audio volume");
        seek.setMax(100);
        String key = musicMode ? PlayerService.PREF_VOLUME : WifiStreamService.PREF_STREAM_VOLUME;
        seek.setProgress(Math.round(prefs.getFloat(key, 1f) * 100));
        seek.setMinimumHeight(dp(48));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                prefs.edit().putFloat(key, progress / 100f).apply();
                // Changing a setting while stopped must not start a playback service.
                if (musicMode && hasPlayerSession()) startPlayerAction(PlayerService.ACTION_SET_VOLUME);
                else if (!musicMode && isWifiRunning()) startWifiAction(WifiStreamService.ACTION_SET_VOLUME);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        volumeRow.addView(seek, weighted(48));
        parent.addView(volumeRow, fullWidth());
    }

    private void buildWifiSetup() {
        LinearLayout details = root, actions = root;
        if (isLandscape()) {
            LinearLayout columns=row();details=column();actions=column();
            columns.addView(details,new LinearLayout.LayoutParams(0,-1,1));gap(columns,16);
            columns.addView(actions,new LinearLayout.LayoutParams(0,-1,1));
            root.addView(columns,new LinearLayout.LayoutParams(-1,0,1));
        }
        String pc = getSharedPreferences(PhoneAudioService.PREFS,0).getString("name", "");
        TextView paired = text(pc.isEmpty() ? "Pair once for audio + exports" : "Paired with " + pc, 20, INK);
        paired.setMaxLines(3);paired.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(paired,spaced(8));
        if(!isLandscape())spacer();
        actions.addView(button("Listen on PC",v -> startActivity(new Intent(this,PhoneAudioActivity.class)),true),spaced(4));
        actions.addView(button("Pair PC",v -> startActivity(new Intent(this,VisualMusicPairActivity.class)),false),spaced(4));
        actions.addView(button("More options",v -> showPage("options"),false),spaced(4));
    }

    private void buildWifiOptions() {
        LinearLayout details = root;
        LinearLayout options = root;
        if (isLandscape()) {
            LinearLayout columns = row();
            details = column();
            options = column();
            columns.addView(details, new LinearLayout.LayoutParams(0, -1, 1));
            gap(columns, 16);
            columns.addView(options, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        SharedPreferences outgoing = getSharedPreferences(PhoneAudioService.PREFS, 0);
        boolean unified = outgoing.getBoolean("unified", false);
        TextView manualCode = text(unified ? "Audio + exports\n" + outgoing.getString("name", "Your PC") : "Manual audio code\n" + displayCode(), 18, INK);
        manualCode.setMaxLines(4); manualCode.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(manualCode, spaced(4));
        options.addView(button(unified ? "Pair PC" : "Copy setup", v -> {
            if (unified) startActivity(new Intent(this, VisualMusicPairActivity.class)); else copyWifiSetup();
        }, false), spaced(4));
        options.addView(button("Sound quality", v -> showPage("quality"), false), spaced(4));
        options.addView(button(unified ? "Forget PC" : "New code", v -> {
            if (unified) {
                new AlertDialog.Builder(this).setTitle("Forget this PC?")
                        .setMessage("Audio will stop and this phone will need pairing again for audio and exports.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Forget PC", (dialog, which) -> extractionExecutor.execute(() -> {
                            try {
                                new VisualMusicPc(new VisualMusicLibrary(getApplicationContext())).forget();
                                runOnUiThread(() -> { if (!isDestroyed()) showPage("setup"); });
                            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) toast(error.getMessage()); }); }
                        })).show();
                return;
            }
            new AlertDialog.Builder(this).setTitle("Replace pairing code?")
                    .setMessage("PC audio will stop. Enter the new code on your PC to reconnect.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("New code", (dialog, which) -> rotatePairingCode()).show();
        }, false), spaced(4));
        if (!isLandscape()) spacer();
    }

    private String displayCode() {
        String code = PairingCode.display(currentPairingCode());
        // Two groups per line remain readable with large accessibility text on a narrow phone.
        return code.length() == 19 ? code.substring(0, 9) + "\n" + code.substring(10) : code;
    }

    private void buildQuality() {
        int selected = prefs.getInt(WifiStreamService.PREF_LATENCY_PACKETS, 4);
        addQuality("Fastest · 10 ms", 2, selected <= 2);
        addQuality("Balanced · 20 ms", 4, selected > 2 && selected < 8);
        addQuality("Reliable · 40 ms", 8, selected >= 8);
        spacer();
    }

    private void addQuality(String label, int packets, boolean selected) {
        Button option = button(label, v -> {
            prefs.edit().putInt(WifiStreamService.PREF_LATENCY_PACKETS, packets).apply();
            toast("Applies next time you start PC audio.");
            showPage("options");
        }, selected);
        option.setSelected(selected);
        root.addView(option, spaced(4));
    }

    private void buildMusicSetup() {
        LinearLayout folderColumn = root;
        LinearLayout options = root;
        if (isLandscape()) {
            LinearLayout columns = row();
            folderColumn = column();
            options = column();
            columns.addView(folderColumn, new LinearLayout.LayoutParams(0, -1, 1));
            gap(columns, 16);
            columns.addView(options, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        TextView folder = text(folderName(), 20, INK);
        folder.setMaxLines(isLandscape() ? 1 : 2);
        folder.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderColumn.addView(folder, spaced(4));
        Button choose = button(isLandscape() ? "Folder" : "Choose folder", v -> chooseFolder(), true);
        choose.setContentDescription("Choose folder");
        folderColumn.addView(choose, spaced(4));
        Button rescan = button(isLandscape() ? "Rescan" : "Rescan folder", v -> {
            startPlayerAction(PlayerService.ACTION_PLAY);
            showPage("home");
        }, false);
        rescan.setContentDescription("Rescan folder");
        rescan.setEnabled(hasFolder());
        folderColumn.addView(rescan, spaced(4));
        CheckBox shuffle = new CheckBox(this);
        shuffle.setText("Shuffle songs");
        shuffle.setTextSize(18);
        shuffle.setTextColor(INK);
        shuffle.setMinHeight(dp(48));
        shuffle.setChecked(prefs.getBoolean(PlayerService.PREF_SHUFFLE, false));
        shuffle.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(PlayerService.PREF_SHUFFLE, checked).apply();
            if (hasPlayerSession()) startPlayerAction(PlayerService.ACTION_SET_SHUFFLE);
        });
        options.addView(shuffle, spaced(4));
        if (!isLandscape()) spacer();
        Button video = button("Add music", v -> showPage("add"), false);
        options.addView(video, spaced(4));
    }

    private void buildExtraction() {
        extractionView = text(extractionText, 18, INK);
        extractionView.setGravity(Gravity.CENTER_VERTICAL);
        extractionView.setMaxLines(8);
        extractionView.setEllipsize(TextUtils.TruncateAt.END);
        extractionView.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Video audio")
                .setMessage(extractionText).setPositiveButton("Close", null).show());
        root.addView(extractionView, new LinearLayout.LayoutParams(-1, 0, 1));
        extractButton = button(hasFolder() ? "Get audio" : "Choose folder", v -> {
            if (hasFolder()) startAudioExtraction();
            else chooseFolder();
        }, true);
        extractButton.setEnabled(!extractionRunning);
        root.addView(extractButton, spaced(4));
    }

    private void updatePlayback() {
        if (!"home".equals(page) || titleView == null) return;
        if (musicMode) {
            boolean playing = playerState != null && playerState.getBooleanExtra(PlayerService.EXTRA_IS_PLAYING, false);
            boolean preparing = playerState != null && playerState.getBooleanExtra(PlayerService.EXTRA_PREPARING, false);
            String status = playerState == null ? "" : nonEmpty(playerState.getStringExtra(PlayerService.EXTRA_STATE), "");
            String track = playerState == null ? null : playerState.getStringExtra(PlayerService.EXTRA_TRACK_NAME);
            int count = playerState == null ? 0 : playerState.getIntExtra(PlayerService.EXTRA_TRACK_COUNT, 0);
            int index = playerState == null ? -1 : playerState.getIntExtra(PlayerService.EXTRA_TRACK_INDEX, -1);
            boolean error = status.contains("failed") || status.contains("error") || status.startsWith("Could not")
                    || status.startsWith("No supported") || status.startsWith("Folder scan") || status.startsWith("Bad ");
            titleView.setText(error ? "Can't play audio" : nonEmpty(track, hasFolder() ? folderName() : "Choose your music"));
            detailView.setText(error ? "Tap for details" : preparing ? "Loading…" : playing
                    ? "Playing" + (count > 0 ? " · " + (index + 1) + " / " + count : "")
                    : index >= 0 ? "Paused" : hasFolder() ? "Ready to play" : "Pick a folder to begin");
            primaryButton.setText(!hasFolder() ? "Choose" : playing ? "Pause" : "Play");
            primaryButton.setContentDescription(!hasFolder() ? "Choose music folder" : playing ? "Pause music" : "Play music");
            primaryButton.setEnabled(!preparing);
            previousButton.setEnabled(count > 0 && !preparing);
            nextButton.setEnabled(count > 0 && !preparing);
            stopButton.setEnabled(hasPlayerSession() || preparing);
        } else {
            boolean connected = wifiState != null && wifiState.getBooleanExtra(WifiStreamService.EXTRA_CONNECTED, false);
            String status = wifiState == null ? "" : nonEmpty(wifiState.getStringExtra(WifiStreamService.EXTRA_STATUS), "");
            boolean error = status.contains("failed") || status.contains("invalid");
            titleView.setText(error ? "Can't connect" : connected ? "Playing PC audio" : isWifiRunning() ? "Waiting for PC" : "PC audio");
            detailView.setText(error ? "Tap for details" : connected
                    ? nonEmpty(wifiState.getStringExtra(WifiStreamService.EXTRA_SENDER), "Connected")
                    : isWifiRunning() ? "Use Connect PC for setup" : "Ready to connect");
            primaryButton.setText(isWifiRunning() ? "Stop" : "Start");
            primaryButton.setContentDescription(isWifiRunning() ? "Stop PC audio" : "Start PC audio");
        }
    }

    private void showDetails() {
        String detail;
        if (musicMode) {
            String track = playerState == null ? null : playerState.getStringExtra(PlayerService.EXTRA_TRACK_NAME);
            String status = playerState == null ? null : playerState.getStringExtra(PlayerService.EXTRA_STATE);
            detail = nonEmpty(track, folderName()) + "\n\n" + nonEmpty(status, "Ready to play");
        } else {
            detail = wifiState == null ? "Tap Start, then open NoFocus on your PC." : wifiState.getStringExtra(WifiStreamService.EXTRA_STATUS);
        }
        new AlertDialog.Builder(this).setTitle(musicMode ? "Music" : "PC audio")
                .setMessage(detail).setPositiveButton("Close", null).show();
    }

    private boolean hasPlayerSession() {
        return playerState != null && (playerState.getBooleanExtra(PlayerService.EXTRA_ACTIVE, false)
                || playerState.getIntExtra(PlayerService.EXTRA_TRACK_COUNT, 0) > 0
                || playerState.getBooleanExtra(PlayerService.EXTRA_PREPARING, false));
    }

    private boolean isWifiRunning() {
        return wifiState != null && wifiState.getBooleanExtra(WifiStreamService.EXTRA_RUNNING, false);
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private LinearLayout row() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }

    private int fontPadding(TextView text) {
        android.graphics.Paint.FontMetricsInt metrics = text.getPaint().getFontMetricsInt();
        return metrics.bottom - metrics.descent + metrics.ascent - metrics.top;
    }

    private Button button(String label, View.OnClickListener click, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(18);
        button.setTextColor(primary ? Color.WHITE : ACCENT);
        button.setAllCaps(false);
        button.setTypeface(null, Typeface.BOLD);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundTintList(null);
        button.setBackground(buttonBackground(primary));
        button.setOnClickListener(click);
        button.setLayoutParams(fullWidth());
        return button;
    }

    private RippleDrawable buttonBackground(boolean primary) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.rgb(226, 232, 230), primary ? ACCENT : TINT}));
        shape.setCornerRadius(dp(16));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(50, 130, 180, 165)), shape, null);
    }

    private ImageButton skipButton(String label, int icon, String action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{MUTED, ACCENT}));
        button.setContentDescription(label);
        button.setBackground(buttonBackground(false));
        button.setOnClickListener(v -> startPlayerAction(action));
        return button;
    }

    private void gap(LinearLayout row, int width) {
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(width), 1));
    }

    private void spacer() {
        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
    }

    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams weighted(int height) {
        return new LinearLayout.LayoutParams(0, dp(height), 1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (takeFlags == 0) takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(tree, takeFlags);
        } catch (SecurityException e) {
            toast("Could not keep folder access. Choose it again next time.");
        }
        prefs.edit().putString(PlayerService.PREF_TREE_URI, tree.toString()).apply();
        if ("extract".equals(page)) {
            buildUi();
        } else {
            musicMode = true;
            prefs.edit().putBoolean(PREF_MODE, true).apply();
            showPage("home");
            startPlayerAction(PlayerService.ACTION_PLAY);
        }
    }

    private void startAudioExtraction() {
        if (extractionRunning) return;
        String tree = prefs.getString(PlayerService.PREF_TREE_URI, null);
        if (!hasPersistedWriteAccess(tree)) {
            setExtractionText("Choose the folder again to allow saving audio.");
            chooseFolder();
            return;
        }
        extractionRunning = true;
        if (extractButton != null) extractButton.setEnabled(false);
        setExtractionText("Getting audio…");
        extractionExecutor.execute(() -> {
            VideoAudioExtractor.Result result = new VideoAudioExtractor(this).extract(tree,
                    status -> mainHandler.post(() -> setExtractionText(status)));
            mainHandler.post(() -> {
                if (isDestroyed()) return;
                extractionRunning = false;
                if (extractButton != null) extractButton.setEnabled(true);
                setExtractionText(result.detailText());
                if (result.hasPlayableOutput()) startPlayerAction(PlayerService.ACTION_PLAY);
            });
        });
    }

    private void setExtractionText(String text) {
        extractionText = text;
        if (extractionView != null) extractionView.setText(text);
    }

    private void startPlayerAction(String action) {
        boolean transport = PlayerService.ACTION_PLAY.equals(action) || PlayerService.ACTION_TOGGLE_PLAY_PAUSE.equals(action)
                || PlayerService.ACTION_NEXT.equals(action) || PlayerService.ACTION_PREVIOUS.equals(action);
        if (transport) {
            if (!hasFolder()) { chooseFolder(); return; }
            startWifiAction(WifiStreamService.ACTION_STOP);
        }
        Intent intent = new Intent(this, PlayerService.class).setAction(action);
        intent.putExtra(PlayerService.EXTRA_TREE_URI, prefs.getString(PlayerService.PREF_TREE_URI, null));
        intent.putExtra(PlayerService.EXTRA_SHUFFLE, prefs.getBoolean(PlayerService.PREF_SHUFFLE, false));
        intent.putExtra(PlayerService.EXTRA_VOLUME, prefs.getFloat(PlayerService.PREF_VOLUME, 1f));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void startWifiAction(String action) {
        if (WifiStreamService.ACTION_STOP.equals(action)) {
            stopService(new Intent(this, WifiStreamService.class));
            wifiState = null;
            updatePlayback();
            return;
        }
        if (WifiStreamService.ACTION_START.equals(action)) {
            if (!hasLocalNetworkPermission()) {
                pendingWifiStart = true;
                requestPermissions(new String[]{networkPermission()}, REQUEST_NETWORK);
                return;
            }
            stopFolderPlayback();
        }
        Intent intent = new Intent(this, WifiStreamService.class).setAction(action);
        intent.putExtra("volume", prefs.getFloat(WifiStreamService.PREF_STREAM_VOLUME, 1f));
        if (WifiStreamService.ACTION_START.equals(action) && Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void stopFolderPlayback() {
        stopService(new Intent(this, PlayerService.class));
        playerState = null;
        updatePlayback();
    }

    private void ensurePairingCode() {
        if (prefs.getString(WifiStreamService.PREF_PAIRING_CODE, null) == null) {
            prefs.edit().putString(WifiStreamService.PREF_PAIRING_CODE, PairingCode.generate()).apply();
        }
    }

    private String currentPairingCode() {
        return prefs.getString(WifiStreamService.PREF_PAIRING_CODE, "");
    }

    private void rotatePairingCode() {
        startWifiAction(WifiStreamService.ACTION_STOP);
        prefs.edit().putString(WifiStreamService.PREF_PAIRING_CODE, PairingCode.generate()).apply();
        showPage("setup");
    }

    private void copyWifiSetup() {
        String setup = "nofocus://connect?host=" + NetworkAddress.localIpv4()
                + "&code=" + PairingCode.display(currentPairingCode());
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("NoFocus PC setup", setup));
            toast("Copied. Use Paste phone setup on your PC.");
        }
    }

    private boolean hasFolder() {
        return prefs.getString(PlayerService.PREF_TREE_URI, null) != null;
    }

    private boolean hasPersistedWriteAccess(String tree) {
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (tree != null && tree.equals(permission.getUri().toString()) && permission.isWritePermission()) return true;
        }
        return false;
    }

    private String folderName() {
        String tree = prefs.getString(PlayerService.PREF_TREE_URI, null);
        if (tree == null) return "No folder chosen";
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(tree));
            return id.substring(Math.max(id.lastIndexOf('/'), id.lastIndexOf(':')) + 1);
        } catch (Exception ignored) {
            return "Music folder";
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @SuppressLint("InlinedApi") // Requested only on API 36+ by hasLocalNetworkPermission().
    private String networkPermission() {
        return Build.VERSION.SDK_INT >= 37 ? ACCESS_LOCAL_NETWORK : Manifest.permission.NEARBY_WIFI_DEVICES;
    }

    private boolean hasLocalNetworkPermission() {
        return Build.VERSION.SDK_INT < 36 || checkSelfPermission(networkPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NETWORK && pendingWifiStart) {
            pendingWifiStart = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startWifiAction(WifiStreamService.ACTION_START);
            else toast("Allow nearby devices in Settings to connect your PC.");
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
