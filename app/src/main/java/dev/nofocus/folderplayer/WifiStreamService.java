package dev.nofocus.folderplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Receives encrypted PCM over the LAN and deliberately never requests audio focus. */
public class WifiStreamService extends Service {
    public static final String ACTION_START = "dev.nofocus.folderplayer.wifi.START";
    public static final String ACTION_STOP = "dev.nofocus.folderplayer.wifi.STOP";
    public static final String ACTION_SET_VOLUME = "dev.nofocus.folderplayer.wifi.SET_VOLUME";
    public static final String ACTION_STATE = "dev.nofocus.folderplayer.wifi.STATE";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_SENDER = "sender";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_BUFFERED_MS = "buffered_ms";
    public static final String EXTRA_LOST = "lost";
    public static final String EXTRA_TRIMMED = "trimmed";
    public static final String PREF_PAIRING_CODE = "wifi_pairing_code";
    public static final String PREF_LATENCY_PACKETS = "wifi_latency_packets";
    public static final String PREF_STREAM_VOLUME = "wifi_stream_volume";

    private static final String CHANNEL_ID = "nofocus_wifi_stream";
    private static final int NOTIFICATION_ID = 43;
    private static final int BUFFER_CAPACITY_PACKETS = 64;
    private static final long SESSION_TIMEOUT_NS = 3_000_000_000L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AudioJitterBuffer jitterBuffer = new AudioJitterBuffer(BUFFER_CAPACITY_PACKETS);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService workers;
    private SharedPreferences prefs;
    private DatagramSocket socket;
    private AudioTrack audioTrack;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;
    private byte[] encryptionKey;
    private volatile long activeSession = Long.MIN_VALUE;
    private volatile InetAddress activeAddress;
    private volatile int framesPerPacket = 240;
    private volatile long lastPacketNs;
    private volatile String senderName = "";
    private volatile float volume = 1f;
    private volatile long receivedPackets;
    private volatile long missingPackets;
    private volatile long trimmedPackets;
    private volatile boolean playbackStarted;
    private volatile int audioBufferFrames = 480;
    private volatile int minimumAudioBufferFrames = 480;
    private volatile int lastUnderrunCount;
    private volatile int stableBufferSeconds;
    private volatile String status = "Stopped";
    private static volatile Intent lastState;

    static Intent currentState() {
        Intent state = lastState;
        return state == null ? null : new Intent(state);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PlayerService.PREFS, MODE_PRIVATE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopStreaming();
            return START_NOT_STICKY;
        }
        if (ACTION_SET_VOLUME.equals(action)) {
            volume = clampVolume(intent == null ? volume : intent.getFloatExtra("volume", volume));
            prefs.edit().putFloat(PREF_STREAM_VOLUME, volume).apply();
            if (!running.get()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            applyVolume();
            publishState();
            return START_STICKY;
        }
        startStreaming();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopStreamingResources();
        status = "Stopped";
        broadcastState();
        super.onDestroy();
    }

    private synchronized void startStreaming() {
        if (running.get()) {
            publishState();
            return;
        }
        String pairingCode = prefs.getString(PREF_PAIRING_CODE, null);
        try {
            encryptionKey = WifiAudioProtocol.keyFromPairingCode(pairingCode);
        } catch (GeneralSecurityException e) {
            status = "Pairing code is invalid. Rotate it in the app.";
            ensureForeground(status);
            publishState();
            return;
        }

        volume = clampVolume(prefs.getFloat(PREF_STREAM_VOLUME, 1f));
        stopService(new Intent(this, PlayerService.class));
        running.set(true);
        status = "Waiting for encrypted PC stream on UDP " + WifiAudioProtocol.PORT;
        ensureForeground(status);
        acquireLocks();
        workers = Executors.newFixedThreadPool(2);
        workers.execute(this::receiveLoop);
        workers.execute(this::playbackLoop);
        publishState();
    }

