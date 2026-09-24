package dev.nofocus.folderplayer;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SongDownloadSpecTest {
    @Test public void recognizesSingleVideosWithoutPlaylistOrTracking() {
        String id = "BaW_jenozKc";
        for (String text : new String[]{id, "https://youtu.be/" + id + "?si=x", "https://music.youtube.com/watch?v=" + id + "&list=ignored",
                "https://www.youtube.com/shorts/" + id, "youtube.com/watch?v=" + id})
            assertEquals("https://www.youtube.com/watch?v=" + id, SongDownloadSpec.videoUrl(text));
    }
    @Test public void rejectsNonYoutubeUrlsAndCommands() {
        for (String text : new String[]{"", "https://youtube.com.evil.test/watch?v=BaW_jenozKc", "file:///etc/passwd",
                "https://youtube.com/playlist?list=x", "--exec calc.exe", "https://me@youtube.com/watch?v=BaW_jenozKc",
                "https://youtube.com:5000/watch?v=BaW_jenozKc", "https://youtu.be/a/b"}) {
            try { SongDownloadSpec.videoUrl(text); fail(text); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void outputFormatAndPathAreSeparateArguments() {
        for (String format : SongDownloadSpec.FORMATS) {
            List<String> args = SongDownloadSpec.arguments("BaW_jenozKc", format, "/My songs/track.%(ext)s");
            assertEquals(format, args.get(args.indexOf("--audio-format") + 1));
            assertEquals("/My songs/track.%(ext)s", args.get(args.indexOf("-o") + 1));
            assertEquals("--", args.get(args.size() - 2));
            assertTrue(args.contains("--no-playlist"));
        }
        try { SongDownloadSpec.arguments("BaW_jenozKc", "mp4", "x"); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void namesAndMimeTypesMatchRealAudioFormats() {
        assertEquals("a_b_c_d_", SongDownloadSpec.safeName("a/b:c\\d\n"));
        assertEquals("audio/mpeg", SongDownloadSpec.mime("mp3"));
        assertEquals("audio/mp4", SongDownloadSpec.mime("m4a"));
        assertEquals("audio/wav", SongDownloadSpec.mime("wav"));
        assertEquals(90, SongDownloadSpec.safeName(new String(new char[200]).replace('\0', 'a')).length());
        String international = SongDownloadSpec.safeName(new String(new char[200]).replace('\0', '曲'));
        assertTrue(international.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 180);
    }
}
