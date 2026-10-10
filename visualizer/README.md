# Phone visual music

The APK bundles the actual Visual-Music-Lyrics canvas exporter, both scene engines, lyric timing, Gemini converter and fonts. The vendored TypeScript files are unmodified, taken from the working copy of `D:/Projects/Apps/Visual-Music-Lyrics` based on commit `d822a3a17176b65ca08bf53732cbeefbe308e162` (PR #8). `vendor/source-hashes.json` identifies the exact files, including working-copy changes at vendoring time. Apache-2.0 and the font OFL notices are included in the APK.

`app.ts` supplies phone controls around that renderer. Preview and export both advance the same renderer and OfflineSpectrum in frame order, including replay from zero after seeking backwards. Export has its own renderer, so previews cannot contaminate export state. Analysis is mono float at 44.1 kHz; native playback uses stereo float at the source rate. Neither analysis conversion nor playback decoding changes the original media retained for export.

This uses the same visual engine, composition and lyric rules. Font rasterization can differ across operating systems. Caveat and Patrick Hand are bundled. Signal bloom retains upstream's `Georgia, serif` stack, so Android uses its serif fallback when Georgia is unavailable. The desktop live view uses DOM text; this app uses the desktop **export canvas compositor** for both listening and exporting.

## Rebuild

Generated HTML/CSS/JS and fonts under `app/src/main/assets/visualizer` are committed so Gradle builds work without Node or internet. After changing phone source, run `npm install` and `npm run build` in this folder. Run `npm run verify` to check the vendored sources. Or reuse an installed esbuild:

```powershell
node visualizer/build.cjs D:/Projects/Apps/Visual-Music-Lyrics/node_modules/esbuild
node visualizer/verify.cjs D:/Projects/Apps/Visual-Music-Lyrics
node visualizer/test-renderer.cjs D:/Projects/Apps/Visual-Music-Lyrics/node_modules
```

## Device verification

Build/install the debug and instrumentation APKs, then run (replace SERIAL):

```powershell
adb -s SERIAL shell am instrument -w -e visualMusic true -e theme sketchbook dev.nofocus.folderplayer.test/dev.nofocus.folderplayer.CompactUiTest
adb -s SERIAL shell am instrument -w -e visualMusic true -e theme signal-bloom dev.nofocus.folderplayer.test/dev.nofocus.folderplayer.CompactUiTest
```

These tests create a temporary synthetic master MKV with stereo float audio, import Gemini JSON through the WebView, verify AudioTrack playback, export a 720 × 1280 lossless RGB video, compare a decoded active-lyric frame pixel-for-pixel with its preview, compare every exported PCM sample, and cancel an encoder waiting for input. They also verify high-quality H.264 with preserved PCM and optional AAC/MP4 output. Temporary test media and published exports are removed and selection/share preferences restored. Test screenshots remain in private app files.

The fixture begins with an English-only vocal before the first Spanish line. Its blue text must be visible in preview and lossless export. The browser regression checks 15 English-only/bilingual layouts across portrait, landscape and square sizes without changing the input timestamps. Version 1.6.1 fixes a mismatch between text placement and the reflection clipping waterline for cues without a primary line; existing saved songs need no migration or JSON edits.

The WebView serves only packaged assets and validated private analysis IDs on a local HTTPS origin; network requests, navigation, file/content access and arbitrary script loading are blocked. Imported text is treated as data. Modern WebViews send raw RGBA ArrayBuffers through an origin-scoped native message bridge with acknowledgments and a bounded two-frame queue; old WebViews retain PNG compatibility. Hardware H.264 draws the same frames onto a MediaCodec surface; software FFmpeg handles lossless RGB or unsupported hardware. output is published through pending MediaStore entries. Cancelling or closing the export screen kills the native encoder and removes temporary output. Playback can continue with the screen closed; frame export requires the screen open.

Limits: Android 10+, media under 768 MB / 20 minutes, sufficient private storage for original + float playback + analysis, export up to 1920 pixels per side at 24/30/60 fps. Software H.264 uses CRF 17; hardware H.264 uses a size/frame-rate-based bitrate; lossless RGB uses CRF 0. Preserved non-AAC audio uses MKV. Explicit AAC export targets 320 kbps. Original audio is copied from the retained input, without gain changes, normalization, resampling or a duration trim.

## Hybrid export (1.7.0)

Export offers Automatic, This phone, and Paired PC. See [the PC companion guide](../desktop_export/README.md) for launch, QR pairing, optional Windows startup, protocol/security details, durable jobs and end-to-end tests. The generated renderer-version.txt hashes the vendored source manifest and bundled fonts so incompatible companions are rejected before upload. Phone-local export reports per-frame draw, read and transfer/encoder-wait timings. Original source audio is preserved in both routes by default.

## PC queue and faster export (1.8.0)

The phone can submit multiple prepared songs while the PC renders them sequentially. Queue cards provide progress, pause, retry, per-video download and removal. Existing single-job preferences migrate without replacing the job ID or settings. Pausing preserves the uploaded inputs; resuming or recovering a PC restart renders from the beginning without another upload. The companion uses browser hardware H.264 plus an acknowledged binary loopback connection, with raw/software support for lossless RGB and unsupported hardware. Upgrade both the phone and companion for export protocol 2; renderer source/font hashes remain unchanged.
