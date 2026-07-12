package dev.nofocus.folderplayer;

import java.util.Map;
import java.util.TreeMap;

/** Small, bounded reorder buffer. It never grows latency without limit. */
final class AudioJitterBuffer {
    private final TreeMap<Long, byte[]> packets = new TreeMap<>();
    private final int capacity;
    private long expectedSequence = -1;
    private long rejectedPackets;

    AudioJitterBuffer(int capacity) {
        if (capacity < 4) {
            throw new IllegalArgumentException("capacity must be at least 4");
        }
        this.capacity = capacity;
    }

    synchronized void reset() {
        packets.clear();
        expectedSequence = -1;
        rejectedPackets = 0;
    }

    synchronized boolean offer(long sequence, byte[] pcm) {
        if (sequence < 0 || pcm == null || pcm.length == 0) {
            return false;
        }
        if (expectedSequence < 0) {
            expectedSequence = sequence;
        }
        // Exact retransmissions and packets that arrived after playout are expected on UDP and are not
        // separate loss events. The playout side already counts a missing sequence once.
        if (sequence < expectedSequence || packets.containsKey(sequence)) {
            return false;
        }
        if (sequence - expectedSequence > capacity * 4L) {
            rejectedPackets++;
            return false;
        }
        packets.put(sequence, pcm);
        while (packets.size() > capacity) {
            Map.Entry<Long, byte[]> removed = packets.pollFirstEntry();
            if (removed != null && removed.getKey() >= expectedSequence) {
                expectedSequence = removed.getKey() + 1;
            }
            rejectedPackets++;
        }
        notifyAll();
        return true;
    }

    synchronized byte[] takeNext() {
        if (expectedSequence < 0) {
            return null;
        }
        byte[] result = packets.remove(expectedSequence);
        expectedSequence++;
        return result;
    }

    synchronized int size() {
        return packets.size();
    }

    synchronized long getRejectedPackets() {
        return rejectedPackets;
    }

    synchronized int discardOldest(int count) {
        int discarded = 0;
        while (discarded < count && expectedSequence >= 0) {
            packets.remove(expectedSequence);
            expectedSequence++;
            discarded++;
        }
        return discarded;
    }
}
