# Phone audio to Windows

The native Windows app is the maintained streamer in both directions. The standalone Python sender was retired at the user's request; recover it from commit `1603fec` if needed. This does not remove the Android downloader's bundled Python runtime.

## Protocol and audio contract

Phone → PC uses the existing NFP2 version 2 wire format on UDP 39822. PC → phone remains on UDP 39821. Hello is a 64-byte HMAC-authenticated message; acknowledgement is a 32-byte authenticated reply. Discovery is eight bytes in either direction, with the responding receiver port in its final two bytes. Discovery never includes a code and is not proof of identity; the encrypted handshake verifies the shared audio secret. [Unified pairing](windows-companion.md) supplies distinct secrets for both directions; manual setup still accepts independent codes.

Each 1,008-byte audio datagram carries 240 frames of little-endian, signed 16-bit stereo PCM at 48 kHz, protected by AES-256-GCM. The random session ID and monotonic sequence form the nonce. The sender repeats the preceding encrypted packet to recover isolated loss. It rejects sequence overflow and caps a sharing session at 12 hours.

The PC locks an active session to one authenticated endpoint, ignores malformed/unauthenticated packets, rejects duplicates and played sequences, retires idle sessions after three seconds, and keeps a bounded 256-session replay history until listening stops. Fresh manual starts create fresh sessions. The phone waits for authenticated pairing before capturing audio and stops after five seconds without an authenticated acknowledgement.

The pull-driven Windows buffer reorders packets, inserts silence for unrecovered gaps, and discards stale audio rather than accumulating latency. Buffer choice is 10/20/40 ms, in addition to Android capture, Windows output and network latency. The implementation performs no lossy encoding. PCM transport identity does not promise lossless source extraction or zero acoustic latency.

Only Android playback capture is used. No microphone source, audio focus, screen frame, cloud request or recording file is involved. The foreground notification and Android's sharing indicator remain visible. Starting reverse mode stops the phone's PC receiver; the Windows UI stops its sender before opening the reverse receiver. Android capture revocation, timeout, explicit stop and activity destruction must not leave a capture running invisibly.

## Verification

`dotnet run --project desktop/windows/NoFocus.Desktop/NoFocus.Desktop.csproj -c Release -- --self-test` verifies original sender protocol/downloader checks plus golden Java/Windows/Python-compatible packet hashes, exact PCM decoding, tampering, packet reordering, loss concealment, duplicate rejection, bounded buffering, real loopback UDP pairing, competing endpoint rejection, malformed traffic, timeout/replay rejection and listener restart. It does not require a sound device or emit audio.

`.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` checks Android code, protocol fixtures, sequence exhaustion, discovery direction and address validation, and builds both APKs.

On 2026-10-10, the Honor 400 Pro passed the real Android capture test with a separate synthetic-tone app, authenticated PCM, continued streaming after leaving setup, and explicit stop/notification cleanup. An API 36 emulator also passed automatic shutdown when PC acknowledgements stopped. The real Honor → Windows Wi-Fi test used the production sender/receiver and WASAPI default output: 2,401 unique packets, 2,326 non-silent packets, 2 concealed gaps, and no stale-buffer trimming across about 12 seconds of capture (the receiver test also includes startup/shutdown). This is a connectivity/output smoke test, not an acoustic latency measurement or proof of zero packet loss.

The original compact UI regression covered 17 screens. The integrated Windows companion update adds compact phone listening, pairing and unified settings coverage; see [its validation record](windows-companion.md#validation). Everyday streaming controls have no scrolling; manual input and longer help are separate dialogs. Windows builds and protocol/downloader self-tests passed. Android unit tests and lint passed (zero lint errors).

Suno's current playback-capture policy, phone-volume behavior and lock-screen behavior still need checking with real listening. Existing PC → phone benchmarks in the main README are not measurements of this new reverse direction.

Device test command (approve Android's normal sharing dialog):
```powershell
adb -s <serial> shell am instrument -w -e phoneAudio true dev.nofocus.folderplayer.test/dev.nofocus.folderplayer.CompactUiTest
# Add -e timeout true to test a lost receiver instead of an explicit stop.
```
The test restores the prior pairing preferences and stops its synthetic tone. A developer Windows output test is available through `--receive-headless --code-file <path> --duration <seconds>`; the code file contains a temporary 16-character code. The Android instrumentation can target that listener with `-e pcHost <address> -e pcCode <same-code>`. Use test-only codes, and remove temporary code files afterward.
