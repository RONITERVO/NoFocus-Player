package dev.nofocus.folderplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.MediaProjection;
import android.os.*;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;

/** Android 10+ internal playback PCM and independently encoded screen video. Never uses a microphone. */
@android.annotation.TargetApi(29)
final class PlaybackCapture {
    interface Progress {
        void update(String message);
        default void started(long atMillis) { }
    }
    volatile boolean stopping;
    volatile Exception audioError;
    volatile long audioStartUs;
    volatile boolean timestampAvailable;
    volatile int peak;
    long videoStartUs = -1;
    private final Context context;
    private final MediaProjection projection;
    private final File directory;
    private final boolean floating;

    PlaybackCapture(Context context, MediaProjection projection, File directory, boolean floating) {
        this.context = context; this.projection = projection; this.directory = directory; this.floating = floating;
    }

    @SuppressLint("MissingPermission") // Activity grants RECORD_AUDIO before starting the foreground service.
    void record(int width, int height, int density, int bitrate, CaptureTiming timing, Progress progress) throws Exception {
        AudioRecord audio = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        Surface surface = null;
        VirtualDisplay display = null;
        Thread audioThread = null;
        boolean muxing = false, encoding = false;
        try {
            AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(android.os.Process.myUid()).build();
            int encodingFormat = floating ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT;
            int minimum = AudioRecord.getMinBufferSize(CaptureFiles.RATE, AudioFormat.CHANNEL_IN_STEREO, encodingFormat);
            if (minimum <= 0) throw new IOException("Audio format unavailable. Try the 16-bit FLAC option.");
            audio = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(capture)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(CaptureFiles.RATE)
                            .setEncoding(encodingFormat).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                    .setBufferSizeInBytes(Math.max(minimum * 4, 38400)).build();
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Internal audio capture could not start.");
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaCodecInfo.VideoCapabilities caps = encoder.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
            width = Math.max(caps.getWidthAlignment(), width / caps.getWidthAlignment() * caps.getWidthAlignment());
            height = Math.max(caps.getHeightAlignment(), height / caps.getHeightAlignment() * caps.getHeightAlignment());
            if (!caps.areSizeAndRateSupported(width, height, 30)) throw new IOException("Video size unavailable. Choose 720p and try again.");
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // A static lyric card still needs frames to keep the video duration in step with audio.
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000L / 30);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = encoder.createInputSurface();
            muxer = new MediaMuxer(new File(directory, "screen.mp4").getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            encoder.start(); encoding = true;
            if (stopping) throw new java.util.concurrent.CancellationException("Capture cancelled before recording.");
            AudioRecord reader = audio;
            audioStartUs = System.nanoTime() / 1000;
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Audio permission or capture policy blocked recording.");
            audioThread = new Thread(() -> writeAudio(reader), "NoFocus-PCM-capture");
            audioThread.start();
            display = projection.createVirtualDisplay("NoFocus lyrics capture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1;
            long began = SystemClock.elapsedRealtime(), updateAt = 0, endAt = 0;
            progress.started(began);
            boolean eosSent = false, ended = false;
            while (!ended) {
                long now = SystemClock.elapsedRealtime();
                if (timing.remaining(began, now) == 0) stopping = true;
                if (stopping && !eosSent) {
                    display.release(); display = null;
                    audio.stop();
                    encoder.signalEndOfInputStream(); eosSent = true; endAt = now + 5000;
                }
                if (eosSent && now > endAt) throw new IOException("Video encoder did not finish. Raw capture retained.");
                int index = encoder.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new IOException("Video format changed during capture.");
                    track = muxer.addTrack(encoder.getOutputFormat()); muxer.start(); muxing = true;
                } else if (index >= 0) {
                    try {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            if (!muxing) throw new IOException("Video track unavailable.");
                            if (videoStartUs < 0) videoStartUs = info.presentationTimeUs;
                            info.presentationTimeUs -= videoStartUs;
                            ByteBuffer buffer = encoder.getOutputBuffer(index);
                            muxer.writeSampleData(track, buffer, info);
                        }
                        ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { encoder.releaseOutputBuffer(index, false); }
                }
                if (now >= updateAt && !eosSent) {
                    updateAt = now + 1000;
                    if (directory.getUsableSpace() < 256L * 1024 * 1024) throw new IOException("Storage nearly full. Raw capture retained.");
                    long seconds = (now - began) / 1000;
                    progress.update("Recording " + CaptureTiming.clock(now - began) + " · " + CaptureTiming.clock(timing.remaining(began, now) + 999) + " left"
                            + (floating ? " · 48 kHz stereo float PCM" : " · 48 kHz stereo 16-bit PCM") + (seconds >= 5 && peak == 0 ? "\nNo internal audio detected. Check Suno playback/capture permission." : ""));
                }
                if (audioError != null) throw audioError;
            }
            audioThread.join(5000);
            if (audioThread.isAlive()) throw new IOException("Audio recorder did not finish.");
            if (audioError != null) throw audioError;
            if (videoStartUs < 0) throw new IOException("No video frames captured.");
            if (peak == 0) throw new IOException("Capture contains only silence. Suno may block playback capture. Raw files retained.");
        } finally {
            stopping = true;
            if (display != null) display.release();
            if (audio != null) { try { audio.stop(); } catch (Exception ignored) { } }
            if (audioThread != null) audioThread.join(5000);
            if (audio != null) audio.release();
            if (encoder != null) { try { if (encoding) encoder.stop(); } finally { encoder.release(); } }
            if (surface != null) surface.release();
            if (muxer != null) { try { if (muxing) muxer.stop(); } finally { muxer.release(); } }
        }
    }

    private void writeAudio(AudioRecord audio) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        try (RandomAccessFile file = new RandomAccessFile(new File(directory, "audio.wav"), "rw")) {
            CaptureFiles.wavHeader(file, 0, floating);
            long bytes = 0;
            byte[] buffer = new byte[19200];
            float[] floats = new float[4800];
            ByteBuffer floatBytes = ByteBuffer.wrap(buffer).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            AudioTimestamp timestamp = new AudioTimestamp();
            try {
                while (!stopping) {
                    int count;
                    if (floating) {
                        int samples = audio.read(floats, 0, floats.length, AudioRecord.READ_BLOCKING);
                        count = samples < 0 ? samples : samples * 4;
                        floatBytes.clear();
                        for (int i = 0; i < samples; i++) {
                            floatBytes.putFloat(floats[i]);
                            if (floats[i] != 0) peak = 1;
                        }
                    } else count = audio.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (count < 0) { if (stopping) break; throw new IOException("Internal audio read failed: " + count); }
                    if (count == 0) continue;
                    if (!timestampAvailable && audio.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                            && timestamp.framePosition > 0) {
                        audioStartUs = timestamp.nanoTime / 1000 - timestamp.framePosition * 1000000L / CaptureFiles.RATE;
                        timestampAvailable = true;
                    }
                    file.write(buffer, 0, count); bytes += count;
                    if (!floating) for (int i = 0; i + 1 < count; i += 2) peak = Math.max(peak, Math.abs((short)((buffer[i] & 255) | (buffer[i + 1] << 8))));
                }
            } finally { CaptureFiles.wavHeader(file, bytes, floating); }
        } catch (Exception error) { audioError = error; stopping = true; }
    }
}
