# Integrated Windows audio and exports

Windows 1.5.0 and Android 1.10.0 introduce one Windows home screen and one pairing for both audio directions and phone video exports. This continues the compact UI approach from [the earlier usability work](https://chatgpt.com/s/cx_6aca2ee804588191a40bab2bd039510a): large everyday controls, short labels, and separate setup/help pages.

Windows **1.5.1** and Android **1.10.1** include the review fixes. Update both for reverse audio v3; pairing remains valid. Live Windows playback now compensates for independent audio clocks. Saving its buffer setting preserves newer manual pairing settings. Saved phone registration is bound to the export token and certificate; revoking/replacing that identity returns Windows to an unpaired state. Old phone records without an identity marker are ignored until the still-paired phone re-registers when opened.

## Everyday use

Open the existing Windows shortcut. The export engine starts hidden automatically. Choose **Pair phone**, scan with the phone camera and explicitly tap **Pair PC**. Copy/paste is available in **Connect PC → Pair PC** if scanning is inconvenient. Both devices need the same LAN. The QR screen defaults to a home-network address; its selector handles multiple adapters.

The two equal audio buttons say where the sound will be heard: **Listen on phone** (PC → phone) and **Listen on PC** (phone → PC). Start the corresponding phone receiver or sharing session. Android still requires its sharing approval every time; pairing does not start capture. The Windows app prevents simultaneous opposing streams. Exporting remains independent of audio streaming.

Phone exports appear in the Windows home queue. Select a row to pause, resume, save or remove it. Saving on Windows verifies the server's SHA-256 checksum before publishing the destination file. It leaves the server copy available for the phone. Removing needs confirmation and removes only that PC job, not original songs or already saved videos. The phone will report a removed job as missing until its local queue entry is removed.

Closing the window keeps the app and exports in the tray; reopening the shortcut activates the same instance. **Settings → Quit NoFocus** stops audio and gracefully stops an owned export engine. If work is queued/rendering, Quit asks before interrupting it. Interrupted renders restart from uploaded sources next launch. A currently running compatible standalone companion is reused and is not stopped by an app that does not own it. A crashed owned companion is retried up to three times; **Pair phone → Retry connection** permits another attempt.

## Existing installations

Existing export identity, uploaded jobs and completed files remain under `%LOCALAPPDATA%/NoFocusExport`. The renderer and export protocol remain compatible. Scan the same PC once in the updated app to also configure audio. Re-pairing that identity after an address change is allowed while jobs exist; changing to a different certificate/token is blocked until pending jobs are saved/removed. Forgetting a unified pairing on the phone clears both audio directions too.

If an old standalone export process is already listening on 49632, the Windows app explains that it must be closed once. It never kills a process merely because it owns that port. If you previously enabled the standalone sign-in launcher, run `desktop_export/enable-startup.ps1 -Remove` before switching launchers. The integrated app starts its engine when the app opens; it does not silently add Windows sign-in startup.

**Settings → Older phone app / manual setup** retains the original audio flow for Android 6–9 and older app versions. Its per-direction codes are compatibility options. Normal Android 10+ setup uses the single QR/code. Source-only development can still use `desktop_export/start.cmd`; end users do not need it.

## Pairing and packaging

The existing 256-bit bearer token and pinned TLS certificate authorize the pairing exchange on HTTPS 49632. After authenticated phone registration, the server supplies separate 80-bit audio codes derived with HMAC-SHA256 and direction-specific labels (`NoFocus audio v1 pc-to-phone` / `NoFocus audio v1 phone-to-pc`). PC → phone retains NFP2 v2 on UDP 39821. Phone → PC uses [NFP3 v3 with fresh receiver challenges](phone-audio.md) on UDP 39822. The server records the observed authenticated connection address, never a client-supplied destination. This setup selects the most recently registered phone; it is not a multi-phone audio mixer.

Windows management and QR endpoints require the same token **and** a loopback connection; browser-origin requests are rejected. Secrets stay out of process arguments and logs. The phone refreshes its LAN address when its main screen opens. PC address changes still require scanning its updated QR. Audio capture policies, Android mixing, VPN/LAN isolation and output-device changes retain their existing limitations.

The release builder embeds checksum-pinned Node v24.21.0 LTS, locked npm dependencies, FFmpeg, original renderer sources and fonts as one archive. It includes the dependencies' license files and a runtime hash manifest. The app extracts into a content-addressed per-user cache and runs the bundled Node executable hidden, without a shell. Graceful shutdown is requested over the owned child's standard input; a closed parent pipe also stops the managed engine. Microsoft Edge is still required for video rendering. No Node, npm, Python or .NET installation is needed by end users.

## Validation

- Node integration tests: authenticated unified pairing, wrong-token/origin rejection, observed address handling, independent stable audio credentials, identity persistence and revocation, exact PCM and lossless RGB, hardware H.264 parity, queue recovery, pause/retry and cancellation.
- Windows: protocol/downloader tests, stale manual-settings preservation, bundled service startup/reuse/shutdown/restart, pinned native API access and QR generation; native Home/Pair phone/Settings snapshots at two window sizes. The review update adds [v3 replay protection, 70 simulated minutes of independent audio clocks and resampler gain/channel checks](phone-audio.md#verification).
- Android: unit tests, lint and builds; 20 screens at normal size and 320 × 480 dp, both orientations and 100/150/200% text (140 screen checks). New PC listening, pairing and shared settings pages have no scrolling and preserve 48 dp controls.
- Honor 400 Pro over Wi-Fi: a single pairing configures both audio directions, wrong TLS fingerprints are rejected, the bundled Windows engine renders the actual phone upload, the returned video preserves every tested PCM sample, durable queue/UI download and cancellation checks pass. Existing preferences and media are restored after the synthetic test. This is not a Suno-specific listening test or an acoustic-latency measurement.

Build with `desktop/windows/build-release.ps1`; use `--self-test`, `--companion-test`, and `--ui-check <directory>` on the resulting executable. The device fixture uses `--companion-device-test <isolated-directory>` with `NOFOCUS_TEST_HOST` and stops after ten minutes or a `stop` file; never point that fixture at production state.
