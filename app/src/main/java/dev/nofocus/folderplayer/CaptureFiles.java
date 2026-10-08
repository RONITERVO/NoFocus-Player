package dev.nofocus.folderplayer;

import java.io.*;
import java.util.*;

/** Pure file/timeline operations shared by capture and its regression tests. */
final class CaptureFiles {
    static final int RATE = 48000, CHANNELS = 2, BYTES_PER_FRAME = 4;
    static final long MAX_MILLIS = 10 * 60 * 1000;

    static void wavHeader(RandomAccessFile file, long bytes) throws IOException {
        wavHeader(file, bytes, false);
    }

    static void wavHeader(RandomAccessFile file, long bytes, boolean floating) throws IOException {
        int frameBytes = floating ? 8 : BYTES_PER_FRAME;
        if (bytes < 0 || bytes > 0xffffffffL - 36 || bytes % frameBytes != 0)
            throw new IOException("Invalid PCM size");
        file.seek(0);
        file.writeBytes("RIFF"); le(file, bytes + 36, 4); file.writeBytes("WAVEfmt ");
        le(file, 16, 4); le(file, floating ? 3 : 1, 2); le(file, CHANNELS, 2); le(file, RATE, 4);
        le(file, RATE * frameBytes, 4); le(file, frameBytes, 2); le(file, floating ? 32 : 16, 2);
        file.writeBytes("data"); le(file, bytes, 4);
    }

    private static void le(RandomAccessFile file, long value, int bytes) throws IOException {
        for (int n = 0; n < bytes; n++) file.write((int)(value >>> (n * 8)) & 255);
    }

    // Align to the first visible video frame using the same MONOTONIC clock.
    // Only whole PCM samples are trimmed or zero-padded; samples are never resampled.
    static String alignment(long audioStartUs, long videoStartUs) {
        long samples = Math.round((audioStartUs - videoStartUs) * RATE / 1_000_000.0);
        return samples < 0 ? "atrim=start_sample=" + (-samples) + ",asetpts=PTS-STARTPTS"
                : "adelay=" + samples + "S:all=1,asetpts=PTS-STARTPTS";
    }

    static List<String> mux(File video, File pcm, File output, long audioStartUs, long videoStartUs, boolean master) {
        return mux(video, pcm, output, audioStartUs, videoStartUs, master, false);
    }

    static List<String> mux(File video, File pcm, File output, long audioStartUs, long videoStartUs, boolean master, boolean floating) {
        return Arrays.asList("-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", video.getAbsolutePath(),
                "-i", pcm.getAbsolutePath(), "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy",
                "-af", alignment(audioStartUs, videoStartUs), "-c:a", master ? (floating ? "pcm_f32le" : "flac") : "aac",
                master ? "-compression_level" : "-b:a", master ? "5" : "320k", output.getAbsolutePath());
    }
}
