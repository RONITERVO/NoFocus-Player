package dev.nofocus.folderplayer;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import androidx.webkit.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Local-only WebView. No remote documents or picked files ever execute JavaScript. */
public final class VisualMusicActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    WebView web;
    private VisualMusicLibrary library;
    private VisualMusicPc pc;
    private volatile boolean pcTransfer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile DownloadRuntime importer;
    private volatile VisualMusicExport export;
    private volatile boolean destroyed;
    private String pickerRequest;
    private boolean pickerMedia;
    private String lastExportUri = "", lastExtension = "";
    private boolean visualActive;
    private boolean binaryFrames;
    private int cancelGeneration;
    private final ExecutorService frameWorker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean framePending = new java.util.concurrent.atomic.AtomicBoolean();

    @android.annotation.SuppressLint("RequiresFeature") // binaryFrames checks both WebView capabilities before registration.
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        library = new VisualMusicLibrary(this);
        pc = new VisualMusicPc(library);
        web = new WebView(this); web.setBackgroundColor(Color.rgb(13, 23, 27));
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        web.addJavascriptInterface(new Bridge(), "Native");
        binaryFrames = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER);
        if (binaryFrames) WebViewCompat.addWebMessageListener(web, "NativeFrames", Collections.singleton(ORIGIN), (view, msg, origin, mainFrame, reply) -> {
            if (!mainFrame || !ORIGIN.equals(origin.toString()) || msg.getType() != WebMessageCompat.TYPE_ARRAY_BUFFER) return;
            byte[] packet = msg.getArrayBuffer();
            VisualMusicExport job = export;
            if (job == null || packet.length != job.width * job.height * 4 + 40 || !framePending.compareAndSet(false, true)) {
                reply.postMessage("Invalid or concurrent frame."); return;
            }
            frameWorker.execute(() -> {
                String result = "";
                try {
                    String id = new String(packet, 0, 36, java.nio.charset.StandardCharsets.US_ASCII);
                    int index = java.nio.ByteBuffer.wrap(packet, 36, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                    if (export != job || !job.id.equals(id)) throw new IOException("Export cancelled.");
                    job.frameBytes(index, Arrays.copyOfRange(packet, 40, packet.length));
                } catch (Throwable error) { result = message(error); }
                finally { framePending.set(false); }
                String response = result;
                runOnUiThread(() -> { if (!destroyed) reply.postMessage(response); });
            });
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(VisualMusicActivity.this).setMessage(message).setPositiveButton("Remove", (d, w) -> result.confirm())
                        .setNegativeButton("Cancel", (d, w) -> result.cancel()).setOnCancelListener(d -> result.cancel()).show(); return true;
            }
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                android.util.Log.d("VisualMusicWeb", message.message() + " @" + message.lineNumber()); return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    Uri uri = request.getUrl();
                    if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost()) || !"GET".equals(request.getMethod())) return blocked();
                    String path = uri.getPath();
                    if (path != null && path.matches("/analysis/[a-f0-9-]{36}"))
                        return response("application/octet-stream", new FileInputStream(new File(library.song(uri.getLastPathSegment()), "analysis.f32")));
                    if (path == null || !path.startsWith("/visualizer/") || path.contains("..") || !path.matches("/[a-zA-Z0-9_./-]+")) return blocked();
                    String mime = path.endsWith(".js") ? "application/javascript" : path.endsWith(".css") ? "text/css" : path.endsWith(".ttf") ? "font/ttf" : path.endsWith(".html") ? "text/html" : "text/plain";
                    return response(mime, getAssets().open(path.substring(1)));
                } catch (Exception error) { return blocked(); }
            }
        });
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setBackgroundColor(Color.rgb(13, 23, 27));
        container.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(container); container.requestApplyInsets();
        if (Build.VERSION.SDK_INT >= 30) getWindow().getInsetsController().setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        else getWindow().getDecorView().setSystemUiVisibility(0);
        web.loadUrl(ORIGIN + "/visualizer/index.html");
    }
    private WebResourceResponse response(String mime, InputStream input) {
        return new WebResourceResponse(mime, "UTF-8", 200, "OK", Collections.singletonMap("Cache-Control", "no-store"), input);
    }
    private WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0])); }
    private void script(String js) { runOnUiThread(() -> { if (!destroyed) web.evaluateJavascript(js, null); }); }
    private void reply(String id, Object data, Throwable error) {
        script("window.nativeReply(" + JSONObject.quote(id) + "," + (data == null ? "null" : data.toString()) + "," + (error == null ? "null" : JSONObject.quote(message(error))) + ")");
    }
    private static String message(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    private void progress(String text) { script("window.nativeProgress(" + JSONObject.quote(text) + ")"); }
    private void busy() {
        VisualMusicWorkService.cancel = this::cancel;
        runOnUiThread(() -> {
            if (destroyed) return;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Intent intent = new Intent(this, VisualMusicWorkService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        });
    }
    private void idle() {
        VisualMusicWorkService.cancel = null;
        runOnUiThread(() -> { if (!visualActive) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); stopService(new Intent(this, VisualMusicWorkService.class)); });
    }
    private void cancel() {
        synchronized (this) { cancelGeneration++; if (pc != null) pc.cancelIo(); }
        DownloadRuntime importing = importer; if (importing != null) importing.cancel();
        VisualMusicExport job = export; export = null;
        if (job != null) { job.cancel(); worker.execute(job::close); }
        script("window.nativeCancelled()"); idle();
    }
    final class Bridge {
        @JavascriptInterface public void visualActive(boolean active) {
            runOnUiThread(() -> {
                visualActive = active;
                if (active || export != null || importer != null || pcTransfer) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
        @JavascriptInterface public void send(String id, String action, String json) {
            if (destroyed || id.length() > 80) return;
            if (json.length() > 2 * 1024 * 1024) { reply(id, null, new IOException("Song data is too large. Import one song with less than 2 MB of timing data.")); return; }
            if ("cancel".equals(action)) { cancel(); reply(id, "true", null); return; }
            final int generation;
            synchronized (VisualMusicActivity.this) { generation = cancelGeneration; }
            worker.execute(() -> {
                try {
                    synchronized (VisualMusicActivity.this) {
                        if (generation != cancelGeneration) throw new IOException("Operation cancelled.");
                        if (action.startsWith("pc")) pc.startOperation();
                    }
                    Object result = handle(id, action, new JSONObject(json)); if (result != null) reply(id, result, null);
                }
                catch (Throwable error) { reply(id, null, error); }
            });
        }
        @JavascriptInterface public String state() {
            try { return new JSONObject().put("id", VisualMusicPlayback.songId).put("playing", VisualMusicPlayback.playing)
                    .put("seconds", VisualMusicPlayback.position()).put("error", VisualMusicPlayback.error).toString(); }
            catch (JSONException error) { return "{}"; }
        }
        @JavascriptInterface public String frame(String id, int index, String base64) {
            try {
                VisualMusicExport job = export;
                if (job == null || !job.id.equals(id)) throw new IOException("Export cancelled.");
                job.frame(index, base64); return "";
            } catch (Throwable error) { return message(error); }
        }
    }
    private Object handle(String request, String action, JSONObject data) throws Exception {
        if (Build.VERSION.SDK_INT < 29 && !"back".equals(action)) throw new IOException("Visual music needs Android 10 or newer.");
        switch (action) {
            case "list": return new JSONObject().put("songs", library.list()).put("selected", getPreferences(0).getString("selected", ""))
                    .put("pc", pc.settings())
                    .put("volume", getSharedPreferences(PlayerService.PREFS, 0).getFloat(PlayerService.PREF_VOLUME, 1))
                    .put("latest", getIntent().getBooleanExtra("latest", false));
            case "volume":
                float volume = (float)Math.max(0, Math.min(1, data.getDouble("value")));
                getSharedPreferences(PlayerService.PREFS, 0).edit().putFloat(PlayerService.PREF_VOLUME, volume).apply();
                VisualMusicPlayback.volume(volume); return "true";
            case "select": getPreferences(0).edit().putString("selected", data.getString("id")).apply(); return library.read(data.getString("id"));
            case "pickMedia": case "pickLyrics":
                if (pickerRequest != null) throw new IOException("Finish the open file picker first.");
                pickerRequest = request; pickerMedia = action.equals("pickMedia");
                runOnUiThread(() -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES, pickerMedia
                                ? new String[]{"audio/*", "video/*", "application/octet-stream"} : new String[]{"application/json", "text/*", "application/octet-stream"}), 75));
                return null;
            case "latest":
                String latest = getSharedPreferences(CaptureService.PREFS, 0).getString("master", "");
                if (latest.isEmpty()) throw new IOException("Capture a song first, or choose Import audio / video.");
                getIntent().removeExtra("latest");
                if (latest.equals(getPreferences(0).getString("latestUri", ""))) {
                    try { return library.read(getPreferences(0).getString("latestId", "")); } catch (Exception ignored) { }
                }
                JSONObject imported = importUri(Uri.parse(latest));
                getPreferences(0).edit().putString("latestUri", latest).putString("latestId", imported.getString("id")).apply(); return imported;
            case "save":
                JSONObject song = library.read(data.getString("id"));
                for (String key : new String[]{"title", "theme", "segments", "lyrics", "guide"}) if (data.has(key)) song.put(key, data.get(key));
                library.save(song); return song;
            case "delete":
                String deleted = data.getString("id");
                if (pc.usesSong(deleted)) throw new IOException("Finish or remove this song's PC export before removing it from the library.");
                if (deleted.equals(VisualMusicPlayback.songId)) runOnUiThread(() -> stopService(new Intent(this, VisualMusicPlayback.class)));
                DownloadRuntime.removeTree(library.song(deleted)); return "true";
            case "load": case "play": case "pause": case "seek":
                Intent player = new Intent(this, VisualMusicPlayback.class).setAction(action).putExtra("id", data.optString("id"))
                        .putExtra("seconds", data.optDouble("seconds", 0));
                runOnUiThread(() -> { if (Build.VERSION.SDK_INT >= 26 && (action.equals("play") || action.equals("load"))) startForegroundService(player); else if (action.equals("play") || action.equals("load") || VisualMusicPlayback.instance != null) startService(player); });
                return "true";
            case "beginExport":
                if (export != null || importer != null || pcTransfer) throw new IOException("Finish or cancel the current operation first.");
                JSONObject selected = library.read(data.getString("id"));
                selected.put("profile", data); library.save(selected);
                data.put("raw", binaryFrames && data.optBoolean("raw"));
                if (!data.optBoolean("raw") && "hardware".equals(data.optString("encoder")) && !"lossless".equals(data.optString("mode")))
                    throw new IOException("Hardware export needs an updated Android System WebView. Choose Automatic for compatibility.");
                VisualMusicExport job = new VisualMusicExport(library, selected, data); export = job; busy();
                try {
                    String encoder = job.ready.get(45, TimeUnit.SECONDS);
                    return new JSONObject().put("id", job.id).put("total", job.total).put("extension", job.extension).put("raw", job.raw).put("encoder", encoder);
                } catch (Exception error) { job.close(); export = null; idle(); throw error; }
            case "finishExport":
                VisualMusicExport finishing = export;
                if (finishing == null || !finishing.id.equals(data.getString("id"))) throw new IOException("Export cancelled.");
                try {
                    JSONObject result = finishing.finish(); lastExportUri = result.getString("uri"); lastExtension = result.getString("extension");
                    getPreferences(0).edit().putString("lastExport", lastExportUri).putString("lastExtension", lastExtension).apply(); return result;
                } finally { finishing.close(); export = null; idle(); }
            case "pcSettings": return pc.settings();
            case "pcPair": return pc.pair(data.getString("code"));
            case "pcForget": pc.forget(); return pc.settings();
            case "pcTarget": pc.target(data.getString("target")); return "true";
            case "pcAvailable": return pc.available();
            case "pcStatus": return pc.status();
            case "pcRemove": pc.remove(); return "true";
            case "pcBegin": case "pcResume": case "pcDownload":
                if (export != null || importer != null || pcTransfer) throw new IOException("Finish or cancel the current operation first.");
                pcTransfer = true; busy();
                try {
                    if (action.equals("pcResume")) return pc.resume(this::progress);
                    if (action.equals("pcDownload")) {
                        JSONObject result = pc.download(this::progress);
                        getPreferences(0).edit().putString("lastExport", result.getString("uri")).putString("lastExtension", result.getString("extension")).apply();
                        return result;
                    }
                    JSONObject pcSong = library.read(data.getString("id"));
                    pcSong.put("profile", data); library.save(pcSong);
                    return pc.begin(pcSong, data, this::progress);
                } finally { pcTransfer = false; idle(); }
            case "shareExport":
                String value = getPreferences(0).getString("lastExport", "");
                if (value.isEmpty()) throw new IOException("Export a video first.");
                share(Uri.parse(value), "mp4".equals(getPreferences(0).getString("lastExtension", "")) ? "video/mp4" : "video/x-matroska"); return "true";
            case "shareGemini":
                String video = getSharedPreferences(CaptureService.PREFS, 0).getString("gemini", "");
                if (video.isEmpty()) throw new IOException("Capture a lyrics video first."); share(Uri.parse(video), "video/mp4"); return "true";
            case "prompt":
                String prompt;
                try (InputStream in = getAssets().open("visualizer/gemini-prompt.txt")) { prompt = VisualMusicLibrary.text(in); }
                final String copy = prompt;
                runOnUiThread(() -> getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Gemini lyrics prompt", copy))); return "true";
            case "capture": runOnUiThread(() -> startActivity(new Intent(this, CaptureActivity.class))); return "true";
            case "back": runOnUiThread(this::onBackPressed); return "true";
            default: throw new IOException("Unknown command.");
        }
    }
    private JSONObject importUri(Uri uri) throws Exception {
        if (importer != null || export != null || pcTransfer) throw new IOException("Finish the current operation first.");
        DownloadRuntime runtime = new DownloadRuntime(this); importer = runtime; busy();
        try { return library.importMedia(uri, runtime, this::progress); }
        finally { importer = null; idle(); }
    }
    private void share(Uri uri, String mime) {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Visual music", uri)); startActivity(Intent.createChooser(intent, "Share video"));
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 75 || pickerRequest == null) return;
        String id = pickerRequest; pickerRequest = null; boolean media = pickerMedia;
        if (result != RESULT_OK || data == null || data.getData() == null) { reply(id, null, new IOException("File selection cancelled.")); return; }
        Uri uri = data.getData();
        worker.execute(() -> {
            try {
                if (media) reply(id, importUri(uri), null);
                else try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Cannot open lyrics.");
                    reply(id, new JSONObject().put("text", VisualMusicLibrary.text(in)), null);
                }
            } catch (Throwable error) { reply(id, null, error); }
        });
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); }
    @Override public void onBackPressed() {
        if (export != null || importer != null || pcTransfer) new AlertDialog.Builder(this).setMessage("Pause or cancel the current operation and leave? PC jobs can be resumed from Export.")
                .setNegativeButton("Stay", null).setPositiveButton("Cancel and leave", (d, w) -> { cancel(); finish(); }).show();
        else finish();
    }
    @Override protected void onStop() {
        if (export != null) { cancel(); progress("Phone export cancelled because the screen was closed. Keep it open during phone export."); }
        if (pcTransfer) { cancel(); progress("PC transfer paused. Return to Export to resume. Uploaded PC jobs keep running."); }
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true; cancel(); web.removeJavascriptInterface("Native"); web.destroy(); worker.shutdown(); frameWorker.shutdown(); super.onDestroy();
    }
}
