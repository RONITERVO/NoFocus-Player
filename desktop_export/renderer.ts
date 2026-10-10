import { createVideoRenderer } from '../visualizer/vendor/src/lib/video/VideoRenderer';
import { OfflineSpectrum } from '../visualizer/vendor/src/lib/video/OfflineSpectrum';

let videoEncoder: VideoEncoder | undefined;
let encoded: EncodedVideoChunk[] = [], encoderError: Error | undefined;
(window as any).prepare = async (config: any) => {
  if (config.mode === 'lossless' || config.software || typeof VideoEncoder === 'undefined') return 'rgba';
  const settings: VideoEncoderConfig = {
    codec: 'avc1.64002A', width: config.width, height: config.height, framerate: config.fps,
    bitrate: Math.min(60000000, Math.max(8000000, config.width * config.height * config.fps * .5)),
    hardwareAcceleration: 'prefer-hardware', latencyMode: 'realtime', avc: {format: 'annexb'},
  };
  try {
    if (!(await VideoEncoder.isConfigSupported(settings)).supported) return 'rgba';
    videoEncoder = new VideoEncoder({output: chunk => encoded.push(chunk), error: error => { encoderError = error; }});
    videoEncoder.configure(settings);
    // Exercise the driver before the real job starts; unsupported hardware can
    // fall back without losing any real frames or requiring another upload.
    const probe = new OffscreenCanvas(config.width, config.height);
    probe.getContext('2d')!.fillRect(0, 0, config.width, config.height);
    const frame = new VideoFrame(probe, {timestamp: 0});
    try { videoEncoder.encode(frame, {keyFrame: true}); } finally { frame.close(); }
    await videoEncoder.flush();
    if (encoderError || encoded.length !== 1) throw encoderError || new Error('Hardware encoder produced no frame.');
    videoEncoder.reset(); videoEncoder.configure(settings); encoded = [];
    return 'h264';
  } catch {
    if (videoEncoder?.state !== 'closed') videoEncoder?.close();
    videoEncoder = undefined; encoded = []; encoderError = undefined;
    return 'rgba';
  }
};

// One acknowledged binary batch at a time bounds browser/encoder memory. Fetching
// a large body for every batch can spend far longer in Chromium's upload path
// than in drawing or encoding; this connection streams directly over loopback.
async function frameConnection() {
  const url = new URL('frames', location.href); url.protocol = 'ws:';
  const socket = new WebSocket(url);
  let pending: {resolve: () => void; reject: (error: Error) => void; frames: number} | undefined;
  const fail = () => { pending?.reject(new Error('PC frame connection closed. Retry this export.')); pending = undefined; };
  socket.onclose = fail; socket.onerror = fail;
  await new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => { socket.close(); fail(); }, 15000);
    socket.onopen = () => { clearTimeout(timeout); resolve(); };
    pending = {resolve, reject: error => { clearTimeout(timeout); reject(error); }, frames: 0};
  });
  pending = undefined;
  socket.onmessage = event => {
    const current = pending; pending = undefined;
    if (!current) { socket.close(); return; }
    try {
      const response = JSON.parse(event.data);
      if (response.error || response.frames !== current.frames) throw new Error(response.error || 'Invalid frame acknowledgment.');
      current.resolve();
    } catch (error) { current.reject(error as Error); }
  };
  return {
    send(bytes: Uint8Array, frames: number) {
      return new Promise<void>((resolve, reject) => {
        if (socket.readyState !== WebSocket.OPEN || pending) { reject(new Error('PC frame connection unavailable.')); return; }
        const timeout = setTimeout(() => { socket.close(); fail(); }, 60000);
        pending = {frames, resolve: () => { clearTimeout(timeout); resolve(); }, reject: error => { clearTimeout(timeout); reject(error); }};
        try { socket.send(bytes); } catch (error) { pending.reject(error as Error); pending = undefined; }
      });
    },
    close() { socket.close(); },
  };
}

// This page only talks to its per-job loopback server. No song content becomes HTML or script.
(window as any).render = async (config: any) => {
  const response = await fetch('analysis');
  if (!response.ok) throw new Error('Cannot load analysis');
  const samples = new Float32Array(await response.arrayBuffer());
  const spectrum = new OfflineSpectrum([samples], 44100);
  const renderer = await createVideoRenderer(config);
  const total = Math.ceil(config.duration * config.fps);
  const batchSize = Math.max(1, Math.min(4, Math.floor(24 * 1024 * 1024 / (config.width * config.height * 4))));
  const timings = {drawMs: 0, readMs: 0, sendMs: 0};
  let connection: Awaited<ReturnType<typeof frameConnection>> | undefined;
  try {
    connection = await frameConnection();
    for (let index = 0; index < total;) {
      const count = Math.min(batchSize, total - index);
      let bytes = videoEncoder ? undefined : new Uint8Array(8 + config.width * config.height * 4 * count);
      for (let offset = 0; offset < count; offset++) {
        const started = performance.now();
        renderer.draw((index + offset) / config.fps, spectrum);
        const drawn = performance.now();
        if (videoEncoder) {
          const frame = new VideoFrame(renderer.canvas, {timestamp: Math.round((index + offset) * 1000000 / config.fps)});
          try { videoEncoder.encode(frame, {keyFrame: (index + offset) % (config.fps * 2) === 0}); }
          finally { frame.close(); }
        } else bytes!.set(renderer.pixels(), 8 + offset * config.width * config.height * 4);
        timings.drawMs += drawn - started;
        timings.readMs += performance.now() - drawn;
      }
      if (videoEncoder) {
        const encoding = performance.now();
        await videoEncoder.flush();
        if (encoderError) throw encoderError;
        if (encoded.length !== count || encoded.some((chunk, offset) => chunk.timestamp !== Math.round((index + offset) * 1000000 / config.fps)))
          throw new Error('Hardware encoder changed the frame sequence. Retry with the PC software encoder.');
        bytes = new Uint8Array(8 + encoded.reduce((size, chunk) => size + chunk.byteLength, 0));
        let offset = 8;
        for (const chunk of encoded) { chunk.copyTo(bytes.subarray(offset)); offset += chunk.byteLength; }
        encoded = [];
        timings.readMs += performance.now() - encoding;
      }
      const header = new DataView(bytes!.buffer);
      header.setUint32(0, index, true); header.setUint32(4, count, true);
      const sending = performance.now();
      await connection.send(bytes!, index + count);
      timings.sendMs += performance.now() - sending;
      index += count;
    }
    return timings;
  } finally { connection?.close(); renderer.destroy(); if (videoEncoder?.state !== 'closed') videoEncoder?.close(); }
};
