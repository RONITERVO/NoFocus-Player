package dev.nofocus.folderplayer;

import android.media.*;
import android.opengl.*;
import android.view.Surface;
import java.io.*;
import java.nio.*;

/** RGBA texture -> encoder surface. All calls belong to one encoding thread. */
@android.annotation.TargetApi(29)
final class VisualHardwareEncoder implements AutoCloseable {
    private MediaCodec codec;
    private MediaMuxer muxer;
    private Surface input;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private int program, texture, track = -1;
    private boolean muxing, ended;
    private final int width, height, fps;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private ByteBuffer pixels;

    VisualHardwareEncoder(File output, int width, int height, int fps) throws Exception {
        this.width = width; this.height = height; this.fps = fps;
        try {
            MediaCodecInfo chosen = null;
            for (MediaCodecInfo candidate : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!candidate.isEncoder() || !candidate.isHardwareAccelerated()) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = candidate.getCapabilitiesForType("video/avc");
                    if (caps.getVideoCapabilities().areSizeAndRateSupported(width, height, fps)) { chosen = candidate; break; }
                } catch (IllegalArgumentException ignored) { }
            }
            if (chosen == null) throw new IOException("Hardware H.264 is unavailable at this size and frame rate.");
            MediaCodecInfo.CodecCapabilities caps = chosen.getCapabilitiesForType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            // Detailed animated lines need considerably more bitrate than camera footage.
            int bitrate = (int)Math.min(60_000_000L, Math.max(8_000_000L, (long)width * height * fps / 2));
            format.setInteger(MediaFormat.KEY_BIT_RATE, caps.getVideoCapabilities().getBitrateRange().clamp(bitrate));
            if (caps.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            codec = MediaCodec.createByCodecName(chosen.getName());
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface();
            muxer = new MediaMuxer(output.toString(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            setupGl(); codec.start();
        } catch (Exception error) { close(); throw error; }
    }
    private void setupGl() throws IOException {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IOException("EGL initialization failed.");
        int[] attributes = { EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) throw new IOException("No encoder EGL configuration.");
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        surface = EGL14.eglCreateWindowSurface(display, configs[0], input, new int[]{EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IOException("Cannot bind encoder surface.");
        int vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 p;attribute vec2 uv;varying vec2 t;void main(){gl_Position=vec4(p,0.,1.);t=uv;}");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float;varying vec2 t;uniform sampler2D image;void main(){gl_FragColor=texture2D(image,t);}");
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
        int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) throw new IOException("Encoder shader link failed.");
        GLES20.glUseProgram(program);
        // Canvas row zero is the top; OpenGL's bottom vertices sample the last row.
        vertices.put(new float[]{-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0});
        int position = GLES20.glGetAttribLocation(program, "p"), uv = GLES20.glGetAttribLocation(program, "uv");
        vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(position);
        vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(uv);
        int[] textures = new int[1]; GLES20.glGenTextures(1, textures, 0); texture = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
        GLES20.glViewport(0, 0, width, height);
        pixels = ByteBuffer.allocateDirect(width * height * 4);
    }
    private static int shader(int type, String source) throws IOException {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] result = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0);
        if (result[0] == 0) { GLES20.glDeleteShader(shader); throw new IOException("Encoder shader compilation failed."); }
        return shader;
    }
    void frame(byte[] rgba, int index) throws Exception {
        drain(false);
        pixels.clear(); pixels.put(rgba).flip();
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw new IOException("Drawing encoder frame failed.");
        EGLExt.eglPresentationTimeANDROID(display, surface, index * 1_000_000_000L / fps);
        if (!EGL14.eglSwapBuffers(display, surface)) throw new IOException("Sending encoder frame failed.");
        drain(false);
    }
    void finish() throws Exception { codec.signalEndOfInputStream(); drain(true); }
    private void drain(boolean eof) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
        while (!ended) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int index = codec.dequeueOutputBuffer(info, eof ? 10000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eof) return;
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("Hardware encoder timed out.");
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxing) throw new IOException("Unexpected video format change.");
                track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxing = true;
            } else if (index >= 0) {
                ByteBuffer data = codec.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0) {
                    if (!muxing || data == null) throw new IOException("Missing encoded video format.");
                    data.position(info.offset); data.limit(info.offset + info.size); muxer.writeSampleData(track, data, info);
                }
                ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(index, false);
            }
        }
    }
    @Override public void close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program);
            if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display); display = EGL14.EGL_NO_DISPLAY;
        }
        if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); codec = null; }
        if (input != null) { input.release(); input = null; }
        if (muxer != null) { try { if (muxing) muxer.stop(); } catch (Exception ignored) { } muxer.release(); muxer = null; }
    }
}
