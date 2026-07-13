using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace NoFocus.Desktop;

internal sealed class AudioEngine : IDisposable
{
    private WasapiLoopbackCapture? capture;
    private BufferedWaveProvider? captureBuffer;
    private ISampleProvider? samples;
    private Socket? socket;
    private CancellationTokenSource? cancellation;
    private Thread? senderThread;
    private Exception? captureError;
    private long packetsSent;
    private long lastAcknowledgementTimestamp;

    internal bool IsRunning => cancellation is { IsCancellationRequested: false };
    internal long PacketsSent => Interlocked.Read(ref packetsSent);
    internal bool IsConfirmed
    {
        get
        {
            long timestamp = Volatile.Read(ref lastAcknowledgementTimestamp);
            return timestamp != 0 &&
                   Stopwatch.GetElapsedTime(timestamp) < TimeSpan.FromSeconds(5);
        }
    }
    internal string DeviceName { get; private set; } = "Default PC output";
    internal event Action<Exception>? Failed;

    internal void Start(string phoneAddress, string pairingCode)
    {
        if (IsRunning) return;
        if (!IPAddress.TryParse(phoneAddress, out IPAddress? address) || address.AddressFamily != AddressFamily.InterNetwork)
            throw new ArgumentException("Choose the discovered phone or enter a valid phone address.");

        byte[] key = Protocol.PairingKey(pairingCode);
        capture = new WasapiLoopbackCapture();
        captureBuffer = new BufferedWaveProvider(capture.WaveFormat)
        {
            BufferDuration = TimeSpan.FromMilliseconds(80),
            DiscardOnBufferOverflow = true,
            ReadFully = true
        };
        ISampleProvider provider = captureBuffer.ToSampleProvider();
        provider = provider.WaveFormat.Channels switch
        {
            1 => new MonoToStereoSampleProvider(provider),
            2 => provider,
            _ => new FirstTwoChannelsProvider(provider)
        };
        if (provider.WaveFormat.SampleRate != Protocol.SampleRate)
            provider = new WdlResamplingSampleProvider(provider, Protocol.SampleRate);
        samples = provider;

        capture.DataAvailable += OnDataAvailable;
        capture.RecordingStopped += OnRecordingStopped;
        socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        socket.SendBufferSize = 256 * 1024;
        socket.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.TypeOfService, 0xB8);
        socket.Connect(new IPEndPoint(address, Protocol.Port));
        cancellation = new CancellationTokenSource();
        captureError = null;
        Interlocked.Exchange(ref packetsSent, 0);
        Volatile.Write(ref lastAcknowledgementTimestamp, 0);
        capture.StartRecording();
        senderThread = new Thread(() => SendLoop(key, cancellation.Token))
        {
            IsBackground = true,
            Name = "NoFocus real-time sender",
            Priority = ThreadPriority.Highest
        };
        senderThread.Start();
    }

    internal void Stop()
    {
        CancellationTokenSource? oldCancellation = cancellation;
        cancellation = null;
        oldCancellation?.Cancel();
        senderThread?.Join(TimeSpan.FromSeconds(2));
        senderThread = null;
        if (capture != null)
        {
            capture.DataAvailable -= OnDataAvailable;
            capture.RecordingStopped -= OnRecordingStopped;
            try { capture.StopRecording(); } catch { }
            capture.Dispose();
            capture = null;
        }
        socket?.Dispose();
        socket = null;
        captureBuffer = null;
        samples = null;
        oldCancellation?.Dispose();
    }

    private void OnDataAvailable(object? sender, WaveInEventArgs eventArgs)
    {
        captureBuffer?.AddSamples(eventArgs.Buffer, 0, eventArgs.BytesRecorded);
    }

    private void OnRecordingStopped(object? sender, StoppedEventArgs eventArgs)
    {
        if (eventArgs.Exception != null)
            captureError = eventArgs.Exception;
    }

    private void SendLoop(byte[] key, CancellationToken token)
    {
        IntPtr mmcssHandle = IntPtr.Zero;
        IntPtr timerHandle = IntPtr.Zero;
        try
        {
            uint taskIndex = 0;
            mmcssHandle = Native.AvSetMmThreadCharacteristics("Pro Audio", ref taskIndex);
            if (mmcssHandle != IntPtr.Zero)
                Native.AvSetMmThreadPriority(mmcssHandle, Native.AvrtPriority.High);

            timerHandle = Native.CreateWaitableTimerEx(IntPtr.Zero, null,
                Native.CreateWaitableTimerHighResolution, Native.TimerAllAccess);
            if (timerHandle == IntPtr.Zero)
                throw new InvalidOperationException("Windows could not create the high-resolution audio timer.");
            ulong sessionId = BitConverter.ToUInt64(RandomNumberGenerator.GetBytes(8));
            byte[] hello = Protocol.CreateHello(key, sessionId, Environment.MachineName);
            using AesGcm aes = new(key, 16);
            float[] floatSamples = new float[Protocol.FramesPerPacket * Protocol.Channels];
            byte[] pcm = new byte[Protocol.PcmBytesPerPacket];
            byte[] acknowledgementBuffer = new byte[64];
            byte[]? previousDatagram = null;
            uint sequence = 0;
            ulong timestampFrames = 0;
            Stopwatch helloClock = Stopwatch.StartNew();
            helloClock.Restart();
            socket!.Send(hello);
            long timerFrequency = Stopwatch.Frequency;
            long packetPeriodTicks = timerFrequency * Protocol.FramesPerPacket / Protocol.SampleRate;
            long nextPacketTick = Stopwatch.GetTimestamp();

            while (!token.IsCancellationRequested)
            {
                nextPacketTick += packetPeriodTicks;
                long nowTicks = Stopwatch.GetTimestamp();
                if (nowTicks - nextPacketTick > packetPeriodTicks * 2)
                    nextPacketTick = nowTicks;
                long remainingTicks = nextPacketTick - nowTicks;
                long dueTime = -Math.Max(1, remainingTicks * 10_000_000L / timerFrequency);
                if (!Native.SetWaitableTimer(timerHandle, ref dueTime, 0, IntPtr.Zero, IntPtr.Zero, false))
                    throw new InvalidOperationException("Windows could not schedule the audio timer.");
                if (Native.WaitForSingleObject(timerHandle, 1000) != Native.WaitObject0)
                    continue;
                if (captureError != null)
                    throw new InvalidOperationException("The PC audio device changed or stopped.", captureError);
                if (helloClock.Elapsed >= TimeSpan.FromSeconds(2))
                {
                    socket.Send(hello);
                    helloClock.Restart();
                }

                int read = samples!.Read(floatSamples, 0, floatSamples.Length);
                if (read < floatSamples.Length)
                    Array.Clear(floatSamples, read, floatSamples.Length - read);
                ConvertToPcm16(floatSamples, pcm);
                byte[] datagram = Protocol.CreateAudio(aes, sessionId, sequence, timestampFrames, pcm);
                socket.Send(datagram);
                if (previousDatagram != null)
                    socket.Send(previousDatagram);
                previousDatagram = datagram;
                sequence++;
                timestampFrames += Protocol.FramesPerPacket;
                Interlocked.Increment(ref packetsSent);
                while (socket.Poll(0, SelectMode.SelectRead))
                {
                    int received = socket.Receive(acknowledgementBuffer);
                    if (Protocol.IsHelloAcknowledgement(acknowledgementBuffer.AsSpan(0, received), key, sessionId))
                        Volatile.Write(ref lastAcknowledgementTimestamp, Stopwatch.GetTimestamp());
                }
            }
        }
        catch (Exception error) when (!token.IsCancellationRequested)
        {
            Failed?.Invoke(error);
        }
        finally
        {
            if (timerHandle != IntPtr.Zero)
            {
                Native.CancelWaitableTimer(timerHandle);
                Native.CloseHandle(timerHandle);
            }
            if (mmcssHandle != IntPtr.Zero)
                Native.AvRevertMmThreadCharacteristics(mmcssHandle);
            CryptographicOperations.ZeroMemory(key);
        }
    }

    private static void ConvertToPcm16(float[] input, byte[] output)
    {
        Span<short> destination = MemoryMarshal.Cast<byte, short>(output);
        for (int i = 0; i < destination.Length; i++)
            destination[i] = (short)(Math.Clamp(input[i], -1f, 1f) * short.MaxValue);
    }

    public void Dispose() => Stop();

    private sealed class FirstTwoChannelsProvider : ISampleProvider
    {
        private readonly ISampleProvider source;
        private float[] sourceBuffer = [];
        internal FirstTwoChannelsProvider(ISampleProvider source)
        {
            this.source = source;
            WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(source.WaveFormat.SampleRate, 2);
        }
        public WaveFormat WaveFormat { get; }
        public int Read(float[] buffer, int offset, int count)
        {
            int outputFrames = count / 2;
            int sourceSamples = outputFrames * source.WaveFormat.Channels;
            if (sourceBuffer.Length < sourceSamples) sourceBuffer = new float[sourceSamples];
            int read = source.Read(sourceBuffer, 0, sourceSamples);
            int framesRead = read / source.WaveFormat.Channels;
            for (int frame = 0; frame < framesRead; frame++)
            {
                buffer[offset + frame * 2] = sourceBuffer[frame * source.WaveFormat.Channels];
                buffer[offset + frame * 2 + 1] = sourceBuffer[frame * source.WaveFormat.Channels + 1];
            }
            return framesRead * 2;
        }
    }

    private static class Native
    {
        internal const uint CreateWaitableTimerHighResolution = 0x2;
        internal const uint TimerAllAccess = 0x1F0003;
        internal const uint WaitObject0 = 0;
        internal enum AvrtPriority { Low = -1, Normal = 0, High = 1, Critical = 2 }

        [DllImport("avrt.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        internal static extern IntPtr AvSetMmThreadCharacteristics(string taskName, ref uint taskIndex);
        [DllImport("avrt.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool AvSetMmThreadPriority(IntPtr handle, AvrtPriority priority);
        [DllImport("avrt.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool AvRevertMmThreadCharacteristics(IntPtr handle);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        internal static extern IntPtr CreateWaitableTimerEx(IntPtr attributes, string? name, uint flags, uint access);
        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool SetWaitableTimer(IntPtr timer, ref long dueTime, int period,
            IntPtr completionRoutine, IntPtr argument, bool resume);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool CancelWaitableTimer(IntPtr timer);
        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool CloseHandle(IntPtr handle);
    }
}
