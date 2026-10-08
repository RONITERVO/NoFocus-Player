# NoFocus Player

NoFocus Player lets an Android phone play either a local music folder or encrypted PC audio without requesting audio focus. YouTube, games, and other apps can therefore keep playing at the same time.

## Easy Wi-Fi speaker setup

Requirements: Windows 10/11, Android 6 or newer, and both devices on the same home network. A strong 5 GHz or 6 GHz connection is recommended.

1. Install and open **NoFocus Player** on the phone.
2. Select **PC audio** and tap **Start**. **Connect PC** shows the pairing code.
3. Download and open **NoFocus-Speaker-Windows-x64.exe** on the PC. It is self-contained; Python and .NET do not need to be installed.
4. The PC normally finds the phone automatically. Enter the pairing code shown on the phone once, then click **Start listening on phone**.

The PC remembers its setup and protects the pairing code with the current Windows account. On later launches, streaming is one click. If automatic discovery is blocked by a router or VPN, find the phone address under **Connect PC → More options**. **Copy setup** on the phone and **Paste phone setup** on the PC provide another easy setup route when clipboard sync is enabled.

The sender captures the default PC output, never the microphone. Changing the Windows default output device while streaming may require pressing Stop and Start once.

## Performance

The native Windows sender uses:

- event-driven WASAPI system-output capture;
- a bounded 80 ms capture buffer that discards stale audio rather than growing latency;
- an MMCSS `Pro Audio` sender thread;
- absolute 5 ms high-resolution deadlines, avoiding cumulative timer drift;
- AES-GCM accelerated by the PC runtime; and
- one-packet time diversity, recovering an isolated Wi-Fi loss without a round trip.

On the tested 32-logical-core Windows PC, the native app used roughly 35-39 MB working memory and 0.1-0.3 CPU-seconds per 25 seconds. With all logical processors deliberately saturated, it still sent 5,002 primary packets in 25 seconds and the phone reported zero gaps.

## Latency and quality

Audio is lossless 48 kHz stereo signed 16-bit PCM. Each encrypted packet contains 5 ms and remains below a normal LAN MTU. Redundancy keeps total traffic below 3.2 Mbit/s.

The phone offers three profiles under **Connect PC → More options → Sound quality**. Changes apply the next time PC audio starts:

- **Fastest - 10 ms:** lowest network buffering; best Wi-Fi required.
- **Balanced - 20 ms:** recommended default.
- **Reliable - 40 ms:** for congested or weaker networks.

Literal zero latency is physically impossible. PC capture, Wi-Fi scheduling, Android's mixer, and the DAC all add time. On an Honor 400 Pro running Android 16, Ultra held a 5-15 ms network queue with zero gaps while Android reported 23-31 ms for its fast output track. Describe this feature as **low latency**, not zero latency, unless an acoustic measurement on the target hardware proves otherwise.

## Privacy and security

- A random 80-bit pairing secret is generated on the phone and can be rotated.
- Windows stores the remembered secret with DPAPI for the current Windows account.
- Hello packets use HMAC-SHA-256 authentication.
- Every PCM packet is encrypted and authenticated with AES-256-GCM.
- Sessions use a random 64-bit ID and monotonic packet sequence.
- The phone locks an active session to one source, rejects replayed/duplicate frames, bounds all buffers, and times out dead sessions.
- Discovery exchanges only an eight-byte service marker and port. It never exposes the pairing secret.
- The pairing secret is excluded from Android backup.

Audio is sent directly over the local network on UDP port `39821`; there is no cloud service or telemetry.

## Folder player

1. Select **My music**, tap **Folder** (or **Choose** on first use), and select `Music` or one of its subfolders. Playback starts automatically.
2. Use **Play / Pause**, **Previous / Next**, **Stop**, and **Volume** on the same screen. Open **Setup** for shuffle, choosing or rescanning a folder, and **Add music**. **Add music → Get video audio** copies supported audio from local videos without lossy re-encoding.
3. Open YouTube or another app; music keeps playing. Return to NoFocus to see the current playback state.

Android 11 and newer may prevent selecting the storage root or `Download` directly. Common MP3, M4A, AAC, FLAC, Ogg/Opus, WebM, WAV, 3GP, AMR, and MIDI files are supported.

Starting either NoFocus source stops the other NoFocus source while unrelated apps keep playing.

## Visual music on the phone

Open **Visuals** in the home header. Import original audio or a captured master MKV, add the matching Gemini JSON under **Lyrics**, and preview or export with the original Visual-Music-Lyrics canvas renderer. Both Living sketchbook and Signal bloom use the desktop export composition, word timing, reactivity and fonts. Native float playback continues in the background without audio focus; listening volume does not change export audio.

