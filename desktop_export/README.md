# NoFocus PC export companion

**Normal use:** open the NoFocus Windows app. It bundles and automatically starts this engine, displays its queue, and pairs audio and exports with one QR scan. The instructions below are for the optional standalone developer launcher. See [the integrated Windows app](../docs/windows-companion.md).

The phone sends its unchanged source media, exact mono analysis, timed lyrics and export settings over pinned HTTPS. This companion draws every frame using the same vendored Visual-Music-Lyrics canvas renderer and fonts, and uses hardware H.264 when supported, then muxes the encoded frames with the original audio using FFmpeg. A persistent loopback binary connection also carries raw RGBA for lossless RGB and software fallback. It does not send phone-rendered frames over Wi-Fi. The existing Visual-Music-Lyrics desktop server and PC audio streaming protocol are unchanged.

## Use

1. Install Node.js 22+ and Microsoft Edge on the PC. Run `desktop_export/start.cmd` (or `start.ps1`). The first launch installs the locked dependencies and portable FFmpeg. Leave the companion running while exporting.
2. Open the displayed pairing page. Connect the phone to the same LAN/Wi-Fi. Scan the QR code with the phone camera and tap **Pair PC**, or paste its code under **Visuals → Export video → Pair PC**. Select the PC address on the same network as the phone. If Windows asks, allow Node on your private network.
3. Leave **Export using → Automatic** selected. It uses the paired PC when reachable and falls back to the phone before an upload starts. **Paired PC** requires the PC; **This phone** always renders locally.
4. After upload, use other apps or close NoFocus. Use **Queue** in the phone header to check each export and **Save to phone**. You can import another capture, edit its Gemini timings, and submit another export while the PC works. Videos go to **Download/NoFocus**. Results expire 24 hours after completion.

Interrupted uploads can be resumed from Export (the media upload restarts, using the same job ID). Downloads are checked against a SHA-256 digest before publishing and can be retried. Once a PC job exists, a connection failure retains it; it never silently starts a duplicate phone export. Cancel/remove retires the PC job. If the PC is offline, reconnect first to cancel it. Pending songs cannot be removed from the phone library until their PC job is retired.

The companion renders one job at a time, with up to eight unfinished jobs and sixteen retained jobs. The phone persists each job separately, including across app upgrades. **Pause** stops a queued or active render and keeps its upload; **Resume export** renders it again from the start without another upload. Failed renders can also be retried. A PC restart automatically restarts interrupted rendering from the beginning, preserves completed results and resumes the queue. Completed jobs release their uploaded inputs. Inactive unfinished jobs expire after 24 hours; queued/active jobs are retained until they finish. Save or remove old results to make room. Phone and companion renderer hashes must match; update both after changing the vendored renderer or fonts.

## Audio and video

**Preserve original audio** copies the first source audio stream without resampling, gain changes, duration trimming, or re-encoding. It does not improve the quality of an already lossy source. Preserved non-AAC audio uses MKV. Explicit AAC mode encodes 320 kbps audio for MP4 compatibility.

PC high-quality video first probes the browser hardware H.264 encoder at 0.5 bits per pixel per frame (8–60 Mbps), matching the phone quality policy. It streams H.264 over loopback and FFmpeg copies it without another video encode. If hardware initialization is unsupported, software H.264 uses CRF 17; lossless RGB always uses CRF 0 with exact raw frames. All renderers advance every frame in order; no resolution or frame-rate reduction is used to accelerate export. On the phone, high-quality video prefers a hardware H.264 surface encoder with a size/frame-rate-based bitrate, falling back to software if initialization is unsupported. The phone's advanced encoder selector can force hardware or software. Lossless RGB always uses software. Both paths consume exactly the same canvas frames. Fonts and rasterization can differ between Windows and Android, especially Signal bloom's Georgia fallback.

