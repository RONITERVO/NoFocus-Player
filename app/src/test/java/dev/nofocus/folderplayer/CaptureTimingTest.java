package dev.nofocus.folderplayer;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureTimingTest {
    @Test public void songDurationsUseMinutesAndSecondsWithAHardRecordingLimit() {
        assertEquals(210000, CaptureTiming.parseDuration("3:30"));
        assertEquals(1000, CaptureTiming.parseDuration(" 0:01 "));
        assertEquals(600000, CaptureTiming.parseDuration("10:00"));
        assertEquals(600000, CaptureTiming.parseDuration(""));
        for (String invalid : new String[]{"3:60", "3:5", "0:00", "10:01", "99:59", "-1:00", "1:00:00", "Infinity"}) {
            try { CaptureTiming.parseDuration(invalid); fail("Accepted " + invalid); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void preparationAndCountdownNeverConsumeRecordingDuration() {
        CaptureTiming schedule = new CaptureTiming(30, 210000);
        long recordingStart = 120000; // Start was tapped after a long preparation and countdown.
        assertEquals(210000, schedule.remaining(recordingStart, recordingStart));
        assertEquals(1000, schedule.remaining(recordingStart, recordingStart + 209000));
        assertEquals(0, schedule.remaining(recordingStart, recordingStart + 210000));
        assertEquals(0, schedule.remaining(recordingStart, recordingStart + 300000));
    }

    @Test public void serviceRejectsInvalidTimerExtrasInsteadOfRecordingIndefinitely() {
        for (long duration : new long[]{-1, 0, 999, 1001, 600001, Long.MAX_VALUE}) {
            try { new CaptureTiming(3, duration); fail("Accepted " + duration); }
            catch (IllegalArgumentException expected) { }
        }
        try { new CaptureTiming(60, 1000); fail("Accepted unsupported countdown"); }
        catch (IllegalArgumentException expected) { }
        assertEquals("3:30", CaptureTiming.clock(210000));
    }
}
