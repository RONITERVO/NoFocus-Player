# NoFocus Player

NoFocus Player lets an Android phone play either a local music folder or encrypted PC audio without requesting audio focus. YouTube, games, and other apps can therefore keep playing at the same time.

## Easy Wi-Fi speaker setup

Requirements: Windows 10/11, Android 6 or newer, and both devices on the same home network. A strong 5 GHz or 6 GHz connection is recommended.

1. Install and open **NoFocus Player** on the phone.
2. Tap **Start Wi-Fi receiver**.
3. Download and open **NoFocus-Speaker-Windows-x64.exe** on the PC. It is self-contained; Python and .NET do not need to be installed.
4. The PC normally finds the phone automatically. Enter the pairing code shown on the phone once, then click **Start listening on phone**.

The PC remembers its setup and protects the pairing code with the current Windows account. On later launches, streaming is one click. If automatic discovery is blocked by a router or VPN, enter the phone address shown in the app. **Copy PC setup** and **Paste phone setup** provide another easy setup route when clipboard sync is enabled.

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

The phone offers three profiles:

- **Ultra-low - 10 ms:** lowest network buffering; best Wi-Fi required.
- **Low - 20 ms:** recommended default.
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

1. Tap **Choose music folder** and select `Music` or one of its subfolders.
2. Optionally use **Extract video audio**. The extractor copies supported source audio into an audio-only container without lossy re-encoding.
3. Tap **Start / rescan folder**, then open YouTube or another app.

Android 11 and newer may prevent selecting the storage root or `Download` directly. Common MP3, M4A, AAC, FLAC, Ogg/Opus, WebM, WAV, 3GP, AMR, and MIDI files are supported.

Starting either NoFocus source stops the other NoFocus source while unrelated apps keep playing.

## Build and test

Android:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Native Windows sender:

```powershell
dotnet run --project desktop\windows\NoFocus.Desktop\NoFocus.Desktop.csproj -c Release -- --self-test
.\desktop\windows\build-release.ps1
```

The one-file executable is written to `artifacts/windows-x64/NoFocus Speaker.exe`. Tagged GitHub builds and manual workflow runs are defined in `.github/workflows/release.yml`.

The Python sender in `desktop_sender/` remains a developer and macOS/Linux fallback. Windows users should use the native one-click app.

## Release safety

The workflow labels the Android artifact as a debug APK. Before calling an Android build production-ready, sign a non-debug release with the publisher's protected Android signing key. For frictionless global Windows distribution, Authenticode-sign the EXE using the publisher's code-signing certificate; otherwise Microsoft SmartScreen may warn users who download a new unsigned binary.

## Protocol maintenance

The Android, native Windows, and Python protocol implementations must change together. Any wire-format change must increment the protocol version and update self-tests/fixtures. Live audio does not request retransmission: time-diverse redundancy and one-frame concealment are preferable to adding round-trip latency.
