package dev.nofocus.folderplayer;

import java.util.Locale;

/** Validated recording duration; countdown and preparation are never part of it. */
final class CaptureTiming {
    static final int[] START_DELAYS = {0, 3, 5, 10, 30};
    final int delaySeconds;
    final long durationMillis;

    CaptureTiming(int delaySeconds, long durationMillis) {
        boolean supported = false;
        for (int delay : START_DELAYS) if (delay == delaySeconds) supported = true;
        if (!supported) throw new IllegalArgumentException("Choose a start countdown from the list.");
        if (durationMillis < 1000 || durationMillis > CaptureFiles.MAX_MILLIS || durationMillis % 1000 != 0)
            throw new IllegalArgumentException("Stop after must be between 0:01 and 10:00.");
        this.delaySeconds = delaySeconds; this.durationMillis = durationMillis;
    }

    static long parseDuration(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return CaptureFiles.MAX_MILLIS;
        if (!value.matches("\\d{1,2}:[0-5]\\d")) throw new IllegalArgumentException("Enter stop duration as m:ss, for example 3:30.");
        String[] parts = value.split(":");
        long millis = (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
        return new CaptureTiming(0, millis).durationMillis;
    }

    long remaining(long recordingStartedAt, long now) {
        return Math.max(0, durationMillis - Math.max(0, now - recordingStartedAt));
    }

    static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
