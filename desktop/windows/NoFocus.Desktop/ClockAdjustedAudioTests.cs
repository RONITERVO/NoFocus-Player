using System.Buffers.Binary;

namespace NoFocus.Desktop;

internal static class ClockAdjustedAudioTests
{
    internal static void Run()
    {
        foreach ((int packets,int ppm,bool jitter) in new[] { (2,1000,false),(4,-1000,false),(2,-100,false),(4,100,false),(8,-100,false),(4,0,false),(4,100,true) })
        {
            PhoneAudioBuffer source = new(packets);ClockAdjustedAudio playback = new(source);
            byte[] pcm = new byte[960];
            for(int i=0;i<pcm.Length;i+=2) BinaryPrimitives.WriteInt16LittleEndian(pcm.AsSpan(i),8192);
            float[] output = new float[1920];
            uint sequence = 0;long nextPacket = 0;
            PriorityQueue<uint,long> arrivals = new();
            // Accelerated 10-minute streams. Input and output advance on independent virtual clocks.
            long period = 5000000L + ppm * 5L;
            bool playing = false;int quietBlocks = 0;
            for(long tick=0;tick<600L * 1000000000;tick+=20000000)
            {
                while(nextPacket<=tick) {
                    long delay = jitter && sequence > 0 ? sequence % 19 == 0 ? 8000000 : sequence % 11 == 0 ? 2000000 : 0 : 0;
                    arrivals.Enqueue(sequence++,nextPacket + delay);nextPacket+=period;
                }
                while(arrivals.TryPeek(out _,out long due) && due <= tick) source.Offer(arrivals.Dequeue(),pcm);
                playback.Read(output,0,output.Length);
                if (source.Missing != 0 || source.Trimmed != 0) throw new Exception($"Clock {packets}/{ppm} failed at {tick / 1e9:F2}s: missing={source.Missing}, trimmed={source.Trimmed}, frames={source.BufferedFrames}, correction={playback.CorrectionPpm:F1}");
                if(output.Any(sample => Math.Abs(sample)>.1)) playing=true;
                else if(playing)quietBlocks++;
            }
            if(!playing || source.Missing != 0 || source.Trimmed != 0 || playback.Rebuffers != 0 || quietBlocks != 0)
                throw new Exception($"Clock drift {packets} packets/{ppm} ppm: missing={source.Missing}, trimmed={source.Trimmed}, rebuffer={playback.Rebuffers}, silence={quietBlocks}");
            if (output.Any(value => !float.IsFinite(value) || Math.Abs(value - .25f) > .002)) throw new Exception("Resampler changed DC gain or produced invalid PCM.");
            Console.WriteLine($"10-minute clock test: target={packets * 5} ms, packet period offset={ppm} ppm, jitter={jitter}, correction={playback.CorrectionPpm:F1} ppm; no missing, trimmed or rebuffered audio.");
            source.Reset();Array.Fill(output,1);playback.Read(output,0,output.Length);
            if(output.Any(x=>x!=0))throw new Exception("New session leaked the previous resampler tail.");
        }
        foreach(int frequency in new[]{20,1000,10000,20000}) CheckTone(frequency);
    }
    private static void CheckTone(int frequency)
    {
        PhoneAudioBuffer source = new(4);ClockAdjustedAudio playback = new(source);
        float[] output = new float[1920];uint sequence = 0;double energy = 0;int samples = 0;
        for(int block=0;block<150;block++) {
            for(int packet=0;packet<4;packet++) {
                byte[] pcm = new byte[960];
                for(int n=0;n<240;n++) BinaryPrimitives.WriteInt16LittleEndian(pcm.AsSpan(n * 4),(short)Math.Round(16384 * Math.Sin((sequence * 240L + n) * 2 * Math.PI * frequency / 48000)));
                source.Offer(sequence++,pcm);
            }
            playback.Read(output,0,output.Length);
            if(block>=50)for(int i=0;i<output.Length;i+=2) {
                if(!float.IsFinite(output[i]) || output[i+1]!=0)throw new Exception("Resampler corrupted stereo channels.");
                energy+=output[i] * output[i];samples++;
            }
        }
        double rms=Math.Sqrt(energy/samples);
        if(Math.Abs(rms - Math.Sqrt(.125)) > .003 || source.Missing != 0 || source.Trimmed != 0)throw new Exception($"Tone {frequency} Hz: RMS {rms}, missing {source.Missing}, trimmed {source.Trimmed}");
        Console.WriteLine($"Sinc playout {frequency} Hz: gain retained within 1%, silent opposite channel, no gaps.");
    }
}
