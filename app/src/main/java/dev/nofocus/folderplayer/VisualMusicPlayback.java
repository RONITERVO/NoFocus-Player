package dev.nofocus.folderplayer;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.*;

/** Float PCM playback, with no audio-focus request and no browser audio resampling. */
public final class VisualMusicPlayback extends Service {
    static volatile VisualMusicPlayback instance;
    static volatile String songId = "", error = "";
    static volatile boolean playing;
    private volatile boolean closed;
    private volatile long requestedFrame = -1, baseFrame;
    private volatile AudioTrack track;
    private int rate = 48000;
    private Thread worker;
    private PowerManager.WakeLock wake;
    static void volume(float value) {
        VisualMusicPlayback service = instance;
        if (service != null && service.track != null) try { service.track.setVolume(Math.max(0, Math.min(1, value))); } catch (IllegalStateException ignored) { }
    }
    static double position() {
        VisualMusicPlayback service = instance; if (service == null) return 0;
        AudioTrack audio = service.track;
        try { return (service.baseFrame + (audio == null ? 0 : Integer.toUnsignedLong(audio.getPlaybackHeadPosition()))) / (double) service.rate; }
        catch (IllegalStateException ignored) { return 0; }
    }
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("visual-music", "Visual music", NotificationManager.IMPORTANCE_LOW));
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoFocus:VisualMusic");
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if ("pause".equals(intent.getAction())) { playing = false; notice(); return START_NOT_STICKY; }
        if ("seek".equals(intent.getAction())) { requestedFrame = Math.max(0, Math.round(intent.getDoubleExtra("seconds", 0) * rate)); return START_NOT_STICKY; }
        try {
            String id = intent.getStringExtra("id");
            if (worker != null && id != null && !id.equals(songId)) { closeWorker(); closed = false; }
            if (worker == null) {
                JSONObject song = new VisualMusicLibrary(this).read(id);
                File pcm = new File(new VisualMusicLibrary(this).song(id), "playback.f32");
                rate = song.getInt("rate"); songId = id; error = ""; baseFrame = 0; requestedFrame = -1;
                playing = "play".equals(intent.getAction()); notice();
                worker = new Thread(() -> playFile(pcm), "visual-music-audio"); worker.start();
            } else if ("play".equals(intent.getAction())) { playing = true; notice(); }
            if (playing) { stopService(new Intent(this, PlayerService.class)); stopService(new Intent(this, WifiStreamService.class)); }
        } catch (Exception e) { error = e.getMessage(); stopSelf(); }
        return START_NOT_STICKY;
    }
    private void playFile(File file) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            int buffer = Math.max(rate * 8 / 5, AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT));
            AudioTrack audio = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build();
            track = audio;
            audio.setVolume(getSharedPreferences(PlayerService.PREFS, 0).getFloat(PlayerService.PREF_VOLUME, 1));
            byte[] bytes = new byte[8192]; float[] samples = new float[2048];
            while (!closed) {
                if (requestedFrame >= 0) {
                    long next = Math.min(file.length() / 8, requestedFrame); requestedFrame = -1;
                    audio.pause(); audio.flush(); input.seek(next * 8); baseFrame = next;
                }
                if (!playing) { if (audio.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) audio.pause(); if (wake.isHeld()) wake.release(); Thread.sleep(20); continue; }
                if (!wake.isHeld()) wake.acquire(21 * 60 * 1000L);
                if (audio.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) audio.play();
                int count = input.read(bytes);
                if (count < 0) {
                    if (position() >= file.length() / (rate * 8.0) - .025) { playing = false; requestedFrame = 0; notice(); }
                    else Thread.sleep(10);
                    continue;
                }
                ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples, 0, count / 4);
                for (int offset = 0; offset < count / 4 && !closed;) {
                    int written = audio.write(samples, offset, count / 4 - offset, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) throw new IOException("Audio output stopped: " + written);
                    offset += written;
                }
            }
        } catch (Exception e) { if (!closed) { error = e.getMessage(); playing = false; new Handler(getMainLooper()).post(this::stopSelf); } }
        finally { AudioTrack audio = track; track = null; if (audio != null) { audio.release(); } if (wake.isHeld()) wake.release(); }
    }
    private void notice() {
        PendingIntent open = PendingIntent.getActivity(this, 60, new Intent(this, VisualMusicActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 61, new Intent(this, VisualMusicPlayback.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "visual-music") : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(R.drawable.ic_stat_music_note).setContentTitle("NoFocus visual music")
                .setContentText(playing ? "Playing • tap for visualization" : "Paused • tap to continue").setContentIntent(open).setOngoing(playing)
                .setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(60, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); else startForeground(60, notification);
    }
    private void closeWorker() {
        closed = true; playing = false;
        AudioTrack audio = track; if (audio != null) try { audio.pause(); audio.flush(); } catch (Exception ignored) { }
        Thread old = worker; if (old != null) try { old.interrupt(); old.join(3000); } catch (InterruptedException ignored) { }
        worker = null;
    }
    @Override public void onDestroy() { closeWorker(); songId = ""; instance = null; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
