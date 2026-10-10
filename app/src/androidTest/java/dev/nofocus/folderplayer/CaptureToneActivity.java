package dev.nofocus.folderplayer;

import android.app.Activity;
import android.media.*;
import android.os.Bundle;
import android.widget.TextView;

/** Separate test-APK UID: captured through the real playback-capture policy, never shipped in the app. */
public final class CaptureToneActivity extends Activity {
    private AudioTrack track;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("stop", false)) { finish(); return; }
        TextView text = new TextView(this); text.setText("NoFocus capture test\nStereo 440 / 880 Hz\nSynthetic audio only");
        text.setTextSize(28); text.setTextColor(0xffeeeeee); text.setBackgroundColor(0xff004455); text.setGravity(android.view.Gravity.CENTER);
        setContentView(text);
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        float[] tone = new float[48000 * 2];
        for (int i = 0; i < 48000; i++) {
            tone[i * 2] = (float)(Math.sin(i * 2 * Math.PI * 440 / 48000) * .25);
            tone[i * 2 + 1] = (float)(Math.sin(i * 2 * Math.PI * 880 / 48000) * .25);
        }
        track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(tone.length * 4).build();
        track.write(tone, 0, tone.length, AudioTrack.WRITE_BLOCKING); track.setLoopPoints(0, 48000, -1); track.play();
    }
    @Override public void onDestroy() { if (track != null) track.release(); super.onDestroy(); }
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("stop", false)) finish();
    }
}
