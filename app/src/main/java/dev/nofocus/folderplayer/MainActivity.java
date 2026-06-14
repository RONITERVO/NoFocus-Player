package dev.nofocus.folderplayer;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_TREE = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService extractionExecutor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private TextView folderView;
    private TextView statusView;
    private TextView nowPlayingView;
    private TextView countView;
    private TextView extractionView;
    private Button playPauseButton;
    private Button extractButton;
    private CheckBox shuffleCheck;
    private SeekBar volumeSeek;
    private boolean receiverRegistered = false;
    private boolean extractionRunning = false;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !PlayerService.ACTION_STATE.equals(intent.getAction())) {
                return;
            }
            String state = intent.getStringExtra(PlayerService.EXTRA_STATE);
            String track = intent.getStringExtra(PlayerService.EXTRA_TRACK_NAME);
            int count = intent.getIntExtra(PlayerService.EXTRA_TRACK_COUNT, 0);
            int index = intent.getIntExtra(PlayerService.EXTRA_TRACK_INDEX, -1);
            boolean playing = intent.getBooleanExtra(PlayerService.EXTRA_IS_PLAYING, false);

            statusView.setText(nonEmpty(state, "Idle"));
            nowPlayingView.setText(track == null ? "-" : track);
            if (count > 0 && index >= 0) {
                countView.setText((index + 1) + " of " + count + " tracks");
            } else {
                countView.setText(count + " tracks");
            }
            playPauseButton.setText(playing ? "Pause" : "Play");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PlayerService.PREFS, MODE_PRIVATE);
        requestNotificationPermissionIfNeeded();
        buildUi();
        updateFolderText();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(PlayerService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        receiverRegistered = true;
        updateFolderText();
    }

    @Override
    protected void onPause() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already gone.
            }
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        extractionExecutor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        int pad = dp(20);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setClipToPadding(false);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.parseColor("#F0F2F5"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(32), pad, dp(40));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("NoFocus Player");
        title.setTextSize(28);
        title.setTextColor(Color.parseColor("#111111"));
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, fullWidth());

        TextView explanation = new TextView(this);
        explanation.setText("Plays local folder music without requesting audio focus. Designed to run alongside apps like YouTube.");
        explanation.setTextSize(15);
        explanation.setTextColor(Color.parseColor("#555555"));
        explanation.setLineSpacing(dp(4), 1f);
        explanation.setPadding(0, 0, 0, dp(24));
        root.addView(explanation, fullWidth());

        LinearLayout sourceCard = createCard(root);
        sourceCard.addView(createSectionLabel("MUSIC SOURCE"));

        folderView = new TextView(this);
        folderView.setTextSize(16);
        folderView.setTextColor(Color.parseColor("#222222"));
        folderView.setPadding(0, dp(8), 0, dp(16));
        sourceCard.addView(folderView, fullWidth());

        sourceCard.addView(createButton("Choose music folder", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseFolder();
            }
        }));

        sourceCard.addView(createButton("Start / rescan folder", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasFolder()) {
                    toast("Choose a folder first.");
                    return;
                }
                startPlayerAction(PlayerService.ACTION_PLAY);
            }
        }));

        sourceCard.addView(createDivider(dp(20), dp(20)));
        sourceCard.addView(createSectionLabel("EXTRACTION TOOLS"));

        extractionView = new TextView(this);
        extractionView.setText("Idle");
        extractionView.setTextSize(14);
        extractionView.setTextColor(Color.parseColor("#555555"));
        extractionView.setPadding(0, dp(8), 0, dp(12));
        sourceCard.addView(extractionView, fullWidth());

        extractButton = createButton("Extract video audio", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAudioExtraction();
            }
        });
        sourceCard.addView(extractButton);

        LinearLayout playingCard = createCard(root);
        playingCard.addView(createSectionLabel("NOW PLAYING"));

        statusView = new TextView(this);
        statusView.setText("Idle");
        statusView.setTextSize(22);
        statusView.setTextColor(Color.parseColor("#111111"));
        statusView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusView.setPadding(0, dp(8), 0, dp(4));
        playingCard.addView(statusView, fullWidth());

        nowPlayingView = new TextView(this);
        nowPlayingView.setText("-");
        nowPlayingView.setTextSize(16);
        nowPlayingView.setTextColor(Color.parseColor("#444444"));
        playingCard.addView(nowPlayingView, fullWidth());

        countView = new TextView(this);
        countView.setText("0 tracks");
        countView.setTextSize(14);
        countView.setTextColor(Color.parseColor("#888888"));
        countView.setPadding(0, dp(4), 0, dp(24));
        playingCard.addView(countView, fullWidth());

        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        playingCard.addView(controlsRow, fullWidth());

        controlsRow.addView(createButton("Prev", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPlayerAction(PlayerService.ACTION_PREVIOUS);
            }
        }), weighted());

        controlsRow.addView(new View(this), new LinearLayout.LayoutParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT));

        playPauseButton = createButton("Play", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPlayerAction(PlayerService.ACTION_TOGGLE_PLAY_PAUSE);
            }
        });
        playPauseButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        controlsRow.addView(playPauseButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));

        controlsRow.addView(new View(this), new LinearLayout.LayoutParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT));

        controlsRow.addView(createButton("Next", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPlayerAction(PlayerService.ACTION_NEXT);
            }
        }), weighted());

        playingCard.addView(createDivider(dp(24), dp(16)));

        LinearLayout settingsRow = new LinearLayout(this);
        settingsRow.setOrientation(LinearLayout.HORIZONTAL);
        settingsRow.setGravity(Gravity.CENTER_VERTICAL);
        playingCard.addView(settingsRow, fullWidth());

        shuffleCheck = new CheckBox(this);
        shuffleCheck.setText("Shuffle");
        shuffleCheck.setTextColor(Color.parseColor("#222222"));
        shuffleCheck.setChecked(prefs.getBoolean(PlayerService.PREF_SHUFFLE, false));
        shuffleCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(PlayerService.PREF_SHUFFLE, isChecked).apply();
                startPlayerAction(PlayerService.ACTION_SET_SHUFFLE);
            }
        });
        settingsRow.addView(shuffleCheck);

        LinearLayout volumeBox = new LinearLayout(this);
        volumeBox.setOrientation(LinearLayout.VERTICAL);
        volumeBox.setPadding(dp(16), 0, 0, 0);

        TextView volumeLabel = new TextView(this);
        volumeLabel.setText("App volume");
        volumeLabel.setTextSize(12);
        volumeLabel.setTextColor(Color.parseColor("#888888"));
        volumeBox.addView(volumeLabel);

        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(Math.round(prefs.getFloat(PlayerService.PREF_VOLUME, 1.0f) * 100f));
        volumeSeek.setPadding(0, dp(8), 0, dp(8));
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float volume = Math.max(0f, Math.min(1f, progress / 100f));
                    prefs.edit().putFloat(PlayerService.PREF_VOLUME, volume).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                startPlayerAction(PlayerService.ACTION_SET_VOLUME);
            }
        });
        volumeBox.addView(volumeSeek, fullWidth());
        settingsRow.addView(volumeBox, weighted());

        Button stopButton = new Button(this);
        stopButton.setText("Stop background service");
        stopButton.setTextColor(Color.parseColor("#D32F2F"));
        stopButton.setBackgroundColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 21) {
            stopButton.setStateListAnimator(null);
        }
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPlayerAction(PlayerService.ACTION_STOP);
            }
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        stopParams.gravity = Gravity.CENTER_HORIZONTAL;
        stopParams.setMargins(0, dp(8), 0, dp(16));
        root.addView(stopButton, stopParams);

        TextView usage = new TextView(this);
        usage.setText("Choose a music subfolder, press Start, then open YouTube. Android 11+ may block storage root or Download; choose Music or a subfolder instead.");
        usage.setTextSize(13);
        usage.setTextColor(Color.parseColor("#888888"));
        usage.setGravity(Gravity.CENTER_HORIZONTAL);
        usage.setLineSpacing(dp(2), 1f);
        usage.setPadding(dp(16), 0, dp(16), dp(16));
        root.addView(usage, fullWidth());

        setContentView(scrollView);
    }

    private LinearLayout createCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(8));
        card.setBackground(background);

        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(2));
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(8), 0, dp(16));
        parent.addView(card, params);
        return card;
    }

    private TextView createSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(Color.parseColor("#888888"));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private Button createButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(6), 0, dp(6));
        button.setLayoutParams(params);
        return button;
    }

    private View createDivider(int topMargin, int bottomMargin) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        params.setMargins(0, topMargin, 0, bottomMargin);
        divider.setLayoutParams(params);
        return divider;
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri treeUri = data.getData();
        int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (takeFlags == 0) {
            takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        try {
            getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        } catch (SecurityException e) {
            toast("Could not persist folder access: " + e.getMessage());
        }

        prefs.edit().putString(PlayerService.PREF_TREE_URI, treeUri.toString()).apply();
        updateFolderText();
        startPlayerAction(PlayerService.ACTION_PLAY);
    }

    private void startAudioExtraction() {
        if (extractionRunning) {
            toast("Extraction is already running.");
            return;
        }

        final String tree = prefs.getString(PlayerService.PREF_TREE_URI, null);
        if (tree == null) {
            toast("Choose a folder first.");
            return;
        }
        if (!hasPersistedWriteAccess(tree)) {
            setExtractionText("Choose the folder again to grant write access.");
            toast("Choose the folder again before extracting.");
            return;
        }

        extractionRunning = true;
        if (extractButton != null) {
            extractButton.setEnabled(false);
        }
        setExtractionText("Preparing...");

        extractionExecutor.execute(new Runnable() {
            @Override
            public void run() {
                VideoAudioExtractor extractor = new VideoAudioExtractor(MainActivity.this);
                final VideoAudioExtractor.Result result = extractor.extract(tree, new VideoAudioExtractor.Callback() {
                    @Override
                    public void onStatus(final String status) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                setExtractionText(status);
                            }
                        });
                    }
                });

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        extractionRunning = false;
                        if (extractButton != null) {
                            extractButton.setEnabled(true);
                        }
                        setExtractionText(result.detailText());
                        if (result.hasPlayableOutput()) {
                            startPlayerAction(PlayerService.ACTION_PLAY);
                        }
                    }
                });
            }
        });
    }

    private void setExtractionText(String text) {
        if (extractionView != null) {
            extractionView.setText(text);
        }
    }

    private void startPlayerAction(String action) {
        Intent intent = new Intent(this, PlayerService.class);
        intent.setAction(action);
        String tree = prefs.getString(PlayerService.PREF_TREE_URI, null);
        if (tree != null) {
            intent.putExtra(PlayerService.EXTRA_TREE_URI, tree);
        }
        intent.putExtra(PlayerService.EXTRA_SHUFFLE, shuffleCheck != null && shuffleCheck.isChecked());
        if (volumeSeek != null) {
            intent.putExtra(PlayerService.EXTRA_VOLUME, Math.max(0f, Math.min(1f, volumeSeek.getProgress() / 100f)));
        }

        if (PlayerService.ACTION_STOP.equals(action)) {
            startService(intent);
        } else if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private boolean hasFolder() {
        return prefs.getString(PlayerService.PREF_TREE_URI, null) != null;
    }

    private boolean hasPersistedWriteAccess(String tree) {
        if (tree == null) {
            return false;
        }
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (tree.equals(permission.getUri().toString()) && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private void updateFolderText() {
        String tree = prefs.getString(PlayerService.PREF_TREE_URI, null);
        if (tree == null) {
            folderView.setText("Not selected");
            folderView.setTextColor(Color.parseColor("#888888"));
            return;
        }
        String display = tree;
        try {
            display = DocumentsContract.getTreeDocumentId(Uri.parse(tree));
        } catch (Exception ignored) {
            // Keep the URI string.
        }
        folderView.setText(display);
        folderView.setTextColor(Color.parseColor("#222222"));
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
