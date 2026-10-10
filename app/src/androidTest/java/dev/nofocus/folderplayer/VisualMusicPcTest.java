package dev.nofocus.folderplayer;

import android.app.*;
import android.content.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Real pinned HTTPS upload -> PC canvas/FFmpeg -> verified phone download. */
final class VisualMusicPcTest {
    private static VisualMusicActivity activity;
    private static Instrumentation instrumentation;
    static void run(Instrumentation test) throws Exception {
        instrumentation = test;
        Context context = test.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("visual-pc",0);
        String previousPair = prefs.getString("pair",""), previousJob = prefs.getString("job",""), previousJobs = prefs.getString("jobs","[]"), previousTarget = prefs.getString("target","auto");
        SharedPreferences uiPrefs = context.getSharedPreferences("VisualMusicActivity",0);
        String previousSong = uiPrefs.getString("selected",""), previousExport = uiPrefs.getString("lastExport",""), previousExtension = uiPrefs.getString("lastExtension","");
        if (!previousJob.isEmpty() || !previousJobs.equals("[]")) throw new IOException("Finish the user's pending PC job before running this test.");
        File directory = new File(context.getCacheDir(),"pc-test-" + UUID.randomUUID());directory.mkdirs();
        File input = new File(SongDownloadService.directory(context),"pc-test-" + UUID.randomUUID() + ".wav");input.getParentFile().mkdirs();
        SharedPreferences audioPrefs = context.getSharedPreferences(PhoneAudioService.PREFS,0);
        Map<String,?> oldAudio = audioPrefs.getAll();
        SharedPreferences playbackPrefs = context.getSharedPreferences(PlayerService.PREFS,0);
        String oldAudioCode = playbackPrefs.getString(WifiStreamService.PREF_PAIRING_CODE,null);
        VisualMusicLibrary library = new VisualMusicLibrary(context);VisualMusicPc client = new VisualMusicPc(library);
        DownloadRuntime runtime = new DownloadRuntime(context);runtime.prepare(message -> {});
        JSONObject song = null;Uri published = null;List<Uri> uiOutputs = new ArrayList<>();
        try {
            String code = VisualMusicLibrary.readText(new File(context.getFilesDir(),"pc-test-pair.txt")).trim();
            JSONObject pair = VisualMusicPc.parsePairing(code);
            String invalid = pair.getString("fingerprint"); pair.put("fingerprint",(invalid.charAt(0) == '0' ? "1" : "0") + invalid.substring(1));
            boolean rejected = false;
            try { client.pair("nofocus-pc-v1:" + android.util.Base64.encodeToString(pair.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP)); }
            catch (Exception expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Untrusted PC certificate accepted");
            client.pair(code);
            if (!audioPrefs.getBoolean("unified",false) || !audioPrefs.getString("code","").matches("[A-Z2-7]{16}")
                    || !playbackPrefs.getString(WifiStreamService.PREF_PAIRING_CODE,"").matches("[A-Z2-7]{16}"))
                throw new AssertionError("One pairing did not configure both audio directions");
            byte[] samples = new byte[48000 * 8 * 2]; ByteBuffer pcm = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
            for (int n = 0; n < 48000 * 2; n++) {pcm.putFloat((float)Math.sin(n * 2 * Math.PI * 440 / 48000) * .12345678f);pcm.putFloat((float)Math.sin(n * 2 * Math.PI * 880 / 48000) * .23456789f);}
            try (RandomAccessFile wav = new RandomAccessFile(input,"rw")) {CaptureFiles.wavHeader(wav,samples.length,true);wav.write(samples);}
            song = library.importMedia(SongFileProvider.uri(context,input.getName()),runtime,message -> {});
            JSONObject options = new JSONObject().put("width",720).put("height",1280).put("fps",24).put("mode","lossless").put("audio","preserve");
            JSONObject started = client.begin(song,options,message -> {});
            client.pair(code); // Pairing the same PC while jobs exist must retain the queue.
            // Recreate the legacy single-job preference and prove the upgrade
            // migrates its exact id/configuration into the durable queue.
            JSONArray initial = new JSONArray(prefs.getString("jobs","[]"));
            prefs.edit().putString("job",initial.getJSONObject(0).toString()).remove("jobs").commit();
            // A new client must recover the same durable job after the screen/process is recreated.
            client = new VisualMusicPc(library);
            if (!client.settings().getBoolean("pending")) throw new AssertionError("PC job not retained");
            if (prefs.contains("job") || client.settings().getJSONArray("jobs").length() != 1) throw new AssertionError("Legacy migration failed");
            // A second request must keep the first id and be independently removable.
            JSONObject second = client.begin(song,options,message -> {});
            if (client.queue().getJSONArray("jobs").length() != 2) throw new AssertionError("Second export displaced the first");
            client.remove(second.getString("id"));
            if (!client.status().getString("id").equals(started.getString("id"))) throw new AssertionError("Removing second job removed first");
            long deadline = System.currentTimeMillis() + 120000;
            JSONObject job;
            do {
                job = client.status();
                if (job.getString("status").equals("failed")) throw new IOException(job.getString("error"));
                if (job.getString("status").equals("complete")) break;
                Thread.sleep(150);
            } while (System.currentTimeMillis() < deadline);
            if (!job.getString("status").equals("complete") || !started.getString("id").equals(job.getString("id"))) throw new AssertionError("PC render did not finish");
            JSONObject result = client.download(message -> {});published = Uri.parse(result.getString("uri"));
            File output = new File(directory,"video.mkv");
            try (InputStream in = context.getContentResolver().openInputStream(published);OutputStream out = new FileOutputStream(output)) {byte[] b = new byte[65536];int count;while ((count = in.read(b)) != -1) out.write(b,0,count);}
            File audio = new File(directory,"audio.f32");
            VisualMusicLibrary.run(runtime,Arrays.asList("-i",output.toString(),"-map","0:a:0","-f","f32le",audio.toString()));
            ByteArrayOutputStream actual = new ByteArrayOutputStream();try (InputStream in = new FileInputStream(audio)) {byte[] b = new byte[65536];int count;while ((count = in.read(b)) != -1) actual.write(b,0,count);}
            if (!Arrays.equals(samples,actual.toByteArray())) throw new AssertionError("PC roundtrip changed audio samples");
            if (client.settings().getBoolean("pending")) throw new AssertionError("Saved PC job not retired");
            // Cancel a second active job, including its native TLS client path.
            client.begin(song,options,message -> {});client.remove();
            if (client.hasJob()) throw new AssertionError("Cancelled PC job retained");
            // Use the actual phone controls, leave after upload, reopen, and save the PC result.
            uiPrefs.edit().putString("selected",song.getString("id")).commit();
            open(context);
            js("document.getElementById('showExport').click();true");waitJs("!document.getElementById('export').hidden",10000);
            js("document.getElementById('exportTarget').value='pc';document.getElementById('fps').value='24';document.getElementById('videoQuality').value='lossless';document.getElementById('startExport').click();true");
            waitJs("document.getElementById('exportProgress').textContent.startsWith('Upload finished')",60000);
            if (!Boolean.TRUE.equals(js("!document.getElementById('startExport').disabled"))) throw new AssertionError("Pending export blocks the next song");
            js("document.getElementById('exportProgress').textContent='';document.getElementById('startExport').click();true");
            waitJs("document.getElementById('exportProgress').textContent.startsWith('Upload finished')",60000);
            if (client.settings().getJSONArray("jobs").length() != 2) throw new AssertionError("UI failed to queue a second export");
            test.runOnMainSync(activity::finish);test.waitForIdleSync();open(context);
            js("document.getElementById('queueButton').click();true");
            waitJs("!!document.querySelector('#pcJobs [data-action=pcDownload]:not([hidden])')",60000);
            js("document.querySelector('#pcJobs [data-action=pcDownload]:not([hidden])').click();true");
            waitJs("document.getElementById('status').textContent.startsWith('Video saved')",60000);
            uiOutputs.add(Uri.parse(uiPrefs.getString("lastExport","")));
            if (client.settings().getJSONArray("jobs").length() != 1) throw new AssertionError("UI retired the wrong number of jobs");
            client.remove();
            // Queue is reachable without selecting a song. New exports stay enabled.
            js("document.getElementById('libraryButton').click();true");waitJs("!document.getElementById('library').hidden",10000);
            test.runOnMainSync(activity::finish);test.waitForIdleSync();open(context);
            js("document.getElementById('showExport').click();true");waitJs("!document.getElementById('export').hidden",10000);
            // An unreachable PC in Automatic must choose a local hardware export before uploading.
            JSONObject offline = VisualMusicPc.parsePairing(code);offline.put("url","https://127.0.0.1:49998");
            prefs.edit().putString("pair",offline.toString()).commit();
            js("document.getElementById('exportTarget').value='auto';document.getElementById('videoQuality').value='publish';document.getElementById('startExport').click();true");
            waitJs("document.getElementById('exportProgress').textContent.includes('Hardware H.264') && document.getElementById('exportProgress').textContent.startsWith('Saved')",60000);
            uiOutputs.add(Uri.parse(uiPrefs.getString("lastExport","")));
            try (OutputStream out = new FileOutputStream(new File(context.getFilesDir(),"hybrid-test-result.txt"))) {
                out.write(String.valueOf(js("document.getElementById('exportProgress').textContent")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            if (activity != null) {test.runOnMainSync(activity::finish);test.waitForIdleSync();}
            context.stopService(new Intent(context,VisualMusicPlayback.class));
            if (client.hasJob() && previousJob.isEmpty()) try {
                JSONArray remaining = client.settings().getJSONArray("jobs");
                for (int i = 0; i < remaining.length(); i++) client.remove(remaining.getJSONObject(i).getString("id"));
            } catch (Exception ignored) { }
            if (published != null) context.getContentResolver().delete(published,null,null);
            for (Uri output : uiOutputs) context.getContentResolver().delete(output,null,null);
            if (song != null) DownloadRuntime.removeTree(library.song(song.getString("id")));
            input.delete();DownloadRuntime.removeTree(directory);
            SharedPreferences.Editor audioRestore=audioPrefs.edit().clear();
            for (Map.Entry<String,?> entry:oldAudio.entrySet()) {
                Object value=entry.getValue();if(value instanceof String)audioRestore.putString(entry.getKey(),(String)value);
                else if(value instanceof Boolean)audioRestore.putBoolean(entry.getKey(),(Boolean)value);
            }
            audioRestore.commit();
            if(oldAudioCode==null)playbackPrefs.edit().remove(WifiStreamService.PREF_PAIRING_CODE).commit();
            else playbackPrefs.edit().putString(WifiStreamService.PREF_PAIRING_CODE,oldAudioCode).commit();
            prefs.edit().putString("pair",previousPair).putString("job",previousJob).putString("jobs",previousJobs).putString("target",previousTarget).commit();
            uiPrefs.edit().putString("selected",previousSong).putString("lastExport",previousExport).putString("lastExtension",previousExtension).commit();
        }
    }
    private static void open(Context context) throws Exception {
        activity = (VisualMusicActivity)instrumentation.startActivitySync(new Intent(context,VisualMusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitJs("!!document.querySelector('#stage canvas')",20000);
    }
    private static Object js(String script) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();instrumentation.runOnMainSync(() -> activity.web.evaluateJavascript(script,result::complete));
        return new JSONTokener(result.get(30,TimeUnit.SECONDS)).nextValue();
    }
    private static void waitJs(String condition, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            if (Boolean.TRUE.equals(js(condition))) return;
            if (Boolean.TRUE.equals(js("document.getElementById('status').classList.contains('error')"))) throw new AssertionError(js("document.getElementById('status').textContent"));
            Thread.sleep(100);
        }
        throw new AssertionError("UI timeout: " + condition + "\n" + js("document.body.innerText"));
    }
}
