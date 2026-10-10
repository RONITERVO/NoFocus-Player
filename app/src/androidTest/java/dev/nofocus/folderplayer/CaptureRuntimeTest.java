package dev.nofocus.folderplayer;

import android.app.Instrumentation;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Checks the actual APK's FFmpeg build, both audio formats, and sample-accurate alignment. */
final class CaptureRuntimeTest {
    static void run(Instrumentation test) throws Exception {
        File directory = new File(test.getTargetContext().getCacheDir(), "capture-test-" + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IOException("Test storage unavailable");
        DownloadRuntime runtime = new DownloadRuntime(test.getTargetContext());
        try {
            runtime.prepare(message -> {});
            File video = new File(directory, "video.mp4");
            run(runtime, Arrays.asList("-v", "error", "-f", "lavfi", "-i", "color=size=320x320:rate=30", "-t", "0.1", "-c:v", "mpeg4", video.getAbsolutePath()));
            for (boolean floating : new boolean[]{false, true}) {
                int frameBytes = floating ? 8 : 4;
                byte[] pcm = new byte[4800 * frameBytes];
                ByteBuffer bytes = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < 9600; i++) {
                    if (floating) bytes.putFloat((float)Math.sin(i * 0.4321) * 0.33333334f);
                    else bytes.putShort((short)(i * 7919));
                }
                File audio = new File(directory, "audio.wav");
                try (RandomAccessFile file = new RandomAccessFile(audio, "rw")) {
                    file.setLength(0); CaptureFiles.wavHeader(file, pcm.length, floating); file.write(pcm);
                }
                File master = new File(directory, "master.mkv"), gemini = new File(directory, "gemini.mp4");
                run(runtime, CaptureFiles.mux(video, audio, master, 1000000, 1010000, true, floating));
                run(runtime, CaptureFiles.mux(video, audio, gemini, 1000000, 1010000, false, floating));
                File decoded = new File(directory, "decoded.raw");
                run(runtime, Arrays.asList("-v", "error", "-y", "-i", master.getAbsolutePath(), "-map", "0:a:0", "-f", floating ? "f32le" : "s16le", decoded.getAbsolutePath()));
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                try (InputStream input = new FileInputStream(decoded)) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) result.write(buffer, 0, count);
                }
                if (!Arrays.equals(Arrays.copyOfRange(pcm, 480 * frameBytes, pcm.length), result.toByteArray()))
                    throw new AssertionError("Capture master changed samples or alignment (float=" + floating + ")");
            }
        } finally { DownloadRuntime.removeTree(directory); }
    }
    private static void run(DownloadRuntime runtime, List<String> args) throws Exception {
        StringBuilder log = new StringBuilder();
        if (runtime.ffmpeg(args, line -> log.append(line).append('\n')) != 0) throw new AssertionError(log.toString());
    }
}
