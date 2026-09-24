package dev.nofocus.folderplayer;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Input validation and CLI arguments shared by the UI and the background job. */
final class SongDownloadSpec {
    static final String[] FORMATS = {"mp3", "m4a", "wav"};
    static final String[] LABELS = {"MP3 · most apps", "M4A · smaller file", "WAV · larger file"};

    static String videoUrl(String input) {
        String text = input == null ? "" : input.trim();
        if (text.matches("[A-Za-z0-9_-]{11}")) return "https://www.youtube.com/watch?v=" + text;
        if (!text.contains("://")) text = "https://" + text;
        String id = "";
        try {
            URI uri = new URI(text);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getPort() != -1) throw new Exception();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            if ("youtu.be".equals(host) && path.matches("/[A-Za-z0-9_-]{11}/?")) id = path.substring(1, 12);
            else if (Arrays.asList("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com").contains(host)) {
                if (path.matches("/(shorts|embed|live)/[A-Za-z0-9_-]{11}/?")) id = path.split("/")[2];
                else if ("/watch".equals(path) && uri.getRawQuery() != null) {
                    for (String pair : uri.getRawQuery().split("&"))
                        if (pair.startsWith("v=")) id = URLDecoder.decode(pair.substring(2), "UTF-8");
                }
            }
        } catch (Exception error) { throw new IllegalArgumentException("Paste a YouTube song link."); }
        if (!id.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("Paste a song link, not a playlist.");
        return "https://www.youtube.com/watch?v=" + id;
    }

    static List<String> arguments(String url, String format, String output) {
        if (!Arrays.asList(FORMATS).contains(format)) throw new IllegalArgumentException("Choose MP3, M4A, or WAV.");
        return new ArrayList<>(Arrays.asList("--ignore-config", "--no-playlist", "--no-cache-dir", "--no-mtime",
                "--newline", "--no-colors", "--socket-timeout", "30", "--retries", "3", "--max-filesize", "500M",
                "--match-filters", "!is_live & duration <= 7200", "-f", "bestaudio/best", "--extract-audio",
                "--audio-format", format, "--audio-quality", "192K", "--write-info-json", "--no-embed-info-json",
                "-o", output, "--", videoUrl(url)));
    }

    static String mime(String format) {
        return "mp3".equals(format) ? "audio/mpeg" : "m4a".equals(format) ? "audio/mp4" : "audio/wav";
    }

    static String safeName(String title) {
        String name = title.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
        if (name.length() > 90) name = name.substring(0, 90);
        if (!name.isEmpty() && Character.isHighSurrogate(name.charAt(name.length() - 1))) name = name.substring(0, name.length() - 1);
        // Android filesystems limit a filename in bytes, not Java characters.
        while (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 180)
            name = name.substring(0, name.offsetByCodePoints(name.length(), -1));
        return name.isEmpty() ? "YouTube audio" : name;
    }

    static String friendlyError(String error) {
        String lower = error.toLowerCase(Locale.ROOT);
        if (lower.contains("sign in") || lower.contains("not a bot")) return "YouTube needs sign-in. Try another public song.";
        if (lower.contains("unavailable") || lower.contains("private video")) return "This video is unavailable. Try another link.";
        if (lower.contains("timed out")) return "Connection timed out. Check your internet and try again.";
        String message = "Download failed. Check the link and try again.";
        for (String line : error.split("\n")) if (line.startsWith("ERROR:")) message = line.substring(6).trim();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
