package dev.nofocus.folderplayer;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.*;
import java.util.regex.*;

public final class SongDownloadService extends Service {
    static final String CANCEL = "dev.nofocus.folderplayer.CANCEL_DOWNLOAD";
    static final String SAVE = "dev.nofocus.folderplayer.SAVE_SONG";
    static final String PREFS = "song_download";
    private static final int NOTIFICATION = 37;
    private static final String CHANNEL = "song_downloads";
    static final class State {
        final boolean running;
        final String message;
        final String file;
        State(boolean running, String message, String file) { this.running = running; this.message = message; this.file = file; }
    }
    private static volatile State state = new State(false, "", "");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private DownloadRuntime runtime;
    private boolean active;
    private long lastNotification;

    static State current(Context context) {
        State snapshot = state;
        if (!snapshot.running && snapshot.message.isEmpty()) {
            String file = context.getSharedPreferences(PREFS, 0).getString("last_file", "");
            if (!file.isEmpty() && new File(directory(context), file).isFile()) return new State(false, "Ready to save", file);
        }
        return snapshot;
    }

    static File directory(Context context) { return new File(context.getFilesDir(), "songs"); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (CANCEL.equals(intent.getAction())) {
            if (runtime != null) { update("Cancelling…"); runtime.cancel(); }
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        active = true;
        runtime = new DownloadRuntime(this);
        state = new State(true, "Preparing…", "");
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Song downloads", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, notification(state.message));
        if (SAVE.equals(intent.getAction())) {
            worker.execute(() -> save(intent));
            return START_NOT_STICKY;
        }
        String url = intent.getStringExtra("url");
        String format = intent.getStringExtra("format");
        worker.execute(() -> download(url, format));
        return START_NOT_STICKY;
    }

    private void save(Intent intent) {
        String file = intent.getStringExtra("file");
        if (file == null) file = "";
        try {
            update("Saving file…");
            try (InputStream input = getContentResolver().openInputStream(SongFileProvider.uri(this, file));
                 OutputStream output = getContentResolver().openOutputStream(intent.getData(), "w")) {
                if (input == null || output == null) throw new IOException("Could not open the save location.");
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) { runtime.checkCancelled(); output.write(buffer, 0, count); }
            }
            state = new State(false, "File saved", file);
        } catch (Exception error) {
            // ACTION_CREATE_DOCUMENT created this output; remove an incomplete copy.
            try { android.provider.DocumentsContract.deleteDocument(getContentResolver(), intent.getData()); } catch (Exception ignored) { }
            state = new State(false, error instanceof CancellationException ? "Save cancelled" : "Could not save. Try another folder.", file);
        } finally { stopForeground(true); stopSelf(); }
    }

    private void download(String input, String format) {
        File job = new File(getCacheDir(), "song-download");
        PowerManager.WakeLock wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "NoFocus:SongDownload");
        try {
            String url = SongDownloadSpec.videoUrl(input);
            SongDownloadSpec.arguments(url, format, "unused");
            wake.acquire(3 * 60 * 60 * 1000L);
            DownloadRuntime.removeTree(job);
            if (!job.mkdirs()) throw new IOException("Not enough storage for a download.");
            runtime.prepare(this::update);
            update("Finding audio…");
            StringBuilder errors = new StringBuilder();
            int exit = runtime.run(SongDownloadSpec.arguments(url, format, new File(job, "track.%(ext)s").getAbsolutePath()), line -> {
                if (line.startsWith("[download]")) {
                    Matcher percent = Pattern.compile("[0-9]+(?:\\.[0-9]+)?%").matcher(line);
                    update(percent.find() ? "Downloading " + percent.group() : "Downloading audio…");
                } else if (line.startsWith("[ExtractAudio]")) update("Preparing " + format.toUpperCase(java.util.Locale.ROOT) + "…");
                else {
                    errors.append(line).append('\n');
                    if (errors.length() > 16000) errors.delete(0, errors.length() - 12000);
                }
            });
            runtime.checkCancelled();
            if (exit != 0) throw new IOException(SongDownloadSpec.friendlyError(errors.toString()));
            File audio = new File(job, "track." + format);
            if (!audio.isFile() || audio.length() == 0) throw new IOException("No audio saved. Try a song under two hours.");
            String title = "YouTube audio";
            File info = new File(job, "track.info.json");
            if (info.isFile()) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                try (InputStream in = new FileInputStream(info)) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) != -1) { data.write(buffer, 0, count); if (data.size() > 8000000) break; }
                }
                try { title = new JSONObject(data.toString("UTF-8")).optString("title", title); } catch (Exception ignored) { }
            }
            File folder = directory(this);
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not save the song.");
            String name = SongDownloadSpec.safeName(title) + " [" + url.substring(url.length() - 11) + "]";
            File target = new File(folder, name + "." + format);
            for (int copy = 1; target.exists(); copy++) target = new File(folder, name + " (" + copy + ")." + format);
            runtime.checkCancelled();
            if (!audio.renameTo(target)) throw new IOException("Could not save the song. Check free storage.");
            getSharedPreferences(PREFS, 0).edit().putString("last_file", target.getName()).apply();
            state = new State(false, "Ready to save", target.getName());
            // Only the latest export is kept privately. Saved files in the user's folders are untouched.
            File[] previous = folder.listFiles();
            if (previous != null) for (File old : previous) if (!old.equals(target) && old.isFile()) old.delete();
        } catch (CancellationException error) { state = new State(false, "Cancelled", ""); }
        catch (Exception error) { state = new State(false, error.getMessage() == null ? "Download failed. Try again." : error.getMessage(), ""); }
        finally {
            try { DownloadRuntime.removeTree(job); } catch (IOException ignored) { }
            if (wake.isHeld()) wake.release();
            stopForeground(true);
            stopSelf();
        }
    }

    private void update(String message) {
        state = new State(true, message, "");
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotification >= 1000) {
            lastNotification = now;
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message));
        }
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SongDownloadActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 0, new Intent(this, SongDownloadService.class).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setContentTitle("Download song").setContentText(message).setSmallIcon(R.drawable.ic_stat_music_note)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Cancel", cancel).build()).build();
    }

    @Override public void onTimeout(int startId, int fgsType) { if (runtime != null) runtime.cancel(); stopSelf(); }

    @Override public void onDestroy() {
        if (runtime != null) runtime.cancel();
        worker.shutdown();
        super.onDestroy();
    }
}
