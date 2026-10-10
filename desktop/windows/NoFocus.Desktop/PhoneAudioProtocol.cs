using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace NoFocus.Desktop;

// Reverse audio v3. A fresh receiver nonce and key confirmation precede any audio.
// The original PC -> phone v2 protocol is independent and remains unchanged.
internal static class PhoneAudioProtocol
{
    private static void Header(Span<byte> packet, byte type, int size, ulong session)
    {
        "NFP3"u8.CopyTo(packet); packet[4] = 3; packet[5] = type;
        BinaryPrimitives.WriteUInt16BigEndian(packet[6..], (ushort)size);
        BinaryPrimitives.WriteUInt64BigEndian(packet[8..], session);
    }
    internal static bool IsType(ReadOnlySpan<byte> packet, byte type, int size) => packet.Length >= size
        && packet[..4].SequenceEqual("NFP3"u8) && packet[4] == 3 && packet[5] == type
        && BinaryPrimitives.ReadUInt16BigEndian(packet[6..]) == size;
    private static void Sign(byte[] packet, byte[] key) => HMACSHA256.HashData(key, packet.AsSpan(0, packet.Length - 16)).AsSpan(0,16).CopyTo(packet.AsSpan(packet.Length - 16));
    private static bool Signed(ReadOnlySpan<byte> packet, byte[] key) => CryptographicOperations.FixedTimeEquals(
        HMACSHA256.HashData(key, packet[..^16]).AsSpan(0,16), packet[^16..]);

    internal static byte[] Hello(byte[] key, ulong session, string name)
    {
        byte[] packet = Protocol.CreateHello(key, session, name);
        Header(packet,1,64,session); Sign(packet,key); return packet;
    }
    internal static bool TryHello(ReadOnlySpan<byte> packet, byte[] key, out ulong session)
    {
        session = 0;
        if (packet.Length != 64 || !IsType(packet,1,64) || !Signed(packet,key)
            || BinaryPrimitives.ReadUInt32BigEndian(packet[16..]) != Protocol.SampleRate || packet[20] != 2 || packet[21] != 2
            || BinaryPrimitives.ReadUInt16BigEndian(packet[22..]) != Protocol.FramesPerPacket) return false;
        session = BinaryPrimitives.ReadUInt64BigEndian(packet[8..]); return true;
    }
    internal static byte[] SessionKey(byte[] root, ulong session, byte[] nonce)
    {
        if (nonce.Length != 16) throw new ArgumentException("Receiver nonce must contain 16 bytes.");
        byte[] label = Encoding.ASCII.GetBytes("NoFocus phone-to-PC v3 session");
        byte[] input = new byte[label.Length + 24]; label.CopyTo(input,0);
        BinaryPrimitives.WriteUInt64BigEndian(input.AsSpan(label.Length),session); nonce.CopyTo(input,label.Length + 8);
        return HMACSHA256.HashData(root,input);
    }
    private static byte[] Proof(byte[] key, ulong session, byte[] nonce, byte type)
    {
        if (nonce.Length != 16) throw new ArgumentException("Receiver nonce must contain 16 bytes.");
        byte[] packet = new byte[48]; Header(packet,type,48,session); nonce.CopyTo(packet,16); Sign(packet,key); return packet;
    }
    internal static byte[] Challenge(byte[] root, ulong session, byte[] nonce) => Proof(root,session,nonce,3);
    internal static byte[] Confirm(byte[] key, ulong session, byte[] nonce) => Proof(key,session,nonce,4);
    internal static bool TryChallenge(ReadOnlySpan<byte> packet, byte[] root, ulong session, out byte[] nonce)
    {
        nonce = [];
        if (packet.Length != 48 || !IsType(packet,3,48) || BinaryPrimitives.ReadUInt64BigEndian(packet[8..]) != session || !Signed(packet,root)) return false;
        nonce = packet.Slice(16,16).ToArray(); return true;
    }
    internal static bool IsConfirmation(ReadOnlySpan<byte> packet, byte[] key, ulong session, byte[] nonce) => packet.Length == 48
        && IsType(packet,4,48) && BinaryPrimitives.ReadUInt64BigEndian(packet[8..]) == session
        && CryptographicOperations.FixedTimeEquals(nonce,packet.Slice(16,16)) && Signed(packet,key);
    internal static byte[] Acknowledge(byte[] key, ulong session)
    {
        byte[] packet = new byte[32]; Header(packet,5,32,session); Sign(packet,key); return packet;
    }
    internal static bool IsAcknowledgement(ReadOnlySpan<byte> packet, byte[] key, ulong session) => packet.Length == 32
        && IsType(packet,5,32) && BinaryPrimitives.ReadUInt64BigEndian(packet[8..]) == session && Signed(packet,key);
    internal static byte[] Audio(AesGcm aes, ulong session, uint sequence, ReadOnlySpan<byte> pcm)
    {
        if (pcm.Length != Protocol.PcmBytesPerPacket) throw new ArgumentException("Invalid PCM packet.");
        byte[] packet = new byte[32 + pcm.Length + 16]; Header(packet,2,32,session);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(16),sequence);
        BinaryPrimitives.WriteUInt64BigEndian(packet.AsSpan(20),(ulong)sequence * Protocol.FramesPerPacket);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(28),Protocol.FramesPerPacket);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(30),(ushort)(pcm.Length + 16));
        aes.Encrypt(packet.AsSpan(8,12),pcm,packet.AsSpan(32,pcm.Length),packet.AsSpan(32 + pcm.Length,16),packet.AsSpan(0,32)); return packet;
    }
    internal static bool TryAudio(ReadOnlySpan<byte> packet, AesGcm aes, ulong session, out uint sequence, out byte[] pcm)
    {
        sequence = 0; pcm = [];
        if (packet.Length != 32 + Protocol.PcmBytesPerPacket + 16 || !IsType(packet,2,32)
            || BinaryPrimitives.ReadUInt64BigEndian(packet[8..]) != session
            || BinaryPrimitives.ReadUInt16BigEndian(packet[28..]) != Protocol.FramesPerPacket
            || BinaryPrimitives.ReadUInt16BigEndian(packet[30..]) != Protocol.PcmBytesPerPacket + 16) return false;
        sequence = BinaryPrimitives.ReadUInt32BigEndian(packet[16..]);
        if (BinaryPrimitives.ReadUInt64BigEndian(packet[20..]) != (ulong)sequence * Protocol.FramesPerPacket) return false;
        byte[] decoded = new byte[Protocol.PcmBytesPerPacket];
        try { aes.Decrypt(packet.Slice(8,12),packet.Slice(32,decoded.Length),packet[^16..],decoded,packet[..32]); }
        catch (CryptographicException) { return false; }
        pcm = decoded; return true;
    }
}