**Preserve original audio** copies the retained source audio without re-encoding. Non-AAC audio stays in MKV; explicit AAC 320 kbps is available for MP4 compatibility. **Lossless RGB** preserves canvas pixels, while high quality H.264 compresses video. Phone exports send binary frames and prefer hardware H.264; advanced settings can force software. Keep the screen open for phone exports and transfers. Results save in **Download/NoFocus** with a Share button.

Visual music requires Android 10+, imports up to 768 MB / 20 minutes, and space for the original plus decoded playback/analysis. Removing a library song leaves original selected files and published videos intact. Font rasterization can differ between operating systems; Signal bloom uses Android's serif fallback when Georgia is unavailable. See [visualizer/README.md](visualizer/README.md) for source provenance, rebuild and pixel/audio verification. English-only intro lyrics and seek dragging are fixed for existing saved songs; JSON does not need repasting.

## PC-assisted video export

Run [desktop_export/start.cmd](desktop_export/start.cmd) on the PC (Node.js 22+ and Edge), scan its QR code with the phone camera, and tap **Pair PC**. Or paste its code into **Visuals / Export video / Pair PC**. The companion draws and encodes on the PC; the phone uploads original media, analysis, timings and settings once, then retrieves the finished video. Preserved audio stays untouched.

**Automatic** uses the paired PC when available and the phone otherwise. **This phone** and **Paired PC** explicitly select the destination. Fully uploaded PC jobs keep working when you leave NoFocus. Return to Export, check progress, and **Save to phone**. Interrupted transfers can be retried; results remain on the PC for 24 hours. An optional Windows sign-in launcher and detailed setup/testing instructions are in [desktop_export/README.md](desktop_export/README.md).

## Capture a Suno lyrics video

Open the capture page from Visuals or Add music. Choose video size/bitrate independently of audio quality. Set a start countdown (0, 3, 5, 10 or 30 seconds) and a stop duration such as `3:30` (up to 10 minutes). A blank stop duration uses the 10-minute limit. Enable the small floating control, switch to Suno, press **Start**, and play the song during the countdown. The draggable control shows elapsed/remaining time and provides Stop/Cancel. The native timer stops recording even if the capture page is closed.

Turn floating control off to use the same countdown and stop timer from the capture page. Keep the phone in one orientation and pause other media apps. Choose Suno alone in Android's sharing prompt when available; whole-screen capture can include the overlay.

- **32-bit float PCM (default):** 48 kHz stereo, without another integer quantization or lossy encoder.
- **16-bit FLAC:** smaller lossless audio at 48 kHz stereo, for devices that cannot capture float PCM.

Each recording saves a lossless **MKV master** and a matching **MP4 copy** with AAC 320 kbps in **Download/NoFocus**. Keep the MKV for visualization and send the MP4 to Gemini with the app's timing prompt. Both share the encoded video and alignment. Use **Open latest master in visualizer**, paste Gemini's JSON, preview, then export with **Preserve original audio**. The same MKV and JSON can be dropped into the local Visual-Music-Lyrics desktop app.

Capture requires 1 GB free space and stops after 10 minutes or when space runs low. It captures internal playback, without microphone or audio-focus requests, gain boost or normalization. Android's mixer, sample-rate conversion, device volume and Suno's capture policy still apply: lossless means preserving captured PCM, not recovering a studio original. Silence is reported when no capturable audio is detected. Device timestamps align the streams; unavailable timestamps trigger a sync-review warning. Make a short test before a full song.

## Download a song

Both apps download directly from YouTube, independently. Android does not need the PC online. Downloading does not stop music playback or PC audio streaming.

* **Android 7 or newer:** **My music → Setup → Add music → Download song**. Paste a single YouTube or YouTube Music video link, choose a format, and tap **Download**. Then use **Save** to keep the file in a folder, or **Share** to send it to another app. For NoFocus playback, save into your chosen music folder and use **Rescan folder**. Only the latest export is kept in the app; save it before downloading another song.
* **Windows:** **Download song**. Paste the link, choose a format and a folder, then **Download**. **Show file** opens its location; **Play** opens your default player. Existing files are preserved by adding a numbered suffix.

| Format | Use |
| --- | --- |
| MP3 (default) | Broad compatibility, including most web apps |
| M4A (AAC) | Smaller files; check the receiving app's supported formats |
| WAV (PCM) | Editing and apps that request WAV; much larger files |

These are real audio conversions. WAV does not improve the original YouTube audio quality. Upload the saved file using the other web app's normal file picker; nothing is uploaded automatically.

