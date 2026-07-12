package dev.nofocus.folderplayer;

import org.junit.Test;

import static org.junit.Assert.*;

public class AudioJitterBufferTest {
    @Test
    public void reordersPacketsAndRejectsDuplicates() {
        AudioJitterBuffer buffer = new AudioJitterBuffer(8);
        byte[] first = new byte[]{1};
        byte[] second = new byte[]{2};
        assertTrue(buffer.offer(10, first));
        assertTrue(buffer.offer(12, new byte[]{3}));
        assertTrue(buffer.offer(11, second));
        assertFalse(buffer.offer(11, second));
        assertEquals(0, buffer.getRejectedPackets());
        assertArrayEquals(first, buffer.takeNext());
        assertArrayEquals(second, buffer.takeNext());
        assertArrayEquals(new byte[]{3}, buffer.takeNext());
    }

    @Test
    public void lateRetransmissionIsNotDoubleCountedAsLoss() {
        AudioJitterBuffer buffer = new AudioJitterBuffer(8);
        buffer.offer(20, new byte[]{1});
        assertNotNull(buffer.takeNext());
        assertFalse(buffer.offer(20, new byte[]{1}));
        assertEquals(0, buffer.getRejectedPackets());
    }

    @Test
    public void missingPacketAdvancesSequenceForRealtimePlayback() {
        AudioJitterBuffer buffer = new AudioJitterBuffer(8);
        buffer.offer(4, new byte[]{4});
        buffer.offer(6, new byte[]{6});
        assertNotNull(buffer.takeNext());
        assertNull(buffer.takeNext());
        assertArrayEquals(new byte[]{6}, buffer.takeNext());
    }

    @Test
    public void rejectsUnboundedFuturePacket() {
        AudioJitterBuffer buffer = new AudioJitterBuffer(4);
        assertTrue(buffer.offer(1, new byte[]{1}));
        assertFalse(buffer.offer(100, new byte[]{2}));
    }
}
