package dev.nofocus.folderplayer;

import android.net.Uri;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded binary frame pipe; the untouched source always supplies export audio. */
@android.annotation.TargetApi(24)
final class VisualMusicExport implements AutoCloseable {
    final String id = UUID.randomUUID().toString();
    final int width, height, fps, total;
    final String extension;
    final boolean raw;
    final CompletableFuture<String> ready = new CompletableFuture<>();
    private volatile String encoderName = "Software H.264";
    private final long began = android.os.SystemClock.elapsedRealtime();
    final File directory, output;
    final DownloadRuntime runtime;
    private final VisualMusicLibrary library;
    private final JSONObject song;
    private final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(2);
    private final CompletableFuture<Void> completed = new CompletableFuture<>();
    private final Thread encoder;
    private volatile boolean cancelled;
    private int received;

    VisualMusicExport(VisualMusicLibrary library, JSONObject song, JSONObject options) throws Exception {
        this.library = library; this.song = song;
        width = options.getInt("width"); height = options.getInt("height"); fps = options.getInt("fps");
        if (width < 320 || width > 1920 || height < 320 || height > 1920 || width % 2 != 0 || height % 2 != 0
                || (fps != 24 && fps != 30 && fps != 60)) throw new IOException("Invalid video dimensions or frame rate.");
        double duration = song.getDouble("duration");
        if (duration <= 0 || duration > 1200) throw new IOException("Export up to 20 minutes per video.");
        total = (int)Math.ceil(duration * fps);
        raw = options.optBoolean("raw", false);
        boolean lossless = "lossless".equals(options.optString("mode"));
        boolean aac = "aac".equals(options.optString("audio"));
        extension = lossless || (!aac && !"aac".equals(song.getString("codec"))) ? "mkv" : "mp4";
        directory = new File(library.context.getCacheDir(), "visual-export-" + id); directory.mkdirs();
        if (directory.getUsableSpace() < 512L * 1024 * 1024) throw new IOException("Free at least 512 MB for export.");
        output = new File(directory, "video." + extension);
        runtime = new DownloadRuntime(library.context);
        File source = new File(library.song(song.getString("id")), "source");
        encoder = new Thread(() -> {
            try {
                runtime.prepare(message -> {});
                VisualHardwareEncoder hardware = null;
                File encoded = new File(directory, "hardware.mp4");
                if (raw && !lossless && !"software".equals(options.optString("encoder"))) {
                    try { hardware = new VisualHardwareEncoder(encoded, width, height, fps); }
                    catch (Exception unavailable) {
                        if ("hardware".equals(options.optString("encoder"))) throw unavailable;
                        android.util.Log.i("VisualExport", "Using software encoder: " + unavailable.getMessage());
                    }
                }
                if (cancelled) { if (hardware != null) hardware.close(); throw new CancellationException(); }
                encoderName = hardware != null ? "Hardware H.264" : lossless ? "Lossless RGB" : "Software H.264";
                ready.complete(encoderName);
                if (hardware != null) {
                    try {
                        int index = 0;
                        for (;;) {
                            byte[] frame = nextFrame(); if (frame.length == 0) break;
                            hardware.frame(frame, index++);
                        }
                        hardware.finish();
                    } finally { hardware.close(); }
                    List<String> mux = new ArrayList<>(Arrays.asList("-i", encoded.toString(), "-i", source.toString(),
                            "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", aac ? "aac" : "copy"));
                    if (aac) Collections.addAll(mux, "-b:a", "320k");
                    if ("mp4".equals(extension)) Collections.addAll(mux, "-movflags", "+faststart");
                    mux.add(output.toString());
                    VisualMusicLibrary.run(runtime, mux);
                } else {
                    List<String> args = new ArrayList<>(Arrays.asList("-hide_banner", "-loglevel", "error", "-y"));
                    if (raw) Collections.addAll(args, "-f", "rawvideo", "-pix_fmt", "rgba", "-video_size", width + "x" + height, "-framerate", "" + fps);
                    else Collections.addAll(args, "-f", "image2pipe", "-framerate", "" + fps, "-c:v", "png");
                    Collections.addAll(args, "-i", "pipe:0", "-protocol_whitelist", "file,pipe", "-i", source.toString(),
                            "-map", "0:v:0", "-map", "1:a:0", "-c:v", lossless ? "libx264rgb" : "libx264",
                            "-preset", "ultrafast", "-crf", lossless ? "0" : "17", "-pix_fmt", lossless ? "rgb24" : "yuv420p",
                            "-threads", "2", "-c:a", aac ? "aac" : "copy");
                    if (aac) Collections.addAll(args, "-b:a", "320k");
                    if ("mp4".equals(extension)) Collections.addAll(args, "-movflags", "+faststart");
                    args.add(output.toString());
                    StringBuilder diagnostic = new StringBuilder();
                    int code = runtime.ffmpeg(args, line -> { if (diagnostic.length() < 1600) diagnostic.append(line).append('\n'); }, stream -> {
                        for (;;) { byte[] frame = nextFrame(); if (frame.length == 0) break; stream.write(frame); }
                    });
                    if (code != 0) throw new IOException("Video encoding failed: " + diagnostic);
                }
                if (cancelled) throw new CancellationException();
                completed.complete(null);
            } catch (Throwable error) { ready.completeExceptionally(error); completed.completeExceptionally(error); }
        }, "visual-music-export");
        encoder.start();
    }
    private byte[] nextFrame() throws Exception {
        byte[] data = frames.take();
        if (cancelled) throw new CancellationException("Export cancelled.");
        if (directory.getUsableSpace() < 128L * 1024 * 1024) throw new IOException("Phone storage is full. Export cancelled.");
        return data;
    }
    synchronized void frameBytes(int index, byte[] rgba) throws Exception {
        if (!raw || index != received || index >= total || rgba.length != width * height * 4) throw new IOException("Invalid raw frame sequence or size.");
        enqueue(rgba); received++;
    }
    synchronized void frame(int index, String base64) throws Exception {
        if (raw || index != received || index >= total || base64.length() > 16 * 1024 * 1024) throw new IOException("Invalid video frame sequence.");
        byte[] png = Base64.decode(base64, Base64.DEFAULT);
        if (png.length < 24 || png[0] != (byte)137 || png[1] != 80 || png[2] != 78 || png[3] != 71
                || java.nio.ByteBuffer.wrap(png, 16, 8).getInt() != width || java.nio.ByteBuffer.wrap(png, 20, 4).getInt() != height)
            throw new IOException("Invalid video frame dimensions.");
        enqueue(png); received++;
    }
    private void enqueue(byte[] png) throws Exception {
        for (;;) {
            if (cancelled) throw new CancellationException("Export cancelled.");
            if (completed.isDone()) { completed.get(); throw new IOException("Encoder ended before all frames arrived."); }
            if (frames.offer(png, 200, TimeUnit.MILLISECONDS)) return;
        }
    }
    JSONObject finish() throws Exception {
        if (received != total) throw new IOException("Video is incomplete.");
        enqueue(new byte[0]); completed.get(120, TimeUnit.SECONDS);
        if (cancelled) throw new CancellationException("Export cancelled.");
        String title = song.getString("title").replaceAll("[^\\p{L}\\p{N} _-]", "");
        if (title.length() > 80) title = title.substring(0, 80);
        Uri uri = library.publish(output, (title.isEmpty() ? "Visualizer" : title) + "-visualizer." + extension,
                "mp4".equals(extension) ? "video/mp4" : "video/x-matroska");
        return new JSONObject().put("uri", uri.toString()).put("extension", extension).put("frames", total).put("encoder", encoderName).put("elapsedMs", android.os.SystemClock.elapsedRealtime() - began);
    }
    void cancel() { cancelled = true; runtime.cancel(); encoder.interrupt(); }
    @Override public void close() {
        cancel();
        try { encoder.join(5000); if (!encoder.isAlive()) DownloadRuntime.removeTree(directory); } catch (Exception ignored) { }
    }
}
