using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace NoFocus.Desktop;

internal static class Protocol
{
    internal const int Port = 39821;
    internal const int SampleRate = 48_000;
    internal const int Channels = 2;
    internal const int FramesPerPacket = 240;
    internal const int PcmBytesPerPacket = FramesPerPacket * Channels * 2;
    internal static readonly byte[] DiscoveryRequest = "NFPD\x02\x01\0\x08"u8.ToArray();

    internal static byte[] PairingKey(string pairingCode)
    {
        string normalized = NormalizeCode(pairingCode);
        if (normalized.Length != 16 || normalized.Any(character => !char.IsAsciiLetterOrDigit(character)))
            throw new ArgumentException("Enter the 16-character pairing code shown on the phone.");
        return SHA256.HashData(Encoding.ASCII.GetBytes(normalized));
    }

    internal static string NormalizeCode(string value) =>
        new(value.Where(char.IsAsciiLetterOrDigit).Select(char.ToUpperInvariant).ToArray());

    internal static byte[] CreateHello(byte[] key, ulong sessionId, string computerName)
    {
        byte[] packet = new byte[64];
        "NFP2"u8.CopyTo(packet);
        packet[4] = 2;
        packet[5] = 1;
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(6), 64);
        BinaryPrimitives.WriteUInt64BigEndian(packet.AsSpan(8), sessionId);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(16), SampleRate);
        packet[20] = Channels;
        packet[21] = 2;
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(22), FramesPerPacket);
        byte[] encodedName = Encoding.UTF8.GetBytes(computerName);
        int nameLength = Math.Min(encodedName.Length, 24);
        encodedName.AsSpan(0, nameLength).CopyTo(packet.AsSpan(24, 24));
        if (nameLength < 24)
            packet.AsSpan(24 + nameLength, 24 - nameLength).Clear();
        byte[] authentication = HMACSHA256.HashData(key, packet.AsSpan(0, 48));
        authentication.AsSpan(0, 16).CopyTo(packet.AsSpan(48, 16));
        return packet;
    }

    internal static byte[] CreateAudio(AesGcm aes, ulong sessionId, uint sequence, ulong timestampFrames,
        ReadOnlySpan<byte> pcm)
    {
        if (pcm.Length != PcmBytesPerPacket)
            throw new ArgumentException("PCM frame has the wrong size.");
        byte[] packet = new byte[32 + pcm.Length + 16];
        "NFP2"u8.CopyTo(packet);
        packet[4] = 2;
        packet[5] = 2;
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(6), 32);
        BinaryPrimitives.WriteUInt64BigEndian(packet.AsSpan(8), sessionId);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(16), sequence);
        BinaryPrimitives.WriteUInt64BigEndian(packet.AsSpan(20), timestampFrames);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(28), FramesPerPacket);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(30), (ushort)(pcm.Length + 16));
        Span<byte> nonce = stackalloc byte[12];
        BinaryPrimitives.WriteUInt64BigEndian(nonce, sessionId);
        BinaryPrimitives.WriteUInt32BigEndian(nonce[8..], sequence);
        aes.Encrypt(nonce, pcm, packet.AsSpan(32, pcm.Length), packet.AsSpan(32 + pcm.Length, 16),
            packet.AsSpan(0, 32));
        return packet;
    }

    internal static bool IsDiscoveryResponse(ReadOnlySpan<byte> packet) =>
        packet.Length == 8 && packet[..6].SequenceEqual("NFPR\x02\x02"u8)
        && BinaryPrimitives.ReadUInt16BigEndian(packet[6..]) == Port;

    internal static bool IsHelloAcknowledgement(ReadOnlySpan<byte> packet, byte[] key, ulong sessionId)
    {
        if (packet.Length != 32 || !packet[..8].SequenceEqual("NFP2\x02\x03\0\x20"u8)
            || BinaryPrimitives.ReadUInt64BigEndian(packet[8..16]) != sessionId)
            return false;
        byte[] expected = HMACSHA256.HashData(key, packet[..16]);
        return CryptographicOperations.FixedTimeEquals(expected.AsSpan(0, 16), packet[16..32]);
    }

    internal static void SelfTest()
    {
        byte[] key = PairingKey("ABCD-EFGH-JKLM-NPQR");
        byte[] hello = CreateHello(key, 7, "Desktop");
        if (hello.Length != 64)
            throw new InvalidOperationException("Hello size mismatch.");
        using AesGcm aes = new(key, 16);
        byte[] pcm = new byte[PcmBytesPerPacket];
        byte[] packet = CreateAudio(aes, 7, 1, 240, pcm);
        if (packet.Length != 1008)
            throw new InvalidOperationException("Audio size mismatch.");
        if (!IsDiscoveryResponse(new byte[] { (byte)'N', (byte)'F', (byte)'P', (byte)'R', 2, 2, 0x9b, 0x8d }))
            throw new InvalidOperationException("Discovery response mismatch.");
        byte[] acknowledgement = new byte[32];
        "NFP2\x02\x03\0\x20"u8.CopyTo(acknowledgement);
        BinaryPrimitives.WriteUInt64BigEndian(acknowledgement.AsSpan(8), 7);
        HMACSHA256.HashData(key, acknowledgement.AsSpan(0, 16)).AsSpan(0, 16)
            .CopyTo(acknowledgement.AsSpan(16));
        if (!IsHelloAcknowledgement(acknowledgement, key, 7))
            throw new InvalidOperationException("Hello acknowledgement mismatch.");
    }
}
