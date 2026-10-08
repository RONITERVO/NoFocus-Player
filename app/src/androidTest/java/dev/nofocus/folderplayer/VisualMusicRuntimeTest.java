package dev.nofocus.folderplayer;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** End-to-end test of the actual bundled WebView, native audio, and native FFmpeg. */
final class VisualMusicRuntimeTest {
    private static Instrumentation instrumentation;
    private static VisualMusicActivity activity;
    static void run(Instrumentation test, String theme) throws Exception {
        instrumentation = test;
        Context context = test.getTargetContext();
        VisualMusicLibrary library = new VisualMusicLibrary(context);
        SharedPreferences preferences = context.getSharedPreferences("VisualMusicActivity", 0);
        SharedPreferences pcPreferences = context.getSharedPreferences("visual-pc", 0);
        String previousTarget = pcPreferences.getString("target", "auto");
        pcPreferences.edit().putString("target", "phone").commit();
        String savedSelection = preferences.getString("selected", ""), savedExport = preferences.getString("lastExport", ""), savedExtension = preferences.getString("lastExtension", "");
        try { library.read(savedSelection); } catch (Exception ignored) { savedSelection = ""; }
        if (!savedExport.isEmpty()) try (android.os.ParcelFileDescriptor ignored = context.getContentResolver().openFileDescriptor(Uri.parse(savedExport), "r")) { }
        catch (Exception ignored) { savedExport = ""; savedExtension = ""; }
        DownloadRuntime runtime = new DownloadRuntime(context); runtime.prepare(message -> {});
        File directory = new File(context.getCacheDir(), "visual-test-" + UUID.randomUUID()); directory.mkdirs();
        File input = new File(SongDownloadService.directory(context), "visual-test-" + UUID.randomUUID() + ".mkv"); input.getParentFile().mkdirs();
        JSONObject song = null; Uri exported = null;
        try {
            byte[] samples = new byte[48000 * 8 * 2]; ByteBuffer pcm = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 48000 * 2; i++) { pcm.putFloat((float)Math.sin(i * 2 * Math.PI * 440 / 48000) * .12345678f); pcm.putFloat((float)Math.sin(i * 2 * Math.PI * 880 / 48000) * .23456789f); }
            File wave = new File(directory, "original.wav");
            try (RandomAccessFile wav = new RandomAccessFile(wave, "rw")) { CaptureFiles.wavHeader(wav, samples.length, true); wav.write(samples); }
            VisualMusicLibrary.run(runtime, Arrays.asList("-f", "lavfi", "-i", "color=size=320x320:rate=24", "-i", wave.toString(), "-t", "2", "-c:v", "mpeg4", "-c:a", "copy", input.toString()));
            song = library.importMedia(SongFileProvider.uri(context, input.getName()), runtime, message -> {});
            song.put("theme", theme); library.save(song);
            // Open our fixture even when the user has a real song selected.
            preferences.edit().putString("selected", song.getString("id")).commit();
            if (!Arrays.equals(samples, bytes(new File(library.song(song.getString("id")), "playback.f32")))) throw new AssertionError("Float playback altered samples");
            activity = (VisualMusicActivity)test.startActivitySync(new Intent(context, VisualMusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitJs("!!document.querySelector('#stage canvas')", 20000);
            if (!Boolean.TRUE.equals(js("!!window.NativeFrames"))) throw new AssertionError("Binary frame bridge unavailable on this test device");
            js("document.getElementById('editLyrics').click(); true");
            waitJs("!document.getElementById('lyrics').hidden", 5000);
            String lyrics = "[{\"text\":\"Gather\",\"start\":0.1,\"end\":0.6,\"language\":\"en\",\"phrase_id\":\"p0\",\"uncertain\":false},{\"text\":\"Hola\",\"start\":1.0,\"end\":1.2,\"language\":\"es\",\"phrase_id\":\"p1\",\"uncertain\":false},{\"text\":\"Hello\",\"start\":1.3,\"end\":1.7,\"language\":\"en\",\"phrase_id\":\"p2\",\"uncertain\":false}]";
            js("document.getElementById('jsonInput').value=" + JSONObject.quote(lyrics) + ";document.getElementById('titleInput').value='Phone parity test';document.getElementById('saveLyrics').click();true");
            waitJs("!document.getElementById('listen').hidden && document.getElementById('songTitle').textContent==='Phone parity test'", 10000);
            if (library.read(song.getString("id")).getJSONArray("segments").length() != 2) throw new AssertionError("Gemini import not saved");
            js("document.getElementById('play').click();true");
            long wait = System.currentTimeMillis() + 5000;
            while (VisualMusicPlayback.position() < .1 && System.currentTimeMillis() < wait) Thread.sleep(30);
            if (VisualMusicPlayback.position() < .1) throw new AssertionError("Native float playback did not advance: " + VisualMusicPlayback.error);
            js("document.getElementById('showExport').click();true");
            waitJs("!document.getElementById('export').hidden", 5000);
            js("document.getElementById('fps').value='24';document.getElementById('videoQuality').value='lossless';document.getElementById('closeExport').click();true");
            waitJs("!document.getElementById('listen').hidden && document.querySelector('#stage canvas').width===720", 10000);
            // The playback clock must not overwrite an in-progress touch drag.
            js("document.getElementById('seek').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));document.getElementById('seek').value='0.5';true");
            Thread.sleep(350);
            if (!"0.5".equals(js("document.getElementById('seek').value"))) throw new AssertionError("Playback clock reset the seek slider while dragging");
            // A nonzero lyric frame catches font, word masks, FFT history and animation differences.
            js("document.getElementById('seek').value='0.5';document.getElementById('seek').dispatchEvent(new Event('change'));true");
            waitJs("document.getElementById('status').textContent===''", 10000);
            Thread.sleep(300);
            String png = (String)js("document.querySelector('#stage canvas').toDataURL('image/png').split(',')[1]");
            byte[] preview = Base64.decode(png, Base64.DEFAULT);
            Bitmap expected = BitmapFactory.decodeByteArray(preview, 0, preview.length);
            if ("sketchbook".equals(theme)) {
                int ink = 0;
                for (int y = 720; y < 900; y++) for (int x = 160; x < 560; x++) {
                    int pixel = expected.getPixel(x, y);
                    if ((pixel & 255) > ((pixel >> 16) & 255) + 12) ink++;
                }
                if (ink < 30) throw new AssertionError("English-only intro was clipped below the waterline: " + ink + " ink pixels");
            }
            try (OutputStream screenshot = new FileOutputStream(new File(context.getFilesDir(), "visual-" + theme + ".png"))) {
                test.getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, screenshot);
            }
            js("document.getElementById('showExport').click();true");
            waitJs("!document.getElementById('export').hidden", 5000);
            js("document.getElementById('startExport').click();true");
            waitJs("document.getElementById('exportProgress').textContent.startsWith('Saved') || document.getElementById('status').classList.contains('error')", 180000);
            if (Boolean.TRUE.equals(js("document.getElementById('status').classList.contains('error')"))) throw new AssertionError(js("document.getElementById('status').textContent"));
            android.util.Log.i("VisualMusicTest", "UI binary export: " + js("document.getElementById('exportProgress').textContent"));
            String uri = activity.getPreferences(0).getString("lastExport", ""); exported = Uri.parse(uri);
            File video = new File(directory, "export.mkv");
            try (InputStream in = context.getContentResolver().openInputStream(exported); OutputStream out = new FileOutputStream(video)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
            File decoded = new File(directory, "audio.f32");
            VisualMusicLibrary.run(runtime, Arrays.asList("-i", video.toString(), "-map", "0:a:0", "-f", "f32le", decoded.toString()));
            if (!Arrays.equals(samples, bytes(decoded))) throw new AssertionError("Export changed original PCM samples");
            File pixels = new File(directory, "frame.rgb");
            VisualMusicLibrary.run(runtime, Arrays.asList("-i", video.toString(), "-map", "0:v:0", "-vf", "select=eq(n\\,12)", "-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", pixels.toString()));
            byte[] rgb = bytes(pixels);
            if (rgb.length != 720 * 1280 * 3) throw new AssertionError("Wrong video dimensions");
            for (int y = 0; y < 1280; y++) for (int x = 0; x < 720; x++) {
                int p = expected.getPixel(x, y), at = (y * 720 + x) * 3;
                if (((p >> 16) & 255) != (rgb[at] & 255) || ((p >> 8) & 255) != (rgb[at + 1] & 255) || (p & 255) != (rgb[at + 2] & 255))
                    throw new AssertionError("Preview/export pixel mismatch at " + x + "," + y);
            }
            // Cancellation must release the encoder even while it is waiting for its first frame.
            VisualMusicExport cancelled = new VisualMusicExport(library, song, new JSONObject().put("width", 320).put("height", 320).put("fps", 24));
            cancelled.close();
            if (cancelled.directory.exists()) throw new AssertionError("Cancelled encoder retained temporary output");
            Bitmap small = Bitmap.createScaledBitmap(expected, 320, 320, false);
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.PNG, 100, compressed);
            String testFrame = Base64.encodeToString(compressed.toByteArray(), Base64.NO_WRAP);
            for (String audioMode : new String[]{"preserve", "aac"}) {
                try (VisualMusicExport compatible = new VisualMusicExport(library, song,
                        new JSONObject().put("width", 320).put("height", 320).put("fps", 24).put("mode", "publish").put("audio", audioMode))) {
                    for (int n = 0; n < compatible.total; n++) compatible.frame(n, testFrame);
                    Uri published = Uri.parse(compatible.finish().getString("uri"));
                    try {
                        JSONObject audioInfo = VisualMusicLibrary.probe(runtime, compatible.output);
                        if (!(audioMode.equals("preserve") ? "pcm_f32le" : "aac").equals(audioInfo.getString("codec_name"))) throw new AssertionError("Wrong audio mode");
                        if (!audioMode.equals("preserve") && !compatible.extension.equals("mp4")) throw new AssertionError("AAC compatibility output must be MP4");
                    } finally { context.getContentResolver().delete(published, null, null); }
                }
            }
            // Exercise the actual hardware surface path with a directional color pattern.
            byte[] hardwareFrame = new byte[320 * 320 * 4];
            for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) {
                int at = (y * 320 + x) * 4;
                hardwareFrame[at] = (byte)(y < 160 ? 230 : 15);
                hardwareFrame[at + 1] = (byte)(x < 160 ? 70 : 160);
                hardwareFrame[at + 2] = (byte)(y < 160 ? 20 : 225);
                hardwareFrame[at + 3] = (byte)255;
            }
            try (VisualMusicExport hardware = new VisualMusicExport(library, song, new JSONObject().put("width",320).put("height",320)
                    .put("fps",24).put("mode","publish").put("audio","preserve").put("raw",true).put("encoder","hardware"))) {
                if (!hardware.ready.get(30, TimeUnit.SECONDS).contains("Hardware")) throw new AssertionError("Hardware encoder was not used");
                boolean rejected = false;
                try { hardware.frameBytes(1, hardwareFrame); } catch (IOException expectedError) { rejected = true; }
                if (!rejected) throw new AssertionError("Out-of-order binary frame accepted");
                for (int n = 0; n < hardware.total; n++) hardware.frameBytes(n, hardwareFrame);
                JSONObject result = hardware.finish(); Uri published = Uri.parse(result.getString("uri"));
                try {
                    File hwAudio = new File(directory,"hardware.f32"), hwPixels = new File(directory,"hardware.rgb");
                    VisualMusicLibrary.run(runtime,Arrays.asList("-i",hardware.output.toString(),"-map","0:a:0","-f","f32le",hwAudio.toString()));
                    if (!Arrays.equals(samples,bytes(hwAudio))) throw new AssertionError("Hardware export changed original audio");
                    VisualMusicLibrary.run(runtime,Arrays.asList("-i",hardware.output.toString(),"-frames:v","1","-pix_fmt","rgb24","-f","rawvideo",hwPixels.toString()));
                    byte[] actual = bytes(hwPixels);
                    for (int y : new int[]{40,280}) for (int x : new int[]{40,280}) for (int c = 0; c < 3; c++) {
                        if (Math.abs((actual[(y * 320 + x) * 3 + c] & 255) - (hardwareFrame[(y * 320 + x) * 4 + c] & 255)) > 30)
                            throw new AssertionError("Hardware video color/orientation mismatch");
                    }
                    android.util.Log.i("VisualMusicTest", "Hardware encode elapsed ms: " + result.getLong("elapsedMs"));
                } finally { context.getContentResolver().delete(published,null,null); }
            }
        } finally {
            if (activity != null) test.runOnMainSync(activity::finish);
            context.stopService(new Intent(context, VisualMusicPlayback.class));
            if (exported != null) context.getContentResolver().delete(exported, null, null);
            if (song != null) DownloadRuntime.removeTree(library.song(song.getString("id")));
            input.delete(); DownloadRuntime.removeTree(directory);
            preferences.edit().putString("selected", savedSelection).putString("lastExport", savedExport).putString("lastExtension", savedExtension).commit();
            pcPreferences.edit().putString("target", previousTarget).commit();
        }
    }
    private static byte[] bytes(File file) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream(); try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) data.write(b, 0, n); } return data.toByteArray();
    }
    private static Object js(String script) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>(); instrumentation.runOnMainSync(() -> activity.web.evaluateJavascript(script, result::complete));
        return new JSONTokener(result.get(30, TimeUnit.SECONDS)).nextValue();
    }
    private static void waitJs(String condition, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) { if (Boolean.TRUE.equals(js(condition))) return; Thread.sleep(100); }
        throw new AssertionError("UI timeout: " + condition + "\n" + js("document.body.innerText"));
    }
}
