using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace NoFocus.Desktop;

internal static class PhoneAudioSessionTests
{
    private const string Code = "ABCDEFGHJKLMNPQR";
    private static void Check(bool value, string reason) { if (!value) throw new Exception(reason); }
    private static byte[] Receive(Socket socket) { byte[] bytes = new byte[4096]; return bytes[..socket.Receive(bytes)]; }
    private static Socket Peer(int port) { Socket s = new(AddressFamily.InterNetwork,SocketType.Dgram,ProtocolType.Udp); s.Connect(IPAddress.Loopback,port);s.ReceiveTimeout = 1500;return s; }
    private static void WaitFor(Func<bool> condition)
    {
        Stopwatch until = Stopwatch.StartNew();
        while (!condition()) { if (until.ElapsedMilliseconds > 2000) throw new TimeoutException("Audio receiver did not advance."); Thread.Sleep(5); }
    }
    private static (byte[] key,byte[] nonce,byte[] confirm) Pair(Socket peer,byte[] root,ulong session)
    {
        peer.Send(PhoneAudioProtocol.Hello(root,session,"Test phone"));
        Check(PhoneAudioProtocol.TryChallenge(Receive(peer),root,session,out byte[] nonce),"Authenticated fresh challenge");
        byte[] key = PhoneAudioProtocol.SessionKey(root,session,nonce), confirm = PhoneAudioProtocol.Confirm(key,session,nonce);
        peer.Send(confirm);Check(PhoneAudioProtocol.IsAcknowledgement(Receive(peer),key,session),"Confirm derived session key");
        return (key,nonce,confirm);
    }
    internal static void Run()
    {
        byte[] root = Protocol.PairingKey(Code);
        byte[] nonce = Enumerable.Range(0,16).Select(i => (byte)i).ToArray();
        byte[] key = PhoneAudioProtocol.SessionKey(root,7,nonce), hello = PhoneAudioProtocol.Hello(root,7,"Desktop");
        Check(PhoneAudioProtocol.TryHello(hello,root,out ulong id) && id == 7,"v3 hello");
        Check(!PhoneAudioProtocol.TryHello(Protocol.CreateHello(root,7,"Old sender"),root,out _),"Reject v2 downgrade");
        byte[] challenge = PhoneAudioProtocol.Challenge(root,7,nonce);
        Check(PhoneAudioProtocol.TryChallenge(challenge,root,7,out var parsed) && parsed.SequenceEqual(nonce),"Challenge nonce");
        challenge[^1] ^= 1;Check(!PhoneAudioProtocol.TryChallenge(challenge,root,7,out _),"Reject tampered challenge");
        byte[] confirm = PhoneAudioProtocol.Confirm(key,7,nonce);
        Check(!PhoneAudioProtocol.IsConfirmation(confirm,key,8,nonce),"Confirmation binds session");
        byte[] otherNonce = nonce.ToArray();otherNonce[0] ^= 1;
        Check(!PhoneAudioProtocol.IsConfirmation(confirm,key,7,otherNonce),"Confirmation binds receiver nonce");
        Check(!key.SequenceEqual(PhoneAudioProtocol.SessionKey(root,7,otherNonce)),"Fresh nonce changes audio key");
        using AesGcm fixtureAes = new(key,16);
        byte[] pcm = Enumerable.Range(0,960).Select(i => (byte)(i * 31)).ToArray();
        byte[] fixture = PhoneAudioProtocol.Audio(fixtureAes,7,9,pcm);
        Check(PhoneAudioProtocol.TryAudio(fixture,fixtureAes,7,out uint sequence,out byte[] decoded) && sequence == 9 && pcm.SequenceEqual(decoded),"Exact v3 PCM");
        // These deterministic hashes are also asserted by the Java sender's unit tests.
        string[] values = [Convert.ToHexString(SHA256.HashData(hello)),Convert.ToHexString(key),Convert.ToHexString(SHA256.HashData(confirm)),Convert.ToHexString(SHA256.HashData(fixture))];
        Check(values.SequenceEqual(new[] {
            "557BF06294946167B8804396C9F55C84F67A7E10034F5BB57897EC98E10C583A",
            "BBE8CE2E06E5A4487DA613B3FC988F93B9132D0C1AB043806667221DBA014CD0",
            "661D8DCDDC6255029CFFD1E95DF2D5804D1AC34630C63CFB6B79CCE47CF2EC30",
            "6B52630A57422BD3F55EACB482E07D77C49935BBE2CAAEE7B73EC8A837E40CC0" }),"Java/Windows v3 golden fixtures");
        fixture[^1] ^= 1;Check(!PhoneAudioProtocol.TryAudio(fixture,fixtureAes,7,out _,out _),"Reject tampered v3 audio");

        using PhoneAudioReceiver receiver = new();
        Exception? failure = null;receiver.Failed += error => failure = error;
        receiver.Start(Code,2,playAudio:false,port:0);int port = receiver.LocalPort;
        using Socket phone = Peer(port), attacker = Peer(port);
        phone.Send(Protocol.DiscoveryRequest);
        Check(Receive(phone).AsSpan().SequenceEqual(new byte[]{(byte)'N',(byte)'F',(byte)'P',(byte)'R',2,2,0x9b,0x8e}),"Reverse discovery");
        phone.Send(PhoneAudioProtocol.Hello(Protocol.PairingKey("ZZZZZZZZZZZZZZZZ"),7,"Wrong"));
        Check(!phone.Poll(100000,SelectMode.SelectRead),"Wrong pairing code rejected");
        byte[] savedHello = PhoneAudioProtocol.Hello(root,7,"Desktop");
        attacker.Send(savedHello);Receive(attacker); // A replayed hello alone must not reserve the listener.
        var paired = Pair(phone,root,7);
        using AesGcm aes = new(paired.key,16);
        byte[][] savedAudio = Enumerable.Range(0,3).Select(n => PhoneAudioProtocol.Audio(aes,7,(uint)n,Enumerable.Repeat((byte)(n + 1),960).ToArray())).ToArray();
        foreach (int n in new[]{0,2,1,2}) phone.Send(savedAudio[n]);
        WaitFor(() => receiver.Buffer!.Received == 3);
        byte[] output = new byte[2880];receiver.Buffer!.Read(output,0,output.Length);
        Check(output.SequenceEqual(Enumerable.Range(1,3).SelectMany(n => Enumerable.Repeat((byte)n,960))),"Reorder and exact transport PCM");
        phone.Send(paired.confirm);Check(PhoneAudioProtocol.IsAcknowledgement(Receive(phone),paired.key,7),"Lost confirmation ACK can retry without resetting audio");
        Check(receiver.Buffer.Received == 3,"Confirmation retry retains stream");
        attacker.Send(savedHello);Check(!attacker.Poll(100000,SelectMode.SelectRead),"Active endpoint isolated");
        phone.Send(new byte[5000]);phone.Send(savedHello);
        Check(PhoneAudioProtocol.IsAcknowledgement(Receive(phone),paired.key,7),"Malformed UDP cannot kill listener");
        Thread.Sleep(3200);Check(!receiver.IsConnected,"Idle timeout");
        attacker.Send(paired.confirm);Check(!attacker.Poll(100000,SelectMode.SelectRead),"Old confirmation cannot reactivate expired session");
        attacker.Send(savedHello);
        Check(PhoneAudioProtocol.TryChallenge(Receive(attacker),root,7,out byte[] replacement) && !replacement.SequenceEqual(paired.nonce),"Timeout requires a new receiver challenge");
        attacker.Send(paired.confirm);foreach(var bytes in savedAudio) attacker.Send(bytes);
        Check(!attacker.Poll(100000,SelectMode.SelectRead) && !receiver.IsConnected,"Expired capture cannot be replayed");
        receiver.Stop();receiver.Start(Code,2,playAudio:false,port:port);
        attacker.Send(savedHello);
        Check(PhoneAudioProtocol.TryChallenge(Receive(attacker),root,7,out replacement) && !replacement.SequenceEqual(paired.nonce),"Restart requires a new receiver challenge");
        attacker.Send(paired.confirm);foreach(var bytes in savedAudio) attacker.Send(bytes);
        Check(!attacker.Poll(100000,SelectMode.SelectRead) && receiver.Buffer!.Received == 0,"Captured session rejected across restart");
        var fresh = Pair(phone,root,8);using AesGcm freshAes = new(fresh.key,16);
        phone.Send(PhoneAudioProtocol.Audio(freshAes,8,0,pcm));WaitFor(() => receiver.Buffer!.Received == 1);
        receiver.Stop();Check(failure == null && !receiver.IsRunning,"Fresh phone connects despite replay attempts; clean stop");
        Console.WriteLine("v3 challenge freshness, endpoint isolation, exact PCM, timeout and cross-restart replay rejection passed.");
    }
}
