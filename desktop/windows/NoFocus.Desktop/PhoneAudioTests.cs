using System.Security.Cryptography;

namespace NoFocus.Desktop;

internal static class PhoneAudioTests
{
    private const string Code = "ABCDEFGHJKLMNPQR";
    private static void Check(bool value, string reason) { if (!value) throw new InvalidOperationException(reason); }
    private static byte[] Pcm(int value) => Enumerable.Repeat((byte)value, Protocol.PcmBytesPerPacket).ToArray();
    internal static void Run()
    {
        byte[] key = Protocol.PairingKey(Code);
        byte[] hello = Protocol.CreateHello(key, 7, "Desktop");
        // Golden v2 hashes were also verified with the retired Python sender.
        Check(Convert.ToHexString(SHA256.HashData(hello)).Equals(
            "ecafd3d9a8658ac4e47367c5ecbee76ddc9dc35e5ae63a9396a9cfc91cb3b8b9", StringComparison.OrdinalIgnoreCase), "Hello compatibility");
        using AesGcm aes = new(key, 16);
        byte[] source = Enumerable.Range(0, 960).Select(i => (byte)(i * 31)).ToArray();
        byte[] packet = Protocol.CreateAudio(aes, 7, 9, 2160, source);
        Check(Convert.ToHexString(SHA256.HashData(packet)).Equals(
            "710bf212c2fa9dcc850841445065ef9f88ae64532a01822805c25ae751f3b0ec", StringComparison.OrdinalIgnoreCase), "PCM compatibility");
        Check(Protocol.TryAudio(packet, aes, 7, out uint seq, out byte[] decoded) && seq == 9 &&
            decoded.SequenceEqual(source), "Exact PCM decrypt");
        packet[^1] ^= 1;
        Check(!Protocol.TryAudio(packet, aes, 7, out _, out _), "Reject modified audio");
        hello[48] ^= 1;
        Check(!Protocol.TryHello(hello, key, out _), "Reject modified hello");
        Check(!Protocol.TryHello([], key, out _) && !Protocol.TryAudio([], aes, 7, out _, out _), "Reject truncation");

        PhoneAudioBuffer buffer = new(2);
        buffer.Offer(0, Pcm(11)); buffer.Offer(2, Pcm(33)); buffer.Offer(1, Pcm(22));
        Check(!buffer.Offer(1, Pcm(99)), "Reject duplicate");
        byte[] output = new byte[2880]; buffer.Read(output, 0, 1444); buffer.Read(output, 1444, 1436);
        Check(output.SequenceEqual(Pcm(11).Concat(Pcm(22)).Concat(Pcm(33))), "Reorder and arbitrary read boundaries");
        Check(!buffer.Offer(0, Pcm(99)), "Reject already played audio");
        buffer.Reset(); buffer.Offer(0, Pcm(11)); buffer.Offer(2, Pcm(33));
        buffer.Read(output, 0, output.Length);
        Check(output.SequenceEqual(Pcm(11).Concat(Pcm(0)).Concat(Pcm(33))), "Conceal one loss without shifting later PCM");
        Check(buffer.Missing == 1, "Count one missing packet");
        for (uint i = 3; i < 100; i++) buffer.Offer(i, Pcm(44));
        Check(buffer.Queued <= 10 && buffer.Trimmed > 0, "Bound queue and trim stale audio");
        buffer.Offer(1000, Pcm(55)); buffer.Offer(1001, Pcm(66));
        output = new byte[1920]; buffer.Read(output, 0, output.Length);
        Check(output.SequenceEqual(Pcm(55).Concat(Pcm(66))), "Recover from a scheduler stall");
        buffer.Reset(); output = new byte[960]; buffer.Read(output, 0, output.Length);
        Check(output.All(x => x == 0), "Reset clears old audio");

        PhoneAudioSessionTests.Run();
        ClockAdjustedAudioTests.Run();
        Console.WriteLine("Phone audio: transport PCM, v2 compatibility, v3 freshness, clock drift, bounded latency and restart passed.");
    }
}
