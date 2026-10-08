package dev.nofocus.folderplayer;

import org.junit.Test;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class CaptureFilesTest {
    @Test public void wavFinalizationPreservesEveryStereoSample() throws Exception {
        File file = File.createTempFile("capture", ".wav");
        byte[] pcm = new byte[192000]; new Random(17).nextBytes(pcm);
        try {
            try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                CaptureFiles.wavHeader(output, 0); output.write(pcm); CaptureFiles.wavHeader(output, pcm.length);
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(48000, header.getInt(24)); assertEquals(2, header.getShort(22)); assertEquals(16, header.getShort(34));
            assertEquals(pcm.length, header.getInt(40)); assertEquals(pcm.length + 36, header.getInt(4));
            assertArrayEquals(pcm, Arrays.copyOfRange(bytes, 44, bytes.length));
        } finally { file.delete(); }
    }
    @Test public void timelineAlignmentUsesWholeSamplesInEitherDirection() {
        assertEquals("atrim=start_sample=4800,asetpts=PTS-STARTPTS", CaptureFiles.alignment(1000000, 1100000));
        assertEquals("adelay=4800S:all=1,asetpts=PTS-STARTPTS", CaptureFiles.alignment(1100000, 1000000));
        assertEquals("adelay=0S:all=1,asetpts=PTS-STARTPTS", CaptureFiles.alignment(1000000, 1000000));
    }
    @Test public void geminiAndMasterUseIdenticalTimelineAndVideoWithoutAudioResampling() {
        List<String> master = CaptureFiles.mux(new File("video"), new File("pcm"), new File("master.mkv"), 2000000, 2120000, true);
        List<String> gemini = CaptureFiles.mux(new File("video"), new File("pcm"), new File("gemini.mp4"), 2000000, 2120000, false);
        assertEquals(master.get(master.indexOf("-af") + 1), gemini.get(gemini.indexOf("-af") + 1));
        assertEquals("copy", master.get(master.indexOf("-c:v") + 1)); assertEquals("copy", gemini.get(gemini.indexOf("-c:v") + 1));
        assertEquals("flac", master.get(master.indexOf("-c:a") + 1)); assertEquals("320k", gemini.get(gemini.indexOf("-b:a") + 1));
        assertFalse(master.contains("-ar")); assertFalse(master.contains("-ac"));
    }
}