    private void receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        byte[] receiveBuffer = new byte[WifiAudioProtocol.MAX_DATAGRAM_SIZE];
        try {
            DatagramSocket localSocket = new DatagramSocket(null);
            localSocket.setReuseAddress(false);
            localSocket.setReceiveBufferSize(256 * 1024);
            localSocket.setSoTimeout(1000);
            localSocket.bind(new InetSocketAddress(WifiAudioProtocol.PORT));
            socket = localSocket;

            while (running.get()) {
                DatagramPacket datagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                try {
                    localSocket.receive(datagram);
                    handleDatagram(datagram);
                } catch (SocketTimeoutException timeout) {
                    if (activeAddress != null && System.nanoTime() - lastPacketNs > SESSION_TIMEOUT_NS) {
                        resetSession("Stream timed out; waiting for the PC");
                    }
                } catch (GeneralSecurityException ignored) {
                    // Unauthenticated or corrupt LAN traffic is intentionally ignored.
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                status = "Wi-Fi receiver failed: " + safeMessage(e);
                publishState();
            }
        } finally {
            DatagramSocket old = socket;
            socket = null;
            if (old != null) {
                old.close();
            }
        }
    }

    private void handleDatagram(DatagramPacket datagram) throws GeneralSecurityException {
        byte[] data = datagram.getData();
        int length = datagram.getLength();
        if (WifiAudioProtocol.isDiscoveryRequest(data, length)) {
            DatagramSocket activeSocket = socket;
            if (activeSocket != null) {
                byte[] response = WifiAudioProtocol.discoveryResponse();
                try {
                    activeSocket.send(new DatagramPacket(response, response.length,
                            datagram.getAddress(), datagram.getPort()));
                } catch (Exception ignored) {
                    // Discovery is optional; manual IP entry remains available.
                }
            }
            return;
        }
        if (length == WifiAudioProtocol.HELLO_SIZE) {
            WifiAudioProtocol.Hello hello = WifiAudioProtocol.parseHello(data, length, encryptionKey);
            sendHelloAcknowledgement(datagram, hello.sessionId);
            boolean newSession = hello.sessionId != activeSession || !datagram.getAddress().equals(activeAddress);
            if (newSession) {
                activeSession = hello.sessionId;
                activeAddress = datagram.getAddress();
                framesPerPacket = hello.framesPerPacket;
                senderName = hello.senderName;
                receivedPackets = 0;
                missingPackets = 0;
                trimmedPackets = 0;
                playbackStarted = false;
                jitterBuffer.reset();
                flushAudioTrack();
                status = "Connected to " + senderName;
                publishState();
            }
            lastPacketNs = System.nanoTime();
            return;
        }

        WifiAudioProtocol.AudioPacket packet = WifiAudioProtocol.decryptAudio(data, length, encryptionKey);
        if (packet.sessionId != activeSession || activeAddress == null || !datagram.getAddress().equals(activeAddress)
                || packet.frameCount != framesPerPacket) {
            return;
        }
        if (jitterBuffer.offer(packet.sequence, packet.pcm)) {
            receivedPackets++;
            lastPacketNs = System.nanoTime();
        }
    }

    private void sendHelloAcknowledgement(DatagramPacket helloPacket, long sessionId) {
        DatagramSocket activeSocket = socket;
        if (activeSocket == null) {
            return;
        }
        try {
            byte[] acknowledgement = WifiAudioProtocol.helloAcknowledgement(sessionId, encryptionKey);
            activeSocket.send(new DatagramPacket(acknowledgement, acknowledgement.length,
                    helloPacket.getAddress(), helloPacket.getPort()));
        } catch (Exception ignored) {
            // The sender repeats hello packets, so a lost acknowledgement is harmless.
        }
    }

