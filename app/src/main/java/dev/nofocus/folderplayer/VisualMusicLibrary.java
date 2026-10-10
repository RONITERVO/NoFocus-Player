package dev.nofocus.folderplayer;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Original media is immutable. Decoded files are only for listening and visual analysis. */
final class VisualMusicLibrary {
    static final long MAX_INPUT = 768L * 1024 * 1024;
    final Context context;
    final File root;
    VisualMusicLibrary(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "visual-music"); root.mkdirs();
    }
    File song(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid song.");
        return new File(root, id);
    }
    JSONObject read(String id) throws Exception { return new JSONObject(readText(new File(song(id), "song.json"))); }
    synchronized void save(JSONObject metadata) throws Exception {
        byte[] encoded = metadata.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 2 * 1024 * 1024) throw new IOException("Song timing data is too large. Keep one song under 2 MB.");
        File dir = song(metadata.getString("id"));
        File temporary = new File(dir, "song.tmp");
        try (OutputStream out = new FileOutputStream(temporary)) { out.write(encoded); }
        if (!temporary.renameTo(new File(dir, "song.json"))) throw new IOException("Could not save song.");
    }
    JSONArray list() throws Exception {
        JSONArray result = new JSONArray(); File[] entries = root.listFiles();
        if (entries == null) return result;
        Arrays.sort(entries, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        for (File entry : entries) if (new File(entry, "song.json").isFile()) {
            try {
                JSONObject song = read(entry.getName());
                result.put(new JSONObject().put("id", song.getString("id")).put("title", song.getString("title"))
                        .put("duration", song.getDouble("duration")).put("codec", song.getString("codec"))
                        .put("cueCount", song.getJSONArray("segments").length()));
            } catch (Exception ignored) { }
        }
        return result;
    }
    JSONObject importMedia(Uri uri, DownloadRuntime runtime, DownloadRuntime.Progress progress) throws Exception {
        String id = UUID.randomUUID().toString(); File dir = song(id); dir.mkdirs();
        try {
            File source = new File(dir, "source");
            String name = "My song";
            try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            }
            progress.report("Copying original media…");
            try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(source)) {
                if (in == null) throw new IOException("Cannot open selected media.");
                byte[] bytes = new byte[65536]; long size = 0; int n;
                while ((n = in.read(bytes)) != -1) {
                    runtime.checkCancelled(); size += n;
                    if (size > MAX_INPUT) throw new IOException("Choose a recording smaller than 768 MB.");
                    if (dir.getUsableSpace() < 256L * 1024 * 1024) throw new IOException("Free more phone storage before importing.");
                    out.write(bytes, 0, n);
                }
            }
            runtime.prepare(progress);
            JSONObject info = probe(runtime, source);
            int rate = info.getInt("sample_rate");
            if (rate < 8000 || rate > 192000) throw new IOException("Unsupported audio sample rate.");
            double duration = info.getDouble("media_duration");
            if (!Double.isFinite(duration) || duration <= 0 || duration > 1200) throw new IOException("Import one song, up to 20 minutes long.");
            if (dir.getUsableSpace() < duration * (rate * 8L + 44100 * 4L) + 512L * 1024 * 1024)
                throw new IOException("Free more storage for lossless playback and video export.");
            progress.report("Preparing full quality playback…");
            File pcm = new File(dir, "playback.f32");
            run(runtime, Arrays.asList("-i", source.toString(), "-map", "0:a:0", "-vn", "-t", "1200", "-ac", "2", "-ar", "" + rate,
                    "-c:a", "pcm_f32le", "-f", "f32le", pcm.toString()));
            progress.report("Preparing visualization…");
            run(runtime, Arrays.asList("-f", "f32le", "-ar", "" + rate, "-ac", "2", "-i", pcm.toString(),
                    "-af", "pan=mono|c0=0.5*c0+0.5*c1", "-ar", "44100", "-c:a", "pcm_f32le", "-f", "f32le", new File(dir, "analysis.f32").toString()));
            JSONObject metadata = new JSONObject().put("id", id).put("title", name.replaceFirst("\\.[^.]+$", ""))
                    .put("duration", pcm.length() / (rate * 8.0)).put("rate", rate).put("codec", info.getString("codec_name"))
                    .put("sourceName", name).put("channels", info.optInt("channels", 2)).put("segments", new JSONArray())
                    .put("theme", "sketchbook").put("lyrics", "").put("guide", "");
            save(metadata); return metadata;
        } catch (Exception error) { DownloadRuntime.removeTree(dir); throw error; }
    }
    static JSONObject probe(DownloadRuntime runtime, File source) throws Exception {
        StringBuilder data = new StringBuilder();
        int result = runtime.ffprobe(Arrays.asList("-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "a:0", "-show_entries",
                "stream=codec_name,sample_rate,channels,duration:format=duration", "-of", "json", source.toString()), line -> {
            if (data.length() < 65536) data.append(line).append('\n');
        });
        if (result != 0) throw new IOException("Cannot read this media file.");
        JSONObject doc = new JSONObject(data.toString());
        JSONArray streams = doc.getJSONArray("streams");
        if (streams.length() == 0) throw new IOException("This file has no audio track.");
        JSONObject stream = streams.getJSONObject(0);
        double duration = stream.optDouble("duration", doc.optJSONObject("format") == null ? 0 : doc.getJSONObject("format").optDouble("duration", 0));
        return stream.put("media_duration", duration);
    }
    static void run(DownloadRuntime runtime, List<String> args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("-hide_banner", "-loglevel", "error", "-y", "-nostdin", "-protocol_whitelist", "file,pipe")); command.addAll(args);
        StringBuilder diagnostic = new StringBuilder();
        if (runtime.ffmpeg(command, line -> { if (diagnostic.length() < 1500) diagnostic.append(line).append('\n'); }) != 0)
            throw new IOException("Audio preparation failed: " + diagnostic);
    }
    static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return text(in); }
    }
    static String text(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int n;
        while ((n = in.read(bytes)) != -1) { if (out.size() + n > 2 * 1024 * 1024) throw new IOException("Lyrics JSON must be under 2 MB."); out.write(bytes, 0, n); }
        return out.toString("UTF-8");
    }
    Uri publish(File file, String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("Saving visualizer videos needs Android 10 or newer.");
        ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime); values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NoFocus");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot create exported video.");
        try {
            try (InputStream in = new FileInputStream(file); OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot save exported video.");
                byte[] bytes = new byte[65536]; int n; while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); context.getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception error) { context.getContentResolver().delete(uri, null, null); throw new IOException(error); }
    }
}
