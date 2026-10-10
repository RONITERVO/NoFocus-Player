using System.Buffers.Binary;
using NAudio.Dsp;
using NAudio.Wave;

namespace NoFocus.Desktop;

// Transport PCM stays unchanged. Only live playout follows the independent output clock.
// WDL's windowed-sinc interpolation avoids periodic packet drops/repeats for ordinary drift.
internal sealed class ClockAdjustedAudio(PhoneAudioBuffer source) : ISampleProvider
{
    private readonly WdlResampler resampler = CreateResampler();
    private byte[] input = [];
    private bool started;
    private long generation = -1;
    private double filteredError, integral, correction;
    internal long Rebuffers { get; private set; }
    internal double CorrectionPpm => correction * 1e6;
    public WaveFormat WaveFormat { get; } = WaveFormat.CreateIeeeFloatWaveFormat(Protocol.SampleRate,Protocol.Channels);
    private static WdlResampler CreateResampler()
    {
        WdlResampler value = new(); value.SetMode(false,0,true,64,32); value.SetFeedMode(false);
        value.SetRates(Protocol.SampleRate,Protocol.SampleRate); return value;
    }
    public int Read(float[] buffer, int offset, int count)
    {
        if (count % 2 != 0) throw new ArgumentException("Stereo reads must contain complete frames.");
        // Bound callback size independently of the Windows endpoint's buffer size.
        for (int copied = 0; copied < count; copied += 1920) ReadBlock(buffer,offset + copied,Math.Min(1920,count - copied));
        return count;
    }
    private void ReadBlock(float[] buffer, int offset, int count)
    {
        lock (source.SyncRoot)
        {
            int frames = count / 2;
            // Retain the requested cushion AFTER supplying one output callback and filter lookahead.
            double target = source.TargetFrames + frames + 64;
            if (generation != source.Generation)
            {
                generation = source.Generation; started = false; resampler.Reset();
                filteredError = integral = correction = 0;
            }
            if (!started)
            {
                if (source.BufferedFrames < target) { Array.Clear(buffer,offset,count); return; }
                started = true;
            }
            double dt = (double)frames / Protocol.SampleRate;
            double depth = source.BufferedFrames + resampler.GetCurrentLatency() * Protocol.SampleRate;
            // Slow PI control rejects packet-scale jitter. Limit and slew-limit correction to 0.15%.
            filteredError += (depth - target - filteredError) * (1 - Math.Exp(-dt / 2));
            integral = Math.Clamp(integral + filteredError * dt * 0.2e-6,-0.0015,0.0015);
            double desired = Math.Clamp(filteredError * 4e-6 + integral,-0.0015,0.0015);
            correction += Math.Clamp(desired - correction,-dt * 500e-6,dt * 500e-6);
            resampler.SetRates(Protocol.SampleRate * (1 + correction),Protocol.SampleRate);
            int needed = resampler.ResamplePrepare(frames,2,out float[] samples,out int sampleOffset);
            if (input.Length < needed * 4) input = new byte[needed * 4];
            long missing = source.Missing;
            source.Read(input,0,needed * 4);
            for (int i = 0; i < needed * 2; i++) samples[sampleOffset + i] = BinaryPrimitives.ReadInt16LittleEndian(input.AsSpan(i * 2)) / 32768f;
            int produced = resampler.ResampleOut(buffer,offset,needed,frames,2);
            if (produced < frames) Array.Clear(buffer,offset + produced * 2,(frames - produced) * 2);
            if (source.Missing != missing && source.BufferedFrames == 0)
            {
                // A real network outage needs fresh buffering, not an accumulated rate correction.
                Rebuffers++; started = false; resampler.Reset(); filteredError = integral = correction = 0;
            }
        }
    }
}
