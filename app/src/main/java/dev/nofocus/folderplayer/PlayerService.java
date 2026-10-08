package dev.nofocus.folderplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerService extends Service {
    public static final String PREFS = "nofocus_player_prefs";
    public static final String PREF_TREE_URI = "tree_uri";
    public static final String PREF_SHUFFLE = "shuffle";
    public static final String PREF_VOLUME = "volume";

    public static final String ACTION_PLAY = "dev.nofocus.folderplayer.action.PLAY";
    public static final String ACTION_TOGGLE_PLAY_PAUSE = "dev.nofocus.folderplayer.action.TOGGLE_PLAY_PAUSE";
    public static final String ACTION_NEXT = "dev.nofocus.folderplayer.action.NEXT";
    public static final String ACTION_PREVIOUS = "dev.nofocus.folderplayer.action.PREVIOUS";
    public static final String ACTION_STOP = "dev.nofocus.folderplayer.action.STOP";
    public static final String ACTION_SET_VOLUME = "dev.nofocus.folderplayer.action.SET_VOLUME";
    public static final String ACTION_SET_SHUFFLE = "dev.nofocus.folderplayer.action.SET_SHUFFLE";
    public static final String ACTION_STATE = "dev.nofocus.folderplayer.action.STATE";

    public static final String EXTRA_TREE_URI = "extra_tree_uri";
    public static final String EXTRA_VOLUME = "extra_volume";
    public static final String EXTRA_SHUFFLE = "extra_shuffle";
    public static final String EXTRA_STATE = "extra_state";
    public static final String EXTRA_TRACK_NAME = "extra_track_name";
    public static final String EXTRA_TRACK_COUNT = "extra_track_count";
    public static final String EXTRA_TRACK_INDEX = "extra_track_index";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";
    public static final String EXTRA_PREPARING = "extra_preparing";
    public static final String EXTRA_ACTIVE = "extra_active";

    private static volatile Intent lastState;

    static Intent currentState() {
        Intent state = lastState;
        return state == null ? null : new Intent(state);
    }

    private static final String CHANNEL_ID = "nofocus_playback";
    private static final int NOTIFICATION_ID = 42;
    private static final int MAX_RECURSION_DEPTH = 48;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final ArrayList<Track> playlist = new ArrayList<>();

    private SharedPreferences prefs;
    private MediaPlayer player;
    private boolean foregroundStarted = false;
    private boolean preparing = false;
    private boolean shuffle = false;
    private float volume = 1.0f;
    private int currentIndex = -1;
    private int playGeneration = 0;
    private int consecutiveErrors = 0;
    private String status = "Idle";
    private String currentTrackName = null;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        shuffle = prefs.getBoolean(PREF_SHUFFLE, false);
        volume = prefs.getFloat(PREF_VOLUME, 1.0f);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            String savedTree = prefs.getString(PREF_TREE_URI, null);
            if (savedTree != null) {
                ensureForeground("Restoring playback...");
                scanAndPlay(savedTree);
                return START_STICKY;
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        applySettingsFromIntent(intent);
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }

        ensureForeground(status);

        if (ACTION_PLAY.equals(action)) {
            stopService(new Intent(this, VisualMusicPlayback.class));
            stopService(new Intent(this, WifiStreamService.class));
            String tree = intent.getStringExtra(EXTRA_TREE_URI);
            if (tree == null) {
                tree = prefs.getString(PREF_TREE_URI, null);
            }
            scanAndPlay(tree);
        } else if (ACTION_TOGGLE_PLAY_PAUSE.equals(action)) {
            togglePlayPause();
        } else if (ACTION_NEXT.equals(action)) {
            nextTrack(true);
        } else if (ACTION_PREVIOUS.equals(action)) {
            previousTrack();
        } else if (ACTION_SET_VOLUME.equals(action)) {
            setPlayerVolume(volume);
            setStatusWithoutChangingTrack("Volume set to " + Math.round(volume * 100f) + "%");
        } else if (ACTION_SET_SHUFFLE.equals(action)) {
            setStatusWithoutChangingTrack(shuffle ? "Shuffle on" : "Shuffle off");
        } else {
            setStatusWithoutChangingTrack("Ready");
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        releasePlayerOnly();
        scanner.shutdownNow();
        playlist.clear();
        currentIndex = -1;
        currentTrackName = null;
        status = "Stopped";
        broadcastState();
        super.onDestroy();
    }

    private void applySettingsFromIntent(Intent intent) {
        if (intent.hasExtra(EXTRA_TREE_URI)) {
            String tree = intent.getStringExtra(EXTRA_TREE_URI);
            if (tree != null) {
                prefs.edit().putString(PREF_TREE_URI, tree).apply();
            }
        }
        if (intent.hasExtra(EXTRA_SHUFFLE)) {
            shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, shuffle);
            prefs.edit().putBoolean(PREF_SHUFFLE, shuffle).apply();
        }
        if (intent.hasExtra(EXTRA_VOLUME)) {
            volume = Math.max(0f, Math.min(1f, intent.getFloatExtra(EXTRA_VOLUME, volume)));
            prefs.edit().putFloat(PREF_VOLUME, volume).apply();
        }
    }

    private void scanAndPlay(String tree) {
        if (tree == null || tree.trim().isEmpty()) {
            fail("No folder selected.");
            return;
        }

        final Uri treeUri;
        try {
            treeUri = Uri.parse(tree);
        } catch (Exception e) {
            fail("Bad folder URI: " + e.getMessage());
            return;
        }

        preparing = true;
        status = "Scanning selected folder...";
        currentTrackName = null;
        updateNotificationAndBroadcast();

        scanner.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Track> found = new ArrayList<>();
                String error = null;
                try {
                    String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
                    scanChildren(treeUri, rootDocumentId, found, 0);
                    Collections.sort(found, new Comparator<Track>() {
                        @Override
                        public int compare(Track left, Track right) {
                            return left.sortKey.compareToIgnoreCase(right.sortKey);
                        }
                    });
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                final String finalError = error;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        preparing = false;
                        playlist.clear();
                        playlist.addAll(found);
                        consecutiveErrors = 0;
                        if (finalError != null) {
                            fail("Folder scan failed: " + finalError);
                            return;
                        }
                        if (playlist.isEmpty()) {
                            fail("No supported audio files found in that folder.");
                            return;
                        }
                        currentIndex = shuffle ? random.nextInt(playlist.size()) : 0;
                        playIndex(currentIndex);
                    }
                });
            }
        });
    }

    private void scanChildren(Uri treeUri, String parentDocumentId, List<Track> out, int depth) {
        if (depth > MAX_RECURSION_DEPTH) {
            return;
        }

        ContentResolver resolver = getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri, projection, null, null, null);
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);

            while (cursor.moveToNext()) {
                String documentId = idColumn >= 0 ? cursor.getString(idColumn) : null;
                if (documentId == null) {
                    continue;
                }
                String name = nameColumn >= 0 ? cursor.getString(nameColumn) : documentId;
                String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : null;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanChildren(treeUri, documentId, out, depth + 1);
                } else if (isSupportedAudio(name, mime)) {
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    out.add(new Track(documentUri, name == null ? documentId : name, documentId));
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private boolean isSupportedAudio(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.US).startsWith("audio/")) {
            return true;
        }
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp3")
                || lower.endsWith(".m4a")
                || lower.endsWith(".aac")
                || lower.endsWith(".flac")
                || lower.endsWith(".ogg")
                || lower.endsWith(".oga")
                || lower.endsWith(".opus")
                || lower.endsWith(".webm")
                || lower.endsWith(".wav")
                || lower.endsWith(".3gp")
                || lower.endsWith(".amr")
                || lower.endsWith(".mid")
                || lower.endsWith(".midi");
    }

    private void playIndex(int index) {
        stopService(new Intent(this, VisualMusicPlayback.class));
        if (playlist.isEmpty()) {
            fail("Playlist is empty.");
            return;
        }
        if (index < 0 || index >= playlist.size()) {
            index = 0;
        }
        currentIndex = index;
        final Track track = playlist.get(currentIndex);

        releasePlayerOnly();
        preparing = true;
        currentTrackName = track.name;
        status = "Loading: " + track.name;
        updateNotificationAndBroadcast();

        final int generation = ++playGeneration;
        final MediaPlayer mp = new MediaPlayer();
        player = mp;

        try {
            // Deliberately no AudioManager.requestAudioFocus() call anywhere in this app.
            // YouTube can take audio focus; this player simply keeps its own local MediaPlayer running.
            if (Build.VERSION.SDK_INT >= 21) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
            } else {
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setVolume(volume, volume);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer preparedPlayer) {
                    if (generation != playGeneration || preparedPlayer != player) {
                        safeRelease(preparedPlayer);
                        return;
                    }
                    preparing = false;
                    consecutiveErrors = 0;
                    try {
                        preparedPlayer.setVolume(volume, volume);
                        preparedPlayer.start();
                        status = "Playing: " + track.name;
                    } catch (IllegalStateException e) {
                        status = "Start failed: " + e.getMessage();
                    }
                    updateNotificationAndBroadcast();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer completedPlayer) {
                    nextTrack(false);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer errorPlayer, int what, int extra) {
                    consecutiveErrors++;
                    status = "Playback error on " + track.name + " (what=" + what + ", extra=" + extra + ")";
                    updateNotificationAndBroadcast();
                    if (consecutiveErrors >= Math.max(1, playlist.size())) {
                        fail("All tracks failed to play. Check file format or folder access.");
                    } else {
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                nextTrack(false);
                            }
                        }, 500);
                    }
                    return true;
                }
            });
            mp.setDataSource(this, track.uri);
            mp.prepareAsync();
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException e) {
            consecutiveErrors++;
            status = "Could not open " + track.name + ": " + e.getMessage();
            updateNotificationAndBroadcast();
            if (consecutiveErrors >= Math.max(1, playlist.size())) {
                fail("All tracks failed to open. Check file permissions and formats.");
            } else {
                nextTrack(false);
            }
        }
    }

    private void togglePlayPause() {
        if (player != null && isActuallyPlaying()) {
            try {
                player.pause();
                preparing = false;
                status = currentTrackName == null ? "Paused" : "Paused: " + currentTrackName;
            } catch (IllegalStateException e) {
                status = "Pause failed: " + e.getMessage();
            }
            updateNotificationAndBroadcast();
            return;
        }

        if (player != null && !preparing) {
            try {
                player.setVolume(volume, volume);
                player.start();
                status = currentTrackName == null ? "Playing" : "Playing: " + currentTrackName;
            } catch (IllegalStateException e) {
                status = "Resume failed: " + e.getMessage();
            }
            updateNotificationAndBroadcast();
            return;
        }

        if (!playlist.isEmpty()) {
            playIndex(currentIndex >= 0 ? currentIndex : 0);
            return;
        }

        String tree = prefs.getString(PREF_TREE_URI, null);
        scanAndPlay(tree);
    }

    private void nextTrack(boolean userInitiated) {
        if (playlist.isEmpty()) {
            fail("No playlist loaded.");
            return;
        }
        int next;
        if (shuffle && playlist.size() > 1) {
            do {
                next = random.nextInt(playlist.size());
            } while (next == currentIndex);
        } else {
            next = (currentIndex + 1) % playlist.size();
        }
        if (userInitiated) {
            consecutiveErrors = 0;
        }
        playIndex(next);
    }

    private void previousTrack() {
        if (playlist.isEmpty()) {
            fail("No playlist loaded.");
            return;
        }
        if (player != null) {
            try {
                if (player.getCurrentPosition() > 5000) {
                    player.seekTo(0);
                    status = currentTrackName == null ? "Restarted track" : "Restarted: " + currentTrackName;
                    updateNotificationAndBroadcast();
                    return;
                }
            } catch (IllegalStateException ignored) {
                // Fall through and move to previous track.
            }
        }
        consecutiveErrors = 0;
        int previous = currentIndex <= 0 ? playlist.size() - 1 : currentIndex - 1;
        playIndex(previous);
    }

    private void setPlayerVolume(float newVolume) {
        volume = Math.max(0f, Math.min(1f, newVolume));
        if (player != null) {
            try {
                player.setVolume(volume, volume);
            } catch (IllegalStateException ignored) {
                // Keep stored volume; it will apply to the next player instance.
            }
        }
    }

    private boolean isActuallyPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void fail(String message) {
        preparing = false;
        status = message == null ? "Error" : message;
        updateNotificationAndBroadcast();
    }

    private void setStatusWithoutChangingTrack(String message) {
        status = message == null ? "Ready" : message;
        updateNotificationAndBroadcast();
    }

    private void releasePlayerOnly() {
        playGeneration++;
        preparing = false;
        if (player != null) {
            MediaPlayer old = player;
            player = null;
            safeRelease(old);
        }
    }

    private void safeRelease(MediaPlayer mediaPlayer) {
        if (mediaPlayer == null) {
            return;
        }
        try {
            mediaPlayer.setOnPreparedListener(null);
            mediaPlayer.setOnCompletionListener(null);
            mediaPlayer.setOnErrorListener(null);
            mediaPlayer.reset();
        } catch (Exception ignored) {
            // Release anyway.
        }
        try {
            mediaPlayer.release();
        } catch (Exception ignored) {
            // Already released.
        }
    }

    private void stopEverything() {
        releasePlayerOnly();
        playlist.clear();
        currentIndex = -1;
        currentTrackName = null;
        status = "Stopped";
        broadcastState();
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            foregroundStarted = false;
        }
        stopSelf();
    }

    private void updateNotificationAndBroadcast() {
        ensureForeground(status);
        broadcastState();
    }

    private void ensureForeground(String text) {
        Notification notification = buildNotification(text == null ? "Ready" : text);
        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
        } else {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 10, openIntent, pendingIntentFlags());

        boolean playing = isActuallyPlaying();
        String playPauseText = playing ? "Pause" : "Play";
        int playPauseIcon = playing ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24;

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(R.drawable.ic_stat_music_note)
                .setContentTitle("NoFocus Folder Player")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setOngoing(playing || preparing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(R.drawable.ic_skip_previous_24, "Previous", servicePendingIntent(ACTION_PREVIOUS, 21))
                .addAction(playPauseIcon, playPauseText, servicePendingIntent(ACTION_TOGGLE_PLAY_PAUSE, 22))
                .addAction(R.drawable.ic_skip_next_24, "Next", servicePendingIntent(ACTION_NEXT, 23))
                .addAction(R.drawable.ic_stop_24, "Stop", servicePendingIntent(ACTION_STOP, 24));

        if (Build.VERSION.SDK_INT >= 21) {
            builder.setCategory(Notification.CATEGORY_SERVICE);
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return builder.build();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlayerService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags());
    }

    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "NoFocus playback",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Local folder playback that does not request audio focus.");
        channel.setShowBadge(false);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void broadcastState() {
        Intent state = new Intent(ACTION_STATE);
        state.setPackage(getPackageName());
        state.putExtra(EXTRA_STATE, status);
        state.putExtra(EXTRA_TRACK_NAME, currentTrackName);
        state.putExtra(EXTRA_TRACK_COUNT, playlist.size());
        state.putExtra(EXTRA_TRACK_INDEX, currentIndex);
        state.putExtra(EXTRA_IS_PLAYING, isActuallyPlaying());
        state.putExtra(EXTRA_PREPARING, preparing);
        state.putExtra(EXTRA_ACTIVE, !"Stopped".equals(status));
        lastState = new Intent(state);
        sendBroadcast(state);
    }

    private static final class Track {
        final Uri uri;
        final String name;
        final String sortKey;

        Track(Uri uri, String name, String sortKey) {
            this.uri = uri;
            this.name = name;
            this.sortKey = sortKey == null ? name : sortKey;
        }
    }
}
