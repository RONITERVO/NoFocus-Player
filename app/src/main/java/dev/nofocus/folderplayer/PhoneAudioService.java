package dev.nofocus.folderplayer;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.IOException;
import java.net.*;
import java.security.SecureRandom;
import java.util.Arrays;

/** Internal playback only, streamed directly to the paired PC. No microphone or audio focus. */
@android.annotation.TargetApi(29)
public final class PhoneAudioService extends Service {
    static final String STOP = "dev.nofocus.folderplayer.STOP_PHONE_AUDIO", PREFS = "phone_to_pc";
    static volatile boolean running;
    static volatile String message = "Ready to send phone audio to your PC.";
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopping, destroyed;
    private volatile DatagramSocket socket;
    private volatile AudioRecord recorder;
    private MediaProjection projection;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || Build.VERSION.SDK_INT < 29) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) { stop("Stopped."); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        running = true;
        try {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel("phone_audio", "Phone audio to PC", NotificationManager.IMPORTANCE_LOW));
            startForeground(41, notification("Connecting to PC…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            if (CaptureService.running) throw new IOException("Stop the lyrics capture before starting phone audio.");
            Intent consent = intent.getParcelableExtra("consent");
            if (consent == null) throw new IOException("Approve Android sharing to send internal audio.");
            String host = intent.getStringExtra("host"), code = intent.getStringExtra("code");
            if (!PhoneAudioActivity.validAddress(host)) throw new IOException("Enter the PC's IPv4 address.");
            byte[] key = WifiAudioProtocol.keyFromPairingCode(code);
            stopService(new Intent(this, WifiStreamService.class));
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, consent);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stop("Android stopped sharing. Unlock the phone and start again."); }
            }, main);
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoFocus:PhoneAudio");
            wake.acquire(12L * 60 * 60 * 1000);
            WifiManager manager = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if (manager != null) {
                wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NoFocus:PhoneAudio");
                wifi.acquire();
            }
            worker = new Thread(() -> send(host, key), "NoFocus-phone-audio");
            worker.start();
        } catch (Exception error) { stop("Could not start: " + error.getMessage()); }
        return START_NOT_STICKY; // Each session needs fresh, visible Android consent.
    }

    @SuppressLint("MissingPermission") // Activity obtains RECORD_AUDIO before media-projection consent.
    private void send(String host, byte[] key) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        AudioRecord audio = null;
        DatagramSocket connection = null;
        byte[] sessionKey = null;
        try {
            long began = SystemClock.elapsedRealtime();
            connection = new DatagramSocket();
            socket = connection;
            connection.setSoTimeout(100);
            connection.setSendBufferSize(16 * 1024);
            connection.connect(InetAddress.getByName(host), WifiAudioProtocol.PC_RECEIVER_PORT);
            long session = new SecureRandom().nextLong();
            byte[] hello = PhoneAudioProtocol.hello(key, session, Build.MODEL);
            byte[] response = new byte[64];
            long lastHello = 0, confirmed = 0;
            update("Connecting to PC…");
            while (!stopping && confirmed == 0) {
                long now = SystemClock.elapsedRealtime();
                if (now - began > 10_000) throw new IOException("PC did not answer. Update both apps, then check its listening mode and private-network firewall.");
                if (now - lastHello >= 500) { sendPacket(connection, hello); lastHello = now; }
                DatagramPacket reply = new DatagramPacket(response,response.length);
                try {
                    connection.receive(reply);
                    byte[] nonce = PhoneAudioProtocol.challengeNonce(response,reply.getLength(),key,session);
                    if (nonce != null) {
                        if (sessionKey != null) Arrays.fill(sessionKey,(byte)0);
                        sessionKey = PhoneAudioProtocol.sessionKey(key,session,nonce);
                        sendPacket(connection,PhoneAudioProtocol.confirm(sessionKey,session,nonce));
                    } else if (sessionKey != null && PhoneAudioProtocol.isAcknowledgement(response,reply.getLength(),sessionKey,session)) confirmed = now;
                } catch (SocketTimeoutException ignored) { }
            }
            if (stopping) return;
            AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            int minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("48 kHz stereo playback capture is unavailable.");
            audio = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(capture)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum * 2, WifiAudioProtocol.PCM_BYTES * 4)).build();
            recorder = audio;
            if (stopping) return;
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Internal audio capture could not start.");
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Android blocked playback capture.");
            connection.setSoTimeout(1);
            byte[] pcm = new byte[WifiAudioProtocol.PCM_BYTES], previous = null;
            int filled = 0;
            long sequence = 0, lastSound = SystemClock.elapsedRealtime(), nextStatus = 0;
            while (!stopping) {
                int read = audio.read(pcm, filled, pcm.length - filled, AudioRecord.READ_BLOCKING);
                if (read < 0) { if (stopping) break; throw new IOException("Internal audio capture stopped (" + read + ")."); }
                if (read == 0) continue;
                filled += read;
                if (filled != pcm.length) continue;
                filled = 0;
                long now = SystemClock.elapsedRealtime();
                if (now - began >= 12L * 60 * 60 * 1000) throw new IOException("12-hour session finished. Start again to reconnect.");
                for (byte sample : pcm) if (sample != 0) { lastSound = now; break; }
                byte[] datagram = PhoneAudioProtocol.audio(sessionKey, session, sequence++, pcm);
                sendPacket(connection, datagram);
                if (previous != null) sendPacket(connection, previous);
                previous = datagram;
                if (now - lastHello >= 1000) { sendPacket(connection, hello); lastHello = now; }
                // Poll briefly, at most every 100 ms; the recorder supplies the stream clock.
                if (sequence % 20 == 0 && receiveAck(connection, response, sessionKey, session)) confirmed = now;
                if (now - confirmed > 5_000) throw new IOException("PC connection lost. Check Wi-Fi and start again.");
                if (now >= nextStatus) {
                    nextStatus = now + 1000;
                    update(now - lastSound > 5000 ? "Connected · no capturable audio. Play media; some apps block sharing."
                            : "Streaming to PC · 48 kHz stereo · uncompressed PCM");
                }
            }
        } catch (Exception error) {
            if (!stopping) main.post(() -> { if (!destroyed) stop(error.getMessage()); });
        } finally {
            if (audio != null) { try { audio.stop(); } catch (Exception ignored) { } audio.release(); }
            recorder = null;
            if (connection != null) connection.close();
            socket = null;
            Arrays.fill(key, (byte) 0);
            if (sessionKey != null) Arrays.fill(sessionKey,(byte)0);
        }
    }

    private static boolean receiveAck(DatagramSocket connection, byte[] buffer, byte[] key, long session) throws Exception {
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try { connection.receive(packet); return PhoneAudioProtocol.isAcknowledgement(buffer, packet.getLength(), key, session); }
        catch (SocketTimeoutException ignored) { return false; }
    }
    private static void sendPacket(DatagramSocket socket, byte[] bytes) throws IOException {
        socket.send(new DatagramPacket(bytes, bytes.length));
    }
    private void update(String text) {
        main.post(() -> {
            if (destroyed || stopping) return;
            message = text;
            getSystemService(NotificationManager.class).notify(41, notification(text));
        });
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 41, new Intent(this, PhoneAudioActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 41, new Intent(this, PhoneAudioService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "phone_audio").setSmallIcon(R.drawable.ic_stat_music_note)
                .setContentTitle("Phone audio → PC").setContentText(text).setContentIntent(open)
                .setOnlyAlertOnce(true).setOngoing(true).addAction(new Notification.Action.Builder(
                        null, "Stop", stop).build()).build();
    }
    private void stop(String text) {
        if (stopping) return;
        message = text == null ? "Phone audio stopped." : text;
        stopping = true;
        DatagramSocket connection = socket; if (connection != null) connection.close();
        AudioRecord audio = recorder; if (audio != null) { try { audio.stop(); } catch (Exception ignored) { } }
        stopSelf();
    }
    @Override public void onDestroy() {
        if (!stopping) message = "Stopped.";
        destroyed = true; stopping = true;
        DatagramSocket connection = socket; if (connection != null) connection.close();
        AudioRecord audio = recorder; if (audio != null) { try { audio.stop(); } catch (Exception ignored) { } }
        if (worker != null) try { worker.join(1500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (projection != null) { projection.stop(); projection = null; }
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (wake != null && wake.isHeld()) wake.release();
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
