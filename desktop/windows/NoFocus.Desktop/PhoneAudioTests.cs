using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace NoFocus.Desktop;

internal static class PhoneAudioTests
{
    private const string Code = "ABCDEFGHJKLMNPQR";
    private static void Check(bool value, string reason) { if (!value) throw new InvalidOperationException(reason); }
    private static byte[] Pcm(int value) => Enumerable.Repeat((byte)value, Protocol.PcmBytesPerPacket).ToArray();
    private static void WaitFor(Func<bool> predicate)
    {
        Stopwatch deadline = Stopwatch.StartNew();
        while (!predicate())
        {
            if (deadline.ElapsedMilliseconds > 2000) throw new TimeoutException("Receiver condition timed out");
            Thread.Sleep(5);
        }
    }

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

        using PhoneAudioReceiver receiver = new();
        Exception? failure = null;
        receiver.Failed += error => failure = error;
        receiver.Start(Code, 2, playAudio: false, port: 0);
        int port = receiver.LocalPort;
        using Socket sender = new(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        sender.Connect(IPAddress.Loopback, port); sender.ReceiveTimeout = 1500;
        byte[] reply = new byte[64];
        sender.Send(Protocol.DiscoveryRequest);
        int discoveryLength = sender.Receive(reply);
        Check(discoveryLength == 8 && reply.AsSpan(0, 8).SequenceEqual(
            new byte[] { (byte)'N', (byte)'F', (byte)'P', (byte)'R', 2, 2, 0x9b, 0x8e }), "Advertise only the reverse receiver port");
        sender.Send(Protocol.CreateHello(Protocol.PairingKey("ZZZZZZZZZZZZZZZZ"), 7, "Wrong"));
        Check(!sender.Poll(100_000, SelectMode.SelectRead), "Wrong code must not be acknowledged");
        sender.Send(Protocol.CreateAudio(aes, 7, 0, 0, Pcm(10)));
        sender.Send(Protocol.CreateHello(key, 7, "Phone"));
        int length = sender.Receive(reply);
        Check(Protocol.IsHelloAcknowledgement(reply.AsSpan(0, length), key, 7), "Authenticated UDP handshake");
        Check(receiver.Buffer!.Received == 0, "Ignore audio before pairing");
        foreach (uint sequence in new uint[] { 0, 2, 1, 2 })
            sender.Send(Protocol.CreateAudio(aes, 7, sequence, sequence * 240UL, Pcm((int)sequence + 1)));
        WaitFor(() => receiver.Buffer.Received == 3);
        output = new byte[2880]; receiver.Buffer.Read(output, 0, output.Length);
        Check(output.SequenceEqual(Pcm(1).Concat(Pcm(2)).Concat(Pcm(3))), "UDP transport preserves PCM and recovers reorder");
        using Socket stranger = new(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        stranger.Connect(IPAddress.Loopback, port);
        stranger.Send(Protocol.CreateHello(key, 8, "Other"));
        Check(!stranger.Poll(100_000, SelectMode.SelectRead), "Lock an active session to its endpoint");
        sender.Send(new byte[5000]); // Oversized LAN junk must not terminate the listener.
        sender.Send(Protocol.CreateHello(key, 7, "Phone"));
        length = sender.Receive(reply);
        Check(Protocol.IsHelloAcknowledgement(reply.AsSpan(0, length), key, 7), "Survive malformed UDP");
        Thread.Sleep(3200);
        Check(!receiver.IsConnected, "Dead session expires");
        sender.Send(Protocol.CreateHello(key, 7, "Replay"));
        Check(!sender.Poll(100_000, SelectMode.SelectRead), "Reject retired session replay");
        sender.Send(Protocol.CreateHello(key, 8, "Reconnect"));
        length = sender.Receive(reply);
        Check(Protocol.IsHelloAcknowledgement(reply.AsSpan(0, length), key, 8), "Fresh session can reconnect");
        receiver.Stop();
        Check(!receiver.IsRunning && failure == null, "Stop releases listener without failure");
        receiver.Start(Code, playAudio: false, port: port);
        receiver.Stop();
        Console.WriteLine("Phone audio: exact PCM, v2 compatibility, tampering, reorder, loss, bounded latency, UDP pairing, timeout and restart passed.");
    }
}
