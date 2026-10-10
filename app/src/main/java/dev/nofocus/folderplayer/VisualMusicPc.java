package dev.nofocus.folderplayer;

import android.content.*;
import android.net.Uri;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;

/** Pinned TLS client. Network operations run outside the UI and WebView bridge threads. */
@android.annotation.TargetApi(24)
@android.annotation.SuppressLint("ApplySharedPref") // Durable job/identity writes run on workers and must complete before network mutations.
final class VisualMusicPc {
    private final VisualMusicLibrary library;
    private final SharedPreferences prefs;
    private volatile HttpsURLConnection active;
    private volatile boolean cancelled;
    void startOperation() { cancelled = false; }
    VisualMusicPc(VisualMusicLibrary library) { this.library = library; prefs = library.context.getSharedPreferences("visual-pc", 0); }
    String rendererVersion() throws Exception {
        try (InputStream in = library.context.getAssets().open("visualizer/renderer-version.txt")) { return VisualMusicLibrary.text(in).trim(); }
    }
    static JSONObject parsePairing(String code) throws Exception {
        code = code.trim();
        if (code.startsWith("nofocus-export://pair?data=")) code = "nofocus-pc-v1:" + Uri.parse(code).getQueryParameter("data");
        if (!code.startsWith("nofocus-pc-v1:") || code.length() > 4096) throw new IOException("Paste the pairing code from the NoFocus PC companion.");
        JSONObject pair = new JSONObject(new String(Base64.decode(code.substring(14), Base64.URL_SAFE | Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8));
        URI url = new URI(pair.getString("url"));
        if (pair.getInt("version") != 1 || !"https".equals(url.getScheme()) || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
                || !(url.getRawPath().isEmpty() || "/".equals(url.getRawPath())) || url.getPort() < 1024 || url.getPort() > 65535 || !localAddress(url.getHost())
                || !pair.getString("fingerprint").matches("[a-f0-9]{64}") || !pair.getString("token").matches("[a-f0-9]{64}")
                || pair.optString("name").length() > 160) throw new IOException("Invalid local PC pairing code.");
        pair.put("url", "https://" + url.getHost() + ":" + url.getPort()); return pair;
    }
    private static boolean localAddress(String host) {
        if (host == null || !host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        String[] parts = host.split("\\."); int[] ip = new int[4];
        try { for (int i = 0; i < 4; i++) { ip[i] = Integer.parseInt(parts[i]); if (ip[i] < 0 || ip[i] > 255) return false; } }
        catch (NumberFormatException error) { return false; }
        return ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168) || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)
                || (ip[0] == 169 && ip[1] == 254) || (ip[0] == 100 && ip[1] >= 64 && ip[1] <= 127);
    }
    JSONObject settings() throws Exception {
        JSONObject pair = pairing();
        return new JSONObject().put("paired", pair != null).put("name", pair == null ? "" : pair.optString("name"))
                .put("target", prefs.getString("target", "auto")).put("pending", hasJob()).put("jobs", localJobs());
    }
    void target(String target) throws IOException {
        if (!Arrays.asList("auto", "phone", "pc").contains(target)) throw new IOException("Invalid export destination.");
        prefs.edit().putString("target", target).apply();
    }
    JSONObject pair(String code) throws Exception {
        if (hasJob()) throw new IOException("Finish or remove the pending PC exports before changing pairing.");
        JSONObject pair = parsePairing(code); JSONObject info = request(pair, "GET", "/v1/info", null);
        checkVersion(info); pair.put("name", info.getString("name"));
        prefs.edit().putString("pair", pair.toString()).commit(); return settings();
    }
    void forget() throws Exception {
        if (hasJob()) throw new IOException("Finish or remove the pending PC exports before forgetting this PC.");
        prefs.edit().remove("pair").apply();
    }
    private JSONObject pairing() throws JSONException { String value = prefs.getString("pair", ""); return value.isEmpty() ? null : new JSONObject(value); }
    // Migrate the original single-job preference without changing its remote id
    // or configuration. Uploads and downloads can then resume after an upgrade.
    private synchronized JSONArray jobs() throws JSONException {
        String legacy = prefs.getString("job", "");
        JSONArray jobs = new JSONArray(prefs.getString("jobs", "[]"));
        if (!legacy.isEmpty()) {
            JSONObject previous = new JSONObject(legacy);
            String id = previous.getJSONObject("config").getString("id"); boolean found = false;
            for (int i = 0; i < jobs.length(); i++) if (jobs.getJSONObject(i).getJSONObject("config").getString("id").equals(id)) found = true;
            if (!found) jobs.put(previous);
            prefs.edit().putString("jobs", jobs.toString()).remove("job").commit();
        }
        return jobs;
    }
    boolean hasJob() { return !prefs.getString("job", "").isEmpty() || !prefs.getString("jobs", "[]").equals("[]"); }
    boolean usesSong(String id) throws Exception {
        JSONArray jobs = jobs();
        for (int i = 0; i < jobs.length(); i++) if (jobs.getJSONObject(i).optString("songId").equals(id)) return true;
        return false;
    }
    private synchronized void add(JSONObject job) throws Exception {
        JSONArray jobs = jobs();jobs.put(job);prefs.edit().putString("jobs", jobs.toString()).commit();
    }
    private synchronized void retire(String id) throws Exception {
        JSONArray jobs = jobs(), remaining = new JSONArray();
        for (int i = 0; i < jobs.length(); i++) if (!jobs.getJSONObject(i).getJSONObject("config").getString("id").equals(id)) remaining.put(jobs.getJSONObject(i));
        prefs.edit().putString("jobs", remaining.toString()).commit();
    }
    private JSONArray localJobs() throws Exception {
        JSONArray jobs = jobs(), result = new JSONArray();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject config = jobs.getJSONObject(i).getJSONObject("config");
            result.put(new JSONObject().put("id", config.getString("id")).put("title", config.getString("title")).put("status", "unknown"));
        }
        return result;
    }
    JSONObject queue() throws Exception {
        JSONObject remote = request(requirePair(), "GET", "/v1/jobs", null);
        JSONArray known = localJobs(), all = remote.getJSONArray("jobs"), result = new JSONArray();
        for (int i = 0; i < known.length(); i++) {
            JSONObject item = known.getJSONObject(i); boolean found = false;
            for (int j = 0; j < all.length(); j++) if (item.getString("id").equals(all.getJSONObject(j).getString("id"))) { result.put(all.getJSONObject(j));found = true;break; }
            if (!found) result.put(item.put("status", "missing"));
        }
        return new JSONObject().put("jobs", result);
    }
    private JSONObject requirePair() throws Exception { JSONObject pair = pairing(); if (pair == null) throw new IOException("Pair the PC companion first."); return pair; }
    private void checkVersion(JSONObject info) throws Exception {
        if (info.optInt("protocol") != 2 || !rendererVersion().equals(info.optString("rendererVersion"))) throw new IOException("Update the phone and PC companion together: their export protocol and renderers must match.");
    }
    JSONObject available() throws Exception {
        JSONObject pair = pairing();
        if (pair == null) return new JSONObject().put("available", false).put("reason", "No PC paired");
        try { JSONObject result = request(pair, "GET", "/v1/info", null); checkVersion(result); return result.put("available", true); }
        catch (Exception error) { return new JSONObject().put("available", false).put("reason", error.getMessage()); }
    }
    JSONObject begin(JSONObject song, JSONObject options, DownloadRuntime.Progress progress) throws Exception {
        if (jobs().length() >= 16) throw new IOException("Save or remove an existing PC export first (16 retained jobs).");
        JSONObject pair = requirePair(); checkVersion(request(pair, "GET", "/v1/info", null));
        File dir = library.song(song.getString("id"));
        JSONObject config = new JSONObject();
        for (String key : new String[]{"width", "height", "fps", "mode", "audio"}) config.put(key, options.get(key));
        for (String key : new String[]{"title", "duration", "theme", "codec", "segments"}) config.put(key, song.get(key));
        config.put("id", UUID.randomUUID().toString()).put("rendererVersion", rendererVersion())
                .put("sourceBytes", new File(dir, "source").length()).put("analysisBytes", new File(dir, "analysis.f32").length());
        JSONObject saved = new JSONObject().put("songId", song.getString("id")).put("config", config);
        // Persist before creating the remote job: retrying uses the same id after an uncertain response.
        add(saved);
        return resume(config.getString("id"), progress);
    }
    JSONObject resume(DownloadRuntime.Progress progress) throws Exception { return resume("", progress); }
    JSONObject resume(String id, DownloadRuntime.Progress progress) throws Exception {
        JSONObject pair = requirePair(), saved = saved(id), config = saved.getJSONObject("config");
        checkVersion(request(pair, "GET", "/v1/info", null));
        JSONObject job = request(pair, "POST", "/v1/jobs", config);
        if (job.getString("status").equals("uploading")) {
            File directory = library.song(saved.getString("songId"));
            for (String name : new String[]{"source", "analysis"}) {
                File file = new File(directory, name.equals("analysis") ? "analysis.f32" : "source");
                if (file.length() != config.getLong(name.equals("source") ? "sourceBytes" : "analysisBytes")) throw new IOException("Source changed. Remove this PC job and start a new export.");
                upload(pair, config.getString("id"), name, file, progress);
            }
        }
        if (Arrays.asList("uploading", "paused", "failed").contains(job.getString("status")))
            job = request(pair, "POST", path(config) + "/start", new JSONObject());
        return job;
    }
    private JSONObject saved(String id) throws Exception {
        JSONArray jobs = jobs();
        // Keep the original native API for existing callers; UI actions always
        // carry a job id so polling or another completed job cannot retarget them.
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject saved = jobs.getJSONObject(i);
            if (id.isEmpty() || saved.getJSONObject("config").getString("id").equals(id)) return saved;
        }
        throw new IOException("This PC export is no longer pending.");
    }
    private String path(JSONObject config) throws JSONException { return "/v1/jobs/" + config.getString("id"); }
    JSONObject status() throws Exception { return status(""); }
    JSONObject status(String id) throws Exception { return request(requirePair(), "GET", path(saved(id).getJSONObject("config")), null); }
    JSONObject pause(String id) throws Exception { return request(requirePair(), "POST", path(saved(id).getJSONObject("config")) + "/pause", new JSONObject()); }
    void remove() throws Exception { remove(""); }
    void remove(String id) throws Exception {
        JSONObject pair = requirePair(), config = saved(id).getJSONObject("config");
        try { request(pair, "DELETE", path(config), null); }
        catch (RemoteException error) { if (error.code != 404) throw error; }
        retire(config.getString("id"));
    }
    void cancelIo() { cancelled = true; HttpsURLConnection connection = active; if (connection != null) connection.disconnect(); }
    JSONObject download(DownloadRuntime.Progress progress) throws Exception { return download("", progress); }
    JSONObject download(String id, DownloadRuntime.Progress progress) throws Exception {
        JSONObject saved = saved(id), config = saved.getJSONObject("config"), pair = requirePair();
        JSONObject job = request(pair, "GET", path(config), null);
        if (!job.getString("status").equals("complete")) throw new IOException("The PC is still exporting.");
        String extension = job.getString("extension");
        if (!extension.equals("mkv") && !extension.equals("mp4")) throw new IOException("Invalid PC output format.");
        File temporary = File.createTempFile("pc-export-", "." + extension, library.context.getCacheDir());
        HttpsURLConnection connection = null;
        try {
            connection = connect(pair, "GET", path(config) + "/file"); active = connection; check(connection);
            long length = connection.getContentLengthLong();
            if (length < 1 || length > temporary.getUsableSpace() / 2 - 128L * 1024 * 1024) throw new IOException("Free more phone storage to save this PC export.");
            String expected = connection.getHeaderField("X-Content-SHA256");
            if (expected == null || !expected.matches("[a-f0-9]{64}")) throw new IOException("PC export checksum is missing.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long received = 0, reported = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(temporary)) {
                byte[] bytes = new byte[65536]; int count;
                while ((count = in.read(bytes)) != -1) {
                    if (cancelled) throw new IOException("Download paused. Return to Export to retry.");
                    received += count; if (received > length) throw new IOException("Invalid download size.");
                    out.write(bytes, 0, count); digest.update(bytes, 0, count);
                    if (received - reported > 1024 * 1024) { progress.report("Saving from PC… " + (received * 100 / length) + "%"); reported = received; }
                }
            }
            if (received != length || !expected.equals(hex(digest.digest()))) throw new IOException("Incomplete PC download. Try saving it again.");
            if (cancelled) throw new IOException("Download paused.");
            String title = config.getString("title").replaceAll("[^\\p{L}\\p{N} _-]", ""); if (title.length() > 80) title = title.substring(0, 80);
            Uri uri = library.publish(temporary, (title.isEmpty() ? "Visualizer" : title) + "-visualizer." + extension, extension.equals("mp4") ? "video/mp4" : "video/x-matroska");
            // Publish first, then retire the pending entry. A network failure cannot lose the phone copy.
            retire(config.getString("id"));
            try { request(pair, "DELETE", path(config), null); } catch (Exception ignored) { }
            return new JSONObject().put("uri", uri.toString()).put("extension", extension).put("encoder", "Paired PC");
        } finally { if (connection != null) connection.disconnect(); active = null; temporary.delete(); }
    }
    private void upload(JSONObject pair, String id, String name, File file, DownloadRuntime.Progress progress) throws Exception {
        if (cancelled) throw new IOException("Upload paused. Return to Export to retry.");
        HttpsURLConnection connection = connect(pair, "PUT", "/v1/jobs/" + id + "/" + name); active = connection;
        try {
            connection.setDoOutput(true); connection.setFixedLengthStreamingMode(file.length());connection.setRequestProperty("Content-Type", "application/octet-stream");
            long sent = 0, reported = -1024 * 1024;
            try (InputStream in = new FileInputStream(file); OutputStream out = connection.getOutputStream()) {
                byte[] bytes = new byte[65536]; int count;
                while ((count = in.read(bytes)) != -1) {
                    if (cancelled) throw new IOException("Upload paused. Return to Export to retry.");
                    out.write(bytes, 0, count); sent += count;
                    if (sent - reported > 1024 * 1024) { progress.report("Sending " + (name.equals("source") ? "original media" : "visualization analysis") + " to PC… " + (sent * 100 / file.length()) + "%"); reported = sent; }
                }
            }
            check(connection);
            try (InputStream in = connection.getInputStream()) { VisualMusicLibrary.text(in); }
        } finally { connection.disconnect(); active = null; }
    }
    private JSONObject request(JSONObject pair, String method, String route, JSONObject body) throws Exception {
        if (cancelled) throw new IOException("PC transfer paused.");
        HttpsURLConnection connection = connect(pair, method, route); active = connection;
        try {
            if (body != null) {
                byte[] data = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                connection.setDoOutput(true);connection.setFixedLengthStreamingMode(data.length);connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            }
            check(connection);
            try (InputStream in = connection.getInputStream()) { return new JSONObject(VisualMusicLibrary.text(in)); }
        } finally { connection.disconnect(); active = null; }
    }
    private void check(HttpsURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        if (code >= 200 && code < 300) return;
        String message = "PC returned HTTP " + code;
        try (InputStream in = connection.getErrorStream()) { if (in != null) message = new JSONObject(VisualMusicLibrary.text(in)).optString("error", message); }
        catch (Exception ignored) { }
        throw new RemoteException(code, message);
    }
    private HttpsURLConnection connect(JSONObject pair, String method, String route) throws Exception {
        String fingerprint = pair.getString("fingerprint");
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("Client certificates are not used."); }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    if (chain.length == 0) throw new CertificateException("Missing PC certificate.");
                    chain[0].checkValidity();
                    if (!MessageDigest.isEqual(fingerprint.getBytes(java.nio.charset.StandardCharsets.US_ASCII), hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new CertificateException("PC identity changed. Pair it again.");
                } catch (GeneralSecurityException error) { throw new CertificateException(error); }
            }
        };
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new TrustManager[]{trust}, null);
        HttpsURLConnection connection = (HttpsURLConnection)new URL(pair.getString("url") + route).openConnection();
        connection.setSSLSocketFactory(ssl.getSocketFactory());
        // The exact certificate fingerprint is the identity, independent of changing LAN addresses.
        connection.setHostnameVerifier((host, session) -> true);
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(4000);connection.setReadTimeout(30000);
        connection.setRequestMethod(method);connection.setRequestProperty("Authorization", "Bearer " + pair.getString("token"));
        return connection;
    }
    private static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));return result.toString(); }
    private static final class RemoteException extends IOException { final int code; RemoteException(int code, String text) { super(text); this.code = code; } }
}
