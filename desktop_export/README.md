# NoFocus PC export companion

The phone sends its unchanged source media, exact mono analysis, timed lyrics and export settings over pinned HTTPS. This companion draws every frame using the same vendored Visual-Music-Lyrics canvas renderer and fonts, and feeds batches of raw RGBA frames to FFmpeg on the PC. It does not send phone-rendered frames over Wi-Fi. The existing Visual-Music-Lyrics desktop server and PC audio streaming protocol are unchanged.

## Use

1. Install Node.js 22+ and Microsoft Edge on the PC. Run `desktop_export/start.cmd` (or `start.ps1`). The first launch installs the locked dependencies and portable FFmpeg. Leave the companion running while exporting.
2. Open the displayed pairing page. Connect the phone to the same LAN/Wi-Fi. Scan the QR code with the phone camera and tap **Pair PC**, or paste its code under **Visuals → Export video → Pair PC**. Select the PC address on the same network as the phone. If Windows asks, allow Node on your private network.
3. Leave **Export using → Automatic** selected. It uses the paired PC when reachable and falls back to the phone before an upload starts. **Paired PC** requires the PC; **This phone** always renders locally.
4. After upload, use other apps or close NoFocus. Reopen **Export video** to check progress and **Save to phone**. Videos go to **Download/NoFocus**. Results expire 24 hours after the job was created.

Interrupted uploads can be resumed from Export (the media upload restarts, using the same job ID). Downloads are checked against a SHA-256 digest before publishing and can be retried. Once a PC job exists, a connection failure retains it; it never silently starts a duplicate phone export. Cancel/remove retires the PC job. If the PC is offline, reconnect first to cancel it. Pending songs cannot be removed from the phone library until their PC job is retired.

The companion renders one job at a time, with up to three uploaded/queued/active jobs and eight retained jobs. Completed media and failed inputs are cleaned up. A PC restart retains completed and queued jobs; an interrupted render is marked failed so it can be explicitly restarted. Phone and companion renderer hashes must match; update both after changing the vendored renderer or fonts.

## Audio and video

**Preserve original audio** copies the first source audio stream without resampling, gain changes, duration trimming, or re-encoding. It does not improve the quality of an already lossy source. Preserved non-AAC audio uses MKV. Explicit AAC mode encodes 320 kbps audio for MP4 compatibility.

PC high-quality video uses software H.264 CRF 17; lossless RGB uses CRF 0. On the phone, high-quality video prefers a hardware H.264 surface encoder with a size/frame-rate-based bitrate, falling back to software if initialization is unsupported. The phone's advanced encoder selector can force hardware or software. Lossless RGB always uses software. Both paths consume exactly the same canvas frames. Fonts and rasterization can differ between Windows and Android, especially Signal bloom's Georgia fallback.

Modern WebViews send raw RGBA `ArrayBuffer` frames to native code with acknowledgments and a bounded queue. Older WebViews retain the PNG compatibility path. The final phone export status reports draw, pixel-read and transfer/encoder-wait time per frame; use these measurements to distinguish rendering from encoding bottlenecks. Local phone export and transfers require the screen to remain open; fully uploaded PC jobs run independently.

## Security and operations

The service listens on TCP 49632. Every API request requires a random 256-bit token. TLS identity is pinned to the certificate fingerprint in the QR/code, independently of the changing LAN address. No certificate is installed into the phone trust store. Pairing accepts local IPv4 addresses, including private LAN, loopback, and Tailscale's address range. Browser-origin requests are rejected; frame ingestion is on a separate per-job random loopback endpoint, inaccessible from the LAN. No remote scripts or lyrics run as code in either renderer.

State, logs, pairing page and retained jobs live under `%LOCALAPPDATA%/NoFocusExport`. Pairing grants access to this companion: keep the QR/code and that directory private. To revoke all paired phones, stop the companion and remove only `identity.json`; restarting creates a new key/certificate. A changed PC address requires scanning its new code. Keep a DHCP reservation for convenient long-term pairing. Do not port-forward this service to the public internet.

Optional: run `enable-startup.ps1` once to start the companion hidden at Windows sign-in. Run it with `-Remove` to remove that shortcut. It does not install a system service or change firewall rules. The repository must stay at the same path. `background.ps1` starts it manually without a console. A second instance exits when the listening port is already occupied. Restart the running companion after updating its code.

Environment options: `NOFOCUS_EXPORT_PORT`, `NOFOCUS_EXPORT_STATE`, `NOFOCUS_FFMPEG` (executable path), `NOFOCUS_BROWSER` (Playwright browser channel; defaults to `msedge`). The first three optional paths/settings are for trusted operator configuration, never supplied by uploaded jobs. Initial testing used the Honor 400 Pro, Windows Edge, and bundled FFmpeg. Other phones may choose software when hardware encoding is unavailable.

## Verification

Run `npm ci` then `npm test` in this folder. The integration test covers authentication/origin rejection, invalid profiles and renderer versions, idempotent creation, upload bounds, exact source PCM preservation, pixel parity for lossless output, restart recovery, downloads and active cancellation. It uses temporary data and removes it.

`device-test-server.cjs` starts an isolated companion on 49633 with its pairing file in the ignored `artifacts/pc-device-test` directory. Put that file into app-private `files/pc-test-pair.txt`, optionally use `adb reverse tcp:49633 tcp:49633`, and run instrumentation with `-e pcExport true`. The test rejects a wrong TLS fingerprint, uploads from the real Android library, recreates the client to recover the pending job, saves the PC-rendered output, compares every decoded PCM sample, and cancels a second job. It restores the user's pairing and removes its temporary media. Use a LAN address in the test pairing for a real Wi-Fi test.

No cloud service, Gemini credentials, or Visual-Music-Lyrics account is involved. This companion uses the same Apache-licensed renderer already bundled by the phone. Node dependency licenses are distributed with their packages and versions are locked in `package-lock.json`.