Modern WebViews send raw RGBA `ArrayBuffer` frames to native code with acknowledgments and a bounded queue. Older WebViews retain the PNG compatibility path. PC queue cards show the encoder, elapsed time and estimated remaining time. The final phone export status reports draw, pixel-read and transfer/encoder-wait time per frame; use these measurements to distinguish rendering from encoding bottlenecks. Local phone export and transfers require the screen to remain open; fully uploaded PC jobs run independently.

## Security and operations

The service listens on TCP 49632. Every API request requires a random 256-bit token. TLS identity is pinned to the certificate fingerprint in the QR/code, independently of the changing LAN address. No certificate is installed into the phone trust store. Pairing accepts local IPv4 addresses, including private LAN, loopback, and Tailscale's address range. Browser-origin requests are rejected; frame ingestion is on a separate per-job random loopback endpoint, inaccessible from the LAN. Its WebSocket checks the exact host, origin, path, frame sequence and payload limit. Only one acknowledged batch is in flight; compression is disabled. No remote scripts or lyrics run as code in either renderer.

State, logs, pairing page and retained jobs live under `%LOCALAPPDATA%/NoFocusExport`. Pairing grants access to this companion: keep the QR/code and that directory private. To revoke all paired phones, stop the companion and remove only `identity.json`; restarting creates a new key/certificate. A changed PC address requires scanning its new code. Keep a DHCP reservation for convenient long-term pairing. Do not port-forward this service to the public internet.

Optional: run `enable-startup.ps1` once to start the companion hidden at Windows sign-in. Run it with `-Remove` to remove that shortcut. It does not install a system service or change firewall rules. The repository must stay at the same path. `background.ps1` starts it manually without a console. A second instance exits when the listening port is already occupied. Restart the running companion after updating its code.

Environment options: `NOFOCUS_EXPORT_PORT`, `NOFOCUS_EXPORT_STATE`, `NOFOCUS_FFMPEG` (executable path), `NOFOCUS_BROWSER` (Playwright browser channel; defaults to `msedge`), `NOFOCUS_PC_ENCODER=software` (force PC software encoding for driver troubleshooting). These settings are for trusted operator configuration, never supplied by uploaded jobs. Initial testing used the Honor 400 Pro, Windows Edge, and bundled FFmpeg. Other phones may choose software when hardware encoding is unavailable.

## Verification

Run `npm ci` then `npm test` in this folder. The integration test covers authentication/origin rejection, invalid profiles and renderer versions, idempotent creation, upload bounds, exact source PCM preservation, pixel parity for lossless output, hardware frame count/composition, multi-job queues, pause/resume, restart recovery, downloads and active cancellation. It uses temporary data and removes it.

`device-test-server.cjs` starts an isolated companion on 49633 with its pairing file in the ignored `artifacts/pc-device-test` directory. Put that file into app-private `files/pc-test-pair.txt`, optionally use `adb reverse tcp:49633 tcp:49633`, and run instrumentation with `-e pcExport true`. The test rejects a wrong TLS fingerprint, uploads from the real Android library, migrates the legacy pending job, queues a second export, recreates the client to recover both jobs, saves the PC-rendered output, compares every decoded PCM sample, and cancels an independent queued job. It also submits two exports through the actual phone controls, reopens the queue and saves one without removing the other. It restores the user's pairing and removes its temporary media. Use a LAN address in the test pairing for a real Wi-Fi test.

No cloud service, Gemini credentials, or Visual-Music-Lyrics account is involved. This companion uses the same Apache-licensed renderer already bundled by the phone. Node dependency licenses are distributed with their packages and versions are locked in `package-lock.json`.

## Measured performance

On the tested Ryzen 9 7950X / RTX 5070 PC, a real 211.942-second song at 720 × 1280 / 60 fps completed all 12,717 frames in 73.2 seconds, including its selected AAC 320 kbps output. The previous raw HTTP transfer path spent 81.8 seconds sending frames for a 10-second sample; the hardware path spent 0.30 seconds sending the same sample. These are local measurements, not a guarantee for other hardware. Preserve-original mode remains a separate audio choice; changing the video encoder does not re-encode preserved audio.
