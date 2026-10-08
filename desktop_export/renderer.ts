import { createVideoRenderer } from '../visualizer/vendor/src/lib/video/VideoRenderer';
import { OfflineSpectrum } from '../visualizer/vendor/src/lib/video/OfflineSpectrum';

// This page only talks to its per-job loopback server. No song content becomes HTML or script.
(window as any).render = async (config: any) => {
  const response = await fetch('analysis');
  if (!response.ok) throw new Error('Cannot load analysis');
  const samples = new Float32Array(await response.arrayBuffer());
  const spectrum = new OfflineSpectrum([samples], 44100);
  const renderer = await createVideoRenderer(config);
  const total = Math.ceil(config.duration * config.fps);
  const batchSize = Math.max(1, Math.min(4, Math.floor(24 * 1024 * 1024 / (config.width * config.height * 4))));
  try {
    for (let index = 0; index < total;) {
      const count = Math.min(batchSize, total - index);
      const bytes = new Uint8Array(config.width * config.height * 4 * count);
      for (let offset = 0; offset < count; offset++) {
        renderer.draw((index + offset) / config.fps, spectrum);
        bytes.set(renderer.pixels(), offset * config.width * config.height * 4);
      }
      const sent = await fetch(`frames?index=${index}&count=${count}`, { method: 'POST', body: bytes });
      if (!sent.ok) throw new Error(await sent.text());
      index += count;
    }
  } finally { renderer.destroy(); }
};
