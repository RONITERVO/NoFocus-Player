# NoFocus Player

NoFocus Player has two deliberately audio-focus-free playback modes:

- recursively play audio from a phone folder; and
- use the phone as an encrypted, low-latency Wi-Fi speaker for a computer.

Because neither mode calls `AudioManager.requestAudioFocus()`, Android can mix it with YouTube or another media app. Phone calls, Bluetooth/Android Auto policies, manufacturer audio enhancements, or other route-specific policies can still override mixing.

## Wi-Fi speaker quick start

The computer and phone must be on the same LAN. A 5 GHz or 6 GHz Wi-Fi connection with a strong signal is recommended.

1. On the phone, open **NoFocus Player** and tap **Start Wi-Fi receiver**.
2. Note the phone address and pairing code shown in the Wi-Fi Speaker card.
3. On the computer, install Python 3.9 or newer and run:

   ```powershell
   cd desktop_sender
   python -m venv .venv
   .venv\Scripts\python -m pip install -r requirements.txt
   .venv\Scripts\python nofocus_sender.py --host 192.168.1.50 --code ABCD-EFGH-JKLM-NPQR
   ```

   On macOS/Linux, activate the environment with `source .venv/bin/activate`, then use `python nofocus_sender.py ...`.

4. Play audio on the computer. The sender captures the default output loopback, not the microphone.

Use `python nofocus_sender.py --list-devices` and `--device "name substring"` when the wrong output is selected. Windows WASAPI loopback and Linux PulseAudio/PipeWire-Pulse monitors work directly. macOS may require a system-audio loopback device such as BlackHole, depending on the CoreAudio configuration.

### Latency and quality

The stream is lossless 48 kHz, stereo, signed 16-bit PCM. Each encrypted UDP packet contains 5 ms of audio and remains below the usual LAN MTU. The sender repeats the preceding frame on the next tick, allowing recovery from one lost Wi-Fi datagram without a round trip; total traffic remains below 3.2 Mbit/s. The app offers 10, 20, and 40 ms network jitter targets. It also requests Android's low-latency output path and starts with a 10 ms `AudioTrack` buffer, expanding that buffer only when Android reports underruns.

Literal zero latency is physically impossible. Total latency also includes the computer capture backend, Wi-Fi scheduling, Android's mixer, and the phone DAC. The **Ultra-low · 10 ms** profile minimizes buffering but needs an excellent LAN; **Low · 20 ms** is the recommended starting point.

On the tested Honor 400 Pro running Android 16, Ultra held a 5–15 ms network queue with zero unrecovered gaps while Android reported 23–31 ms for its fast output track. Other phones, routes, and audio enhancements will differ. Describe the feature as low latency—not zero latency—unless end-to-end acoustic measurements on the target device prove otherwise.

### Security model

- A random 80-bit pairing secret is generated on the phone and can be rotated at any time.
- Hello packets are authenticated with HMAC-SHA-256.
- Every PCM packet is encrypted and authenticated with AES-256-GCM using a random 64-bit session ID and monotonic packet sequence.
- The receiver locks an active session to one source address, rejects old/duplicate sequence numbers, bounds all packet and queue sizes, and times out dead sessions.
- The pairing secret is excluded from Android backup. Treat it like a local-network password and rotate it after sharing or using an untrusted LAN.

Audio is never uploaded to a cloud service. The only network traffic is direct UDP from the computer to the phone on port `39821`.

## Folder player

1. Tap **Choose music folder** and select `Music` or one of its subfolders.
2. Optionally use **Extract video audio**. The extractor copies a supported source audio track into an audio-only container without lossy re-encoding.
3. Tap **Start / rescan folder**, then open YouTube or another app.

Android 11 and newer may prevent selecting the storage root or `Download` directly. The scanner supports common formats including MP3, M4A, AAC, FLAC, Ogg/Opus, WebM, WAV, 3GP, AMR, and MIDI.

Starting either NoFocus source stops the other source. This prevents accidental double playback while still allowing unrelated apps to play alongside it.

## Build and validation

The app is Java-only and has no runtime Android dependencies. It targets Android 16/API 36 and supports Android 6/API 23 and newer.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python -m unittest discover -s desktop_sender -p "test_*.py" -v
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Android 16 local-network restrictions use the Nearby Devices permission during the transition. The manifest also declares Android 17's dedicated local-network permission so the network boundary is explicit for the next target-SDK upgrade.

For a sender diagnostic that opens the real system loopback and exits automatically:

```powershell
python desktop_sender\nofocus_sender.py --host 127.0.0.1 --code ABCD-EFGH-JKLM-NPQR --duration 1
```

## Protocol maintenance

The wire version and fixed-size validation live in `WifiAudioProtocol.java` and `desktop_sender/nofocus_sender.py`. Any format change must increment `VERSION` in both implementations and add cross-language-compatible fixtures. Live audio intentionally does not retransmit: a late packet increases latency and is less useful than the receiver's one-frame silence concealment.
