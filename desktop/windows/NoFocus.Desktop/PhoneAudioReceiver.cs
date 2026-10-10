using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace NoFocus.Desktop;

internal sealed class PhoneAudioReceiver : IDisposable
{
    private Socket? socket;
    private Thread? worker;
    private CancellationTokenSource? cancellation;
    private WasapiOut? output;
    private long lastAudio;
    private sealed record Pending(ulong Session, byte[] Nonce, long Created);
    internal PhoneAudioBuffer? Buffer { get; private set; }
    internal bool IsRunning => cancellation is { IsCancellationRequested: false };
    internal int LocalPort => ((IPEndPoint)socket!.LocalEndPoint!).Port;
    internal bool IsConnected => Volatile.Read(ref lastAudio) != 0 &&
        Stopwatch.GetElapsedTime(Volatile.Read(ref lastAudio)) < TimeSpan.FromSeconds(3);
    internal event Action<Exception>? Failed;

    internal void Start(string code, int targetPackets = 4, bool playAudio = true, int port = Protocol.ReceiverPort)
    {
        if (IsRunning) return;
        byte[] key = Protocol.PairingKey(code);
        try
        {
            Buffer = new PhoneAudioBuffer(targetPackets);
            socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp)
            {
                ExclusiveAddressUse = true, ReceiveBufferSize = 64 * 1024, ReceiveTimeout = 50
            };
            socket.Bind(new IPEndPoint(IPAddress.Any, port));
            if (playAudio)
            {
                output = new WasapiOut(AudioClientShareMode.Shared, true, 20);
                output.Init(new ClockAdjustedAudio(Buffer).ToWaveProvider());
                output.PlaybackStopped += OutputStopped;
                output.Play();
            }
            Volatile.Write(ref lastAudio, 0);
            cancellation = new CancellationTokenSource();
            Socket localSocket = socket;
            CancellationToken token = cancellation.Token;
            worker = new Thread(() => Receive(localSocket, key, token)) { IsBackground = true,
                Name = "NoFocus phone receiver", Priority = ThreadPriority.AboveNormal };
            worker.Start();
        }
        catch { Stop(); CryptographicOperations.ZeroMemory(key); throw; }
    }

    private void Receive(Socket connection, byte[] key, CancellationToken token)
    {
        byte[] bytes = new byte[4096];
        IPEndPoint? active = null;
        ulong session = 0;
        Dictionary<IPEndPoint, Pending> pending = [];
        byte[]? sessionKey = null;
        byte[]? activeNonce = null;
        AesGcm? aes = null;
        long sessionBegan = 0;
        try
        {
            while (!token.IsCancellationRequested)
            {
                long last = Volatile.Read(ref lastAudio);
                if (active != null && Stopwatch.GetElapsedTime(last == 0 ? sessionBegan : last) > TimeSpan.FromSeconds(3))
                {
                    aes?.Dispose(); aes = null;
                    if (sessionKey != null) CryptographicOperations.ZeroMemory(sessionKey);
                    sessionKey = null; activeNonce = null;
                    active = null; Buffer!.Reset(); Volatile.Write(ref lastAudio, 0);
                }
                foreach (var old in pending.Where(p => Stopwatch.GetElapsedTime(p.Value.Created) > TimeSpan.FromSeconds(5)).Select(p => p.Key).ToArray()) pending.Remove(old);
                EndPoint peer = new IPEndPoint(IPAddress.Any, 0);
                int count;
                try { count = connection.ReceiveFrom(bytes, ref peer); }
                catch (SocketException error) when (error.SocketErrorCode is SocketError.TimedOut or SocketError.WouldBlock or SocketError.ConnectionReset or SocketError.MessageSize) { continue; }
                ReadOnlySpan<byte> packet = bytes.AsSpan(0, count);
                if (packet.SequenceEqual(Protocol.DiscoveryRequest))
                {
                    byte[] response = [(byte)'N', (byte)'F', (byte)'P', (byte)'R', 2, 2,
                        (byte)(Protocol.ReceiverPort >> 8), (byte)(Protocol.ReceiverPort & 255)];
                    connection.SendTo(response, peer);
                    continue;
                }
                if (PhoneAudioProtocol.TryHello(packet, key, out ulong proposed))
                {
                    if (active != null)
                    {
                        if (active.Equals(peer) && proposed == session) connection.SendTo(PhoneAudioProtocol.Acknowledge(sessionKey!,session),peer);
                        continue;
                    }
                    var endpoint = (IPEndPoint)peer;
                    if (!pending.TryGetValue(endpoint, out Pending? challenge) || challenge.Session != proposed)
                    {
                        if (pending.Count >= 8) pending.Remove(pending.MinBy(p => p.Value.Created).Key);
                        challenge = new Pending(proposed,RandomNumberGenerator.GetBytes(16),Stopwatch.GetTimestamp());
                        pending[endpoint] = challenge;
                    }
                    connection.SendTo(PhoneAudioProtocol.Challenge(key,proposed,challenge.Nonce),peer);
                }
                else if (PhoneAudioProtocol.IsType(packet,4,48))
                {
                    if (active != null)
                    {
                        if (active.Equals(peer) && PhoneAudioProtocol.IsConfirmation(packet,sessionKey!,session,activeNonce!))
                            connection.SendTo(PhoneAudioProtocol.Acknowledge(sessionKey!,session),peer);
                        continue;
                    }
                    if (!pending.TryGetValue((IPEndPoint)peer,out Pending? challenge)) continue;
                    byte[] candidate = PhoneAudioProtocol.SessionKey(key,challenge.Session,challenge.Nonce);
                    if (!PhoneAudioProtocol.IsConfirmation(packet,candidate,challenge.Session,challenge.Nonce)) { CryptographicOperations.ZeroMemory(candidate); continue; }
                    active = (IPEndPoint)peer; session = challenge.Session; activeNonce = challenge.Nonce;
                    sessionKey = candidate; aes = new AesGcm(sessionKey,16); pending.Clear();
                    sessionBegan = Stopwatch.GetTimestamp(); Buffer!.Reset(); Volatile.Write(ref lastAudio,0);
                    connection.SendTo(PhoneAudioProtocol.Acknowledge(sessionKey,session),peer);
                }
                else if (active != null && active.Equals(peer) && PhoneAudioProtocol.TryAudio(packet, aes!, session, out uint sequence, out byte[] pcm)
                    && Buffer!.Offer(sequence, pcm))
                    Volatile.Write(ref lastAudio, Stopwatch.GetTimestamp());
            }
        }
        catch (Exception error) when (token.IsCancellationRequested && error is SocketException or ObjectDisposedException) { }
        catch (Exception error) { Failed?.Invoke(error); }
        finally { aes?.Dispose(); if (sessionKey != null) CryptographicOperations.ZeroMemory(sessionKey); CryptographicOperations.ZeroMemory(key); }
    }

    private void OutputStopped(object? sender, StoppedEventArgs args)
    {
        if (args.Exception != null && IsRunning) Failed?.Invoke(args.Exception);
    }

    internal void Stop()
    {
        cancellation?.Cancel();
        socket?.Dispose();
        if (worker != Thread.CurrentThread) worker?.Join(TimeSpan.FromSeconds(2));
        worker = null; socket = null;
        if (output != null) { output.PlaybackStopped -= OutputStopped; output.Stop(); output.Dispose(); output = null; }
        Buffer?.Reset();
        cancellation?.Dispose(); cancellation = null; Volatile.Write(ref lastAudio, 0);
    }
    public void Dispose() => Stop();
}