Use single public videos under two hours, with source downloads capped at 500 MB. Playlists, live streams and sign-in-only videos are unsupported. YouTube changes can require an updated app. Download only material you are allowed to save.

The Android APK bundles Python, yt-dlp, QuickJS and FFmpeg for independent downloads, so the universal APK is about 190 MiB. Android 6 retains folder playback and PC audio; song downloads require Android 7. Windows installs checksum-verified portable helpers into `%LOCALAPPDATA%/NoFocus Speaker/download-tools` on first use (internet and several hundred MB free space required). Neither device needs Python installed separately. Cancellation stops the converter and cleans incomplete job files. Android downloads and saving continue when you leave the screen; reopen it or use the notification to cancel.

**PC to phone:** the existing **PC audio** mode streams whatever is playing on the PC, including a downloaded song. It does not copy the PC's music files to the phone. To keep a song on the phone, download it there and use **Save**.

## Compact phone interface

The two audio modes have separate screens, with everyday controls visible without scrolling. Setup and extraction have their own short pages. Long track names are shortened to fit; tap the track area for the full name and any error details. Landscape uses two columns and shorter labels. Changing tabs only changes the visible controls; pressing Play or Start switches the audio source.

Buttons and volume sliders have touch targets of at least 48 dp. Text follows Android's font setting, and layouts account for the status bar, navigation bar, and camera cutout. The device layout checks cover 320 × 480 dp, portrait and landscape, at 100%, 150%, and 200% font scale, plus the connected phone's normal display.

## Build and test

Android:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Device UI regression checks (with the phone unlocked):

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
.\scripts\check-phone-ui.ps1
```

The script temporarily changes display size, density, rotation, and font scale, then restores their previous values in `finally`. It checks screen bounds, clipping, 48 dp controls, empty-folder and long-title states, and PC connection labels. It does not play audio or change media files, pairing codes, or volume. Android's own folder picker and full-detail dialogs may scroll when their contents require it.

Native Windows sender:

```powershell
dotnet run --project desktop\windows\NoFocus.Desktop\NoFocus.Desktop.csproj -c Release -- --self-test
.\desktop\windows\build-release.ps1
```

The one-file executable is written to `artifacts/windows-x64/NoFocus Speaker.exe`. Tagged GitHub builds and manual workflow runs are defined in `.github/workflows/release.yml`.

Download checks (use an explicit Android emulator/phone serial):

```powershell
adb -s emulator-5580 shell am instrument -w -e downloads true dev.nofocus.folderplayer.test/dev.nofocus.folderplayer.CompactUiTest
# Optional live YouTube check, in addition to offline format/cancellation checks:
adb -s emulator-5580 shell am instrument -w -e downloads true -e url "https://www.youtube.com/watch?v=jNQXAC9IVRw" dev.nofocus.folderplayer.test/dev.nofocus.folderplayer.CompactUiTest
dotnet run --project desktop/windows/NoFocus.Desktop/NoFocus.Desktop.csproj -c Release -- --download-youtube --url "https://www.youtube.com/watch?v=jNQXAC9IVRw" --format mp3 --output artifacts/download-test
```

The runtime test generates a one-second tone, converts all three formats with the bundled tools, checks the containers/duration, cancels an active process, and rejects content-provider path traversal. It uses an isolated cache directory and cleans it afterward. The optional live check depends on YouTube availability. Layout checks also cover download progress, long errors and completed results, including 200% text.

Tool versions and SHA-256 checksums live in `download-tools.json`. Updating the Android runtime version or yt-dlp hash automatically selects a new private extraction directory. Review upstream license/source changes at the same time; notices are in [third-party/NOTICE.md](third-party/NOTICE.md). Native AARs supply only CLI payloads; the GPL Android wrapper is not linked. First builds require Maven Central and GitHub access. These apps do not use Visual-Music-Lyrics's server, authentication, cookies or secrets.

The Python sender in `desktop_sender/` remains a developer and macOS/Linux fallback. Windows users should use the native one-click app.

## Release safety

The workflow labels the Android artifact as a debug APK. Before calling an Android build production-ready, sign a non-debug release with the publisher's protected Android signing key. For frictionless global Windows distribution, Authenticode-sign the EXE using the publisher's code-signing certificate; otherwise Microsoft SmartScreen may warn users who download a new unsigned binary.

## Protocol maintenance

The Android, native Windows, and Python protocol implementations must change together. Any wire-format change must increment the protocol version and update self-tests/fixtures. Live audio does not request retransmission: time-diverse redundancy and one-frame concealment are preferable to adding round-trip latency.
