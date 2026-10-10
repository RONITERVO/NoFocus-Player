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
                output.Init(Buffer);
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
        HashSet<ulong> retired = [];
        Queue<ulong> retiredOrder = [];
        long sessionBegan = 0;
        using AesGcm aes = new(key, 16);
        try
        {
            while (!token.IsCancellationRequested)
            {
                long last = Volatile.Read(ref lastAudio);
                if (active != null && Stopwatch.GetElapsedTime(last == 0 ? sessionBegan : last) > TimeSpan.FromSeconds(3))
                {
                    retired.Add(session); retiredOrder.Enqueue(session);
                    // Bound memory for long-running listeners; a fresh listener has a fresh local history.
                    if (retiredOrder.Count > 256) retired.Remove(retiredOrder.Dequeue());
                    active = null; Buffer!.Reset(); Volatile.Write(ref lastAudio, 0);
                }
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
                if (Protocol.TryHello(packet, key, out ulong proposed))
                {
                    if (retired.Contains(proposed) || (active != null && (!active.Equals(peer) || proposed != session))) continue;
                    if (active == null)
                    {
                        active = (IPEndPoint)peer; session = proposed; sessionBegan = Stopwatch.GetTimestamp();
                        Buffer!.Reset(); Volatile.Write(ref lastAudio, 0);
                    }
                    connection.SendTo(Protocol.CreateAcknowledgement(key, session), peer);
                }
                else if (active != null && active.Equals(peer) && Protocol.TryAudio(packet, aes, session, out uint sequence, out byte[] pcm)
                    && Buffer!.Offer(sequence, pcm))
                    Volatile.Write(ref lastAudio, Stopwatch.GetTimestamp());
            }
        }
        catch (Exception error) when (token.IsCancellationRequested && error is SocketException or ObjectDisposedException) { }
        catch (Exception error) { Failed?.Invoke(error); }
        finally { CryptographicOperations.ZeroMemory(key); }
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
