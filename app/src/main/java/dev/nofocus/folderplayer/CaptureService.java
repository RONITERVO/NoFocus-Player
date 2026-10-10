package dev.nofocus.folderplayer;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

@android.annotation.TargetApi(29)
public final class CaptureService extends Service {
    static final String STOP = "dev.nofocus.folderplayer.STOP_CAPTURE", BEGIN = "dev.nofocus.folderplayer.BEGIN_CAPTURE", PREFS = "capture";
    enum Phase { IDLE, PREPARING, READY, COUNTDOWN, STARTING, RECORDING, SAVING, STOPPING }
    static volatile Phase phase = Phase.IDLE;
    private static final int NOTIFICATION = 39;
    static volatile boolean running, recording;
    static volatile String message = "Ready to capture internal audio and lyrics video.";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile PlaybackCapture capture;
    private volatile boolean stopRequested;
    private MediaProjection projection;
    private DownloadRuntime runtime;
    private PowerManager.WakeLock wake;
    private boolean active;
    private volatile boolean destroyed;
    private final CountDownLatch begin = new CountDownLatch(1), cancel = new CountDownLatch(1);
    private CaptureOverlay overlay;
    private CaptureTiming timing;
    private volatile long countdownEnd, recordingStart;
    private final Runnable overlayTick = new Runnable() {
        @Override public void run() {
            if (destroyed || overlay == null) return;
            try { renderOverlay(); }
            catch (RuntimeException error) {
                // Overlay access may be revoked while recording; the notification remains usable.
                removeOverlay();
                if (phase == Phase.READY) requestStop();
            }
            if (overlay != null) main.postDelayed(this, 250);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || Build.VERSION.SDK_INT < 29) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) {
            requestStop();
            if (!active) stopSelf();
            return START_NOT_STICKY;
        }
        if (BEGIN.equals(intent.getAction())) {
            requestBegin();
            if (!active) stopSelf();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        active = true; running = true; recording = false; stopRequested = false; phase = Phase.PREPARING;
        message = "Preparing capture…";
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("capture", "Lyrics capture", NotificationManager.IMPORTANCE_LOW));
        try {
            if (PhoneAudioService.running) throw new IOException("Stop phone audio sharing before capturing a lyrics video.");
            timing = new CaptureTiming(intent.getIntExtra("delay_seconds", 3), intent.getLongExtra("duration_millis", CaptureFiles.MAX_MILLIS));
            startForeground(NOTIFICATION, notification("Preparing capture…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Intent consent = intent.getParcelableExtra("consent");
            if (consent == null) throw new IOException("Approve screen capture to begin.");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, consent);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (phase != Phase.SAVING) requestStop();
                }
            }, main);
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoFocus:Capture");
            wake.acquire(30 * 60 * 1000L);
            runtime = new DownloadRuntime(this);
            if (intent.getBooleanExtra("overlay", false)) {
                if (!android.provider.Settings.canDrawOverlays(this)) throw new IOException("Allow display over other apps, or turn off floating control.");
                overlay = new CaptureOverlay(this, () -> {
                    if (phase == Phase.READY) requestBegin(); else if (phase == Phase.RECORDING) requestStop();
                }, this::requestStop);
                overlay.show(); main.post(overlayTick);
            }
            worker.execute(() -> runCapture(intent));
        } catch (Exception error) { finishCapture("Could not start: " + error.getMessage()); }
        return START_NOT_STICKY;
    }

    private void runCapture(Intent options) {
        File directory = new File(getFilesDir(), "captures/" + UUID.randomUUID());
        String name = "NoFocus-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        try {
            if (!directory.mkdirs()) throw new IOException("Cannot create capture folder.");
            if (directory.getUsableSpace() < 1024L * 1024 * 1024) throw new IOException("Free at least 1 GB before recording.");
            runtime.prepare(this::update);
            checkCancelled();
            if (options.getBooleanExtra("overlay", false)) {
                phase = Phase.READY;
                update("Floating control ready. Open Suno, then tap ▶. Expires in 5 minutes.");
                if (!begin.await(5, TimeUnit.MINUTES)) throw new CancellationException("Floating control expired. Prepare a new capture.");
                checkCancelled();
            }
            countdownEnd = SystemClock.elapsedRealtime() + timing.delaySeconds * 1000L;
            phase = Phase.COUNTDOWN;
            while (SystemClock.elapsedRealtime() < countdownEnd) {
                checkCancelled();
                long left = countdownEnd - SystemClock.elapsedRealtime();
                update("Recording starts in " + ((left + 999) / 1000) + "… Auto-stop after " + CaptureTiming.clock(timing.durationMillis) + ".");
                cancel.await(Math.min(250, Math.max(1, left)), TimeUnit.MILLISECONDS);
            }
            checkCancelled();
            phase = Phase.STARTING; update("Starting recorder…");
            boolean floating = options.getBooleanExtra("floating", true);
            capture = new PlaybackCapture(this, projection, directory, floating);
            if (stopRequested) capture.stopping = true;
            capture.record(options.getIntExtra("width", 720), options.getIntExtra("height", 1280),
                    options.getIntExtra("density", 320), options.getIntExtra("bitrate", 2000000), timing, new PlaybackCapture.Progress() {
                        @Override public void update(String text) { CaptureService.this.update(text); }
                        @Override public void started(long atMillis) { recordingStart = atMillis; recording = true; phase = Phase.RECORDING; }
                    });
            recording = false; phase = Phase.SAVING;
            // Projection is no longer needed during packaging. Keep the existing foreground service alive.
            projection.stop();
            if (stopRequested && capture.videoStartUs < 0) throw new IOException("Capture stopped before video began.");
            File video = new File(directory, "screen.mp4"), pcm = new File(directory, "audio.wav");
            File master = new File(directory, name + ".mkv"), gemini = new File(directory, name + ".mp4");
            update("Saving lossless audio master…");
            convert(CaptureFiles.mux(video, pcm, master, capture.audioStartUs, capture.videoStartUs, true, floating));
            Uri masterUri = publish(master, "video/x-matroska");
            getSharedPreferences(PREFS, 0).edit().putString("master", masterUri.toString()).remove("gemini").apply();
            update("Making Gemini MP4 · original audio master saved…");
            convert(CaptureFiles.mux(video, pcm, gemini, capture.audioStartUs, capture.videoStartUs, false, floating));
            Uri geminiUri = publish(gemini, "video/mp4");
            getSharedPreferences(PREFS, 0).edit().putString("gemini", geminiUri.toString()).apply();
            String done = "Saved to Download/NoFocus. Open the master in Visuals, send MP4 to Gemini, then add the matching JSON."
                    + (capture.timestampAvailable ? "" : " Device audio timestamps unavailable; check lyric sync.");
            getSharedPreferences(PREFS, 0).edit().putString("result", done).apply();
            DownloadRuntime.removeTree(directory);
            finishCapture(done);
        } catch (CancellationException error) {
            try { DownloadRuntime.removeTree(directory); } catch (IOException ignored) { }
            finishCapture(error.getMessage() == null ? "Capture cancelled before recording." : error.getMessage());
        } catch (Exception error) {
            // Preserve interrupted/failed recordings and make raw recovery files accessible outside app storage.
            String recovery = "";
            for (String raw : new String[]{"audio.wav", "screen.mp4"}) {
                File file = new File(directory, raw);
                if (file.length() > 44) try {
                    publish(file, raw.endsWith("wav") ? "audio/wav" : "video/mp4", name + "-recovery-" + raw);
                    recovery = " Raw recovery files saved to Download/NoFocus; these are not aligned masters.";
                } catch (Exception ignored) { recovery = " Raw files retained in app storage."; }
            }
            finishCapture("Capture incomplete: " + error.getMessage() + recovery);
        }
    }

    private void convert(List<String> args) throws Exception {
        StringBuilder diagnostic = new StringBuilder();
        int result = runtime.ffmpeg(args, line -> {
            diagnostic.append(line).append('\n');
            if (diagnostic.length() > 3000) diagnostic.delete(0, diagnostic.length() - 3000);
        });
        if (result != 0) throw new IOException("Could not package capture: " + diagnostic);
    }

    private Uri publish(File file, String mime) throws IOException { return publish(file, mime, file.getName()); }
    private Uri publish(File file, String mime, String name) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NoFocus");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot save recording.");
        try {
            try (InputStream input = new FileInputStream(file); OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IOException("Cannot open recording destination.");
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception error) { getContentResolver().delete(uri, null, null); throw new IOException(error); }
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, CaptureActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, "capture").setSmallIcon(R.drawable.ic_stat_music_note)
                .setContentTitle("NoFocus lyrics capture").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true);
        if (phase == Phase.READY) {
            PendingIntent start = PendingIntent.getService(this, 2, new Intent(this, CaptureService.class).setAction(BEGIN), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "Start countdown", start).build());
        }
        if (phase != Phase.SAVING && phase != Phase.IDLE) builder.addAction(new Notification.Action.Builder(null, recording ? "Stop and save" : "Cancel", stop).build());
        return builder.build();
    }

    private void update(String text) {
        if (destroyed || text.equals(message)) return;
        message = text;
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
    }

    private void finishCapture(String text) {
        if (destroyed) return;
        message = text; recording = false; phase = Phase.IDLE;
        main.post(() -> { removeOverlay(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
    }

    private void checkCancelled() { if (stopRequested) throw new CancellationException("Capture cancelled before recording."); }

    private void requestBegin() {
        if (phase != Phase.READY || stopRequested) return;
        phase = Phase.COUNTDOWN; begin.countDown();
    }

    private void requestStop() {
        if (phase == Phase.SAVING || phase == Phase.IDLE) return;
        stopRequested = true; phase = Phase.STOPPING;
        begin.countDown(); cancel.countDown();
        if (capture != null) capture.stopping = true;
        update(recording ? "Stopping and saving…" : "Cancelling capture…");
    }

    private void renderOverlay() {
        long now = SystemClock.elapsedRealtime();
        String label;
        switch (phase) {
            case READY: label = "Ready\n" + CaptureTiming.clock(timing.durationMillis); break;
            case COUNTDOWN: label = "Starts in\n" + Math.max(0, (countdownEnd - now + 999) / 1000); break;
            case RECORDING: label = "● " + CaptureTiming.clock(now - recordingStart) + "\n" + CaptureTiming.clock(timing.remaining(recordingStart, now) + 999) + " left"; break;
            case SAVING: label = "Saving…"; break;
            case STOPPING: label = "Stopping…"; break;
            default: label = "Preparing…";
        }
        overlay.render(label, phase == Phase.READY, phase == Phase.RECORDING,
                phase == Phase.READY || phase == Phase.COUNTDOWN || phase == Phase.PREPARING || phase == Phase.STARTING);
    }

    private void removeOverlay() {
        main.removeCallbacks(overlayTick);
        if (overlay != null) { try { overlay.close(); } catch (RuntimeException ignored) { } overlay = null; }
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopRequested = true;
        begin.countDown(); cancel.countDown(); removeOverlay();
        if (capture != null) capture.stopping = true;
        if (projection != null) projection.stop();
        if (runtime != null) runtime.cancel();
        if (wake != null && wake.isHeld()) wake.release();
        worker.shutdown();
        running = false; recording = false; phase = Phase.IDLE;
        super.onDestroy();
    }
}
