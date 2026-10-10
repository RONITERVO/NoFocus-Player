using NAudio.Wave;

namespace NoFocus.Desktop;

/// <summary>Bounded, pull-driven PCM playout. Reorders UDP and rejects duplicates after playout.</summary>
internal sealed class PhoneAudioBuffer(int targetPackets) : IWaveProvider
{
    private readonly object gate = new();
    private readonly SortedDictionary<long, byte[]> packets = [];
    private readonly int target = Math.Clamp(targetPackets, 2, 8);
    private long expected = -1;
    private byte[]? current;
    private int offset;
    private bool primed;
    private long received, missing, trimmed, nonSilent;
    private long generation;
    internal object SyncRoot => gate;
    internal int TargetFrames => target * Protocol.FramesPerPacket;
    internal int BufferedFrames { get { lock (gate) return packets.Count * Protocol.FramesPerPacket + (current == null ? 0 : (current.Length - offset) / 4); } }
    internal long Generation { get { lock (gate) return generation; } }
    public WaveFormat WaveFormat { get; } = new(Protocol.SampleRate, 16, Protocol.Channels);
    internal long Received { get { lock (gate) return received; } }
    internal long Missing { get { lock (gate) return missing; } }
    internal long Trimmed { get { lock (gate) return trimmed; } }
    internal long NonSilent { get { lock (gate) return nonSilent; } }
    internal int Queued { get { lock (gate) return packets.Count; } }

    internal void Reset()
    {
        lock (gate) { packets.Clear(); expected = -1; current = null; offset = 0; primed = false; generation++; }
    }

    internal bool Offer(uint sequence, byte[] pcm)
    {
        if (pcm.Length != Protocol.PcmBytesPerPacket) return false;
        lock (gate)
        {
            if (expected < 0) expected = sequence;
            if (sequence < expected || packets.ContainsKey(sequence)) return false;
            // A scheduler stall or long loss must recover to current audio, never grow delay.
            if (sequence - expected > 24)
            {
                trimmed += sequence - expected;
                generation++;
                packets.Clear(); current = null; offset = 0; primed = false; expected = sequence;
            }
            packets.Add(sequence, pcm); received++;
            if (pcm.AsSpan().IndexOfAnyExcept((byte)0) >= 0) nonSilent++;
            if (packets.Count > target + 8)
            {
                long next = packets.Keys.Last() - target + 1;
                trimmed += next - expected;
                generation++;
                foreach (long old in packets.Keys.TakeWhile(value => value < next).ToArray()) packets.Remove(old);
                expected = next;
            }
            return true;
        }
    }

    public int Read(byte[] buffer, int bufferOffset, int count)
    {
        lock (gate)
        {
            Array.Clear(buffer, bufferOffset, count);
            int copied = 0;
            while (copied < count)
            {
                if (current == null)
                {
                    if (!primed)
                    {
                        if (packets.Count < target) break;
                        primed = true;
                    }
                    if (!packets.Remove(expected++, out current))
                    {
                        missing++;
                        current = new byte[Protocol.PcmBytesPerPacket];
                        // Rebuffer after an outage. Keep the sequence floor to reject replays.
                        if (packets.Count == 0) primed = false;
                    }
                    offset = 0;
                }
                int length = Math.Min(count - copied, current.Length - offset);
                Buffer.BlockCopy(current, offset, buffer, bufferOffset + copied, length);
                copied += length; offset += length;
                if (offset == current.Length) current = null;
            }
            return count;
        }
    }
}
