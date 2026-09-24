package dev.nofocus.folderplayer;

import android.app.Instrumentation;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Real bundled executables and audio containers, independent of YouTube availability. */
final class DownloadRuntimeTest {
    static void run(Instrumentation test, Bundle arguments) throws Exception {
        Context context = test.getTargetContext();
        DownloadRuntime runtime = new DownloadRuntime(context);
        File directory = new File(context.getCacheDir(), "runtime-test");
        DownloadRuntime.removeTree(directory); directory.mkdirs();
        try {
            runtime.prepare(message -> android.util.Log.i("DownloadRuntimeTest", message));
            StringBuilder log = new StringBuilder();
            int version = runtime.run(Arrays.asList("--version"), line -> log.append(line).append('\n'));
            if (version != 0 || !log.toString().contains("2026.")) throw new AssertionError("yt-dlp did not run: " + log);
            File source = new File(directory, "tone.wav");
            tone(source);
            for (String format : SongDownloadSpec.FORMATS) {
                log.setLength(0);
                File output = new File(directory, "converted-" + format + ".%(ext)s");
                int exit = runtime.run(Arrays.asList("--ignore-config", "--enable-file-urls", "--no-playlist", "--no-cache-dir",
                        "--extract-audio", "--audio-format", format, "--audio-quality", "192K", "-o", output.getAbsolutePath(), "--", source.toURI().toString()),
                        line -> { log.append(line).append('\n'); android.util.Log.i("DownloadRuntimeTest", line); });
                File audio = new File(directory, "converted-" + format + "." + format);
                if (exit != 0 || !audio.isFile() || audio.length() == 0) throw new AssertionError(format + ": " + log);
                try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
                    metadata.setDataSource(audio.getAbsolutePath());
                    String mime = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                    String expected = SongDownloadSpec.mime(format);
                    if (!expected.equals(mime) && !("wav".equals(format) && "audio/x-wav".equals(mime)))
                        throw new AssertionError(format + " container mismatch: " + mime);
                    int duration = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    if (duration < 900 || duration > 1200) throw new AssertionError("Incorrect audio duration: " + duration);
                }
            }
            DownloadRuntime cancel = new DownloadRuntime(context);
            cancel.prepare(message -> {});
            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
            long start = System.currentTimeMillis();
            timer.schedule(cancel::cancel, 2, TimeUnit.SECONDS);
            try {
                cancel.run(Arrays.asList("--ignore-config", "--enable-file-urls", "--sleep-interval", "30", "-o",
                        new File(directory, "cancel.%(ext)s").getAbsolutePath(), "--", source.toURI().toString()), line -> {});
                throw new AssertionError("Cancelled process completed instead of throwing");
            } catch (CancellationException expected) {
                if (System.currentTimeMillis() - start > 10000) throw new AssertionError("Cancellation took too long");
            } finally { timer.shutdownNow(); }
            try {
                context.getContentResolver().openFileDescriptor(SongFileProvider.uri(context, "../secret"), "r");
                throw new AssertionError("Provider accepted a path outside songs");
            } catch (FileNotFoundException expected) { }
            String url = arguments.getString("url");
            if (url != null) {
                log.setLength(0);
                int exit = runtime.run(SongDownloadSpec.arguments(url, "mp3", new File(directory, "youtube.%(ext)s").getAbsolutePath()),
                        line -> { log.append(line).append('\n'); android.util.Log.i("DownloadRuntimeTest", line); });
                if (exit != 0 || !new File(directory, "youtube.mp3").isFile()) throw new AssertionError("YouTube: " + log);
            }
        } finally { runtime.cancel(); DownloadRuntime.removeTree(directory); }
    }

    private static void tone(File file) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(file))) {
            output.writeBytes("RIFF"); le32(output, 36 + 44100 * 2); output.writeBytes("WAVEfmt ");
            le32(output, 16); le16(output, 1); le16(output, 1); le32(output, 44100); le32(output, 88200);
            le16(output, 2); le16(output, 16); output.writeBytes("data"); le32(output, 88200);
            for (int i = 0; i < 44100; i++) le16(output, (int)(2000 * Math.sin(2 * Math.PI * 440 * i / 44100)));
        }
    }
    private static void le16(DataOutputStream out, int value) throws IOException { out.writeByte(value); out.writeByte(value >> 8); }
    private static void le32(DataOutputStream out, int value) throws IOException { le16(out, value); le16(out, value >> 16); }
}