    private void playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            audioTrack = createAudioTrack();
            applyVolume();
            byte[] silence = new byte[960 * WifiAudioProtocol.CHANNELS * WifiAudioProtocol.BYTES_PER_SAMPLE];
            long lastUpdateNs = 0;
            while (running.get()) {
                int targetPackets = latencyPackets();
                if (activeAddress == null || jitterBuffer.size() < (playbackStarted ? 1 : targetPackets)) {
                    sleepQuietly(1);
                    continue;
                }
                if (!playbackStarted) {
                    int prefillPackets = Math.max(1, Math.min(targetPackets,
                            (audioBufferFrames + framesPerPacket - 1) / framesPerPacket));
                    for (int i = 0; i < prefillPackets; i++) {
                        byte[] initialPcm = jitterBuffer.takeNext();
                        if (initialPcm == null) {
                            initialPcm = silence;
                        }
                        writeFully(audioTrack, initialPcm,
                                framesPerPacket * WifiAudioProtocol.CHANNELS * WifiAudioProtocol.BYTES_PER_SAMPLE);
                    }
                    audioTrack.play();
                    playbackStarted = true;
                    continue;
                }

                int excess = jitterBuffer.size() - (targetPackets + 2);
                if (excess > 0) {
                    trimmedPackets += jitterBuffer.discardOldest(jitterBuffer.size() - targetPackets);
                }
                byte[] pcm = jitterBuffer.takeNext();
                if (pcm == null) {
                    int bytes = framesPerPacket * WifiAudioProtocol.CHANNELS * WifiAudioProtocol.BYTES_PER_SAMPLE;
                    writeFully(audioTrack, silence, bytes);
                    missingPackets++;
                } else {
                    writeFully(audioTrack, pcm, pcm.length);
                }

                long now = System.nanoTime();
                if (now - lastUpdateNs > 1_000_000_000L) {
                    lastUpdateNs = now;
                    tuneAudioBuffer();
                    status = String.format(Locale.US, "Streaming from %s · buffer %d ms · gaps %d",
                            senderName, bufferedMilliseconds(), missingPackets);
                    postState();
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                status = "Audio output failed: " + safeMessage(e);
                publishState();
            }
        } finally {
            releaseAudioTrack();
        }
    }

    private AudioTrack createAudioTrack() {
        int channelMask = AudioFormat.CHANNEL_OUT_STEREO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minimum = AudioTrack.getMinBufferSize(WifiAudioProtocol.SAMPLE_RATE, channelMask, encoding);
        int packetBytes = 240 * WifiAudioProtocol.CHANNELS * WifiAudioProtocol.BYTES_PER_SAMPLE;
        int bufferBytes = Math.max(minimum, packetBytes * 4);
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(WifiAudioProtocol.SAMPLE_RATE)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .build();
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioTrack.Builder builder = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes);
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        AudioTrack track = builder.build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("48 kHz stereo output is unavailable");
        }
        if (Build.VERSION.SDK_INT >= 24) {
            int profilePackets = latencyPackets();
            // This Honor device and many Android fast mixers underrun below 480 frames (10 ms).
            // Reliable mode deliberately doubles the device buffer; Ultra trims only network jitter.
            minimumAudioBufferFrames = profilePackets >= 8 ? 960 : 480;
            audioBufferFrames = track.setBufferSizeInFrames(minimumAudioBufferFrames);
            lastUnderrunCount = track.getUnderrunCount();
        }
        return track;
    }

    private void tuneAudioBuffer() {
        AudioTrack track = audioTrack;
        if (track == null || Build.VERSION.SDK_INT < 24) {
            return;
        }
        int underruns = track.getUnderrunCount();
        if (underruns > lastUnderrunCount) {
            int capacity = track.getBufferCapacityInFrames();
            int requested = Math.min(capacity, audioBufferFrames + framesPerPacket);
            int actual = track.setBufferSizeInFrames(requested);
            if (actual > 0) {
                audioBufferFrames = actual;
            }
            stableBufferSeconds = 0;
        } else if (audioBufferFrames > minimumAudioBufferFrames && ++stableBufferSeconds >= 10) {
            int actual = track.setBufferSizeInFrames(
                    Math.max(minimumAudioBufferFrames, audioBufferFrames - framesPerPacket));
            if (actual > 0) {
                audioBufferFrames = actual;
            }
            stableBufferSeconds = 0;
        }
        lastUnderrunCount = underruns;
    }

    private static void writeFully(AudioTrack track, byte[] data, int length) {
        int offset = 0;
        while (offset < length) {
            int written = track.write(data, offset, length - offset, AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException("AudioTrack write failed: " + written);
            }
            offset += written;
        }
    }

    private synchronized void resetSession(String newStatus) {
        activeSession = Long.MIN_VALUE;
        activeAddress = null;
        senderName = "";
        playbackStarted = false;
        jitterBuffer.reset();
        flushAudioTrack();
        status = newStatus;
        publishState();
    }

    private void flushAudioTrack() {
        AudioTrack track = audioTrack;
        if (track == null) {
            return;
        }
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException ignored) {
            // The playback thread may be creating or releasing it.
        }
    }

    private synchronized void stopStreaming() {
        status = "Stopped";
        publishState();
        stopStreamingResources();
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private synchronized void stopStreamingResources() {
        running.set(false);
        DatagramSocket oldSocket = socket;
        socket = null;
        if (oldSocket != null) {
            oldSocket.close();
        }
        ExecutorService oldWorkers = workers;
        workers = null;
        if (oldWorkers != null) {
            oldWorkers.shutdownNow();
        }
        releaseAudioTrack();
        releaseLocks();
        activeAddress = null;
        activeSession = Long.MIN_VALUE;
        jitterBuffer.reset();
    }

    private synchronized void releaseAudioTrack() {
        AudioTrack old = audioTrack;
        audioTrack = null;
        if (old != null) {
            try {
                old.pause();
                old.flush();
            } catch (Exception ignored) {
            }
            old.release();
        }
    }

    private void acquireLocks() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoFocus:WifiAudio");
            wakeLock.setReferenceCounted(false);
            // Refreshed by each explicit receiver start; bounds leaks even if an OEM skips service teardown.
            wakeLock.acquire(12L * 60L * 60L * 1000L);
        }
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NoFocus:LowLatencyWifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
            multicastLock = wifiManager.createMulticastLock("NoFocus:Discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wifiLock = null;
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        multicastLock = null;
    }

    private void applyVolume() {
        AudioTrack track = audioTrack;
        if (track != null) {
            track.setVolume(volume);
        }
    }

    private int latencyPackets() {
        return Math.max(2, Math.min(12, prefs.getInt(PREF_LATENCY_PACKETS, 4)));
    }

    private int bufferedMilliseconds() {
        return Math.round(jitterBuffer.size() * framesPerPacket * 1000f / WifiAudioProtocol.SAMPLE_RATE);
    }

    private void publishState() {
        ensureForeground(status);
        broadcastState();
    }

    private void broadcastState() {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra(EXTRA_STATUS, status);
        state.putExtra(EXTRA_SENDER, senderName);
        state.putExtra(EXTRA_CONNECTED, activeAddress != null);
        state.putExtra(EXTRA_RUNNING, running.get() && !"Stopped".equals(status));
        state.putExtra(EXTRA_BUFFERED_MS, bufferedMilliseconds());
        state.putExtra(EXTRA_LOST, missingPackets + jitterBuffer.getRejectedPackets());
        state.putExtra(EXTRA_TRIMMED, trimmedPackets);
        lastState = new Intent(state);
        sendBroadcast(state);
    }

    private void postState() {
        mainHandler.post(() -> {
            if (running.get()) {
                publishState();
            }
        });
    }

    private void ensureForeground(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 40, open, pendingIntentFlags());
        Intent stop = new Intent(this, WifiStreamService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 41, stop, pendingIntentFlags());
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_stat_music_note)
                .setContentTitle("NoFocus Wi-Fi speaker")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openPending)
                .setOngoing(running.get())
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(R.drawable.ic_stop_24, "Stop", stopPending)
                .build();
    }

    private int pendingIntentFlags() {
        int result = PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 23 ? result | PendingIntent.FLAG_IMMUTABLE : result;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "NoFocus Wi-Fi speaker",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Secure low-latency audio received from a computer on the local network.");
        channel.setShowBadge(false);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private static float clampVolume(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
