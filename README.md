# NoFocus Folder Player

A minimal native Android music player intended for one specific job: play local audio files from a user-selected folder while the YouTube app also plays audio.

The important implementation choice is in `PlayerService.java`: it uses `MediaPlayer`, starts a `mediaPlayback` foreground service, and deliberately never calls `AudioManager.requestAudioFocus()`. Normal music apps request audio focus and then pause when YouTube requests focus. This app opts out of that behavior.

## Build / deploy

1. Open this folder in Android Studio.
2. Let Android Studio sync Gradle.
3. Run the `app` configuration on your phone, or build a debug APK from **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

This project is Java-only and has no third-party dependencies.

The zip does not include a Gradle wrapper JAR. Android Studio will use/download a compatible Gradle distribution. If you prefer CLI builds, create a wrapper once with your installed Gradle:

```bash
gradle wrapper --gradle-version 8.11.1
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Launch **NoFocus Folder Player**.
2. Grant notification permission if Android asks.
3. Tap **Choose music folder** and select a real subfolder such as `Music` or `Music/MyMix`.
4. To turn music videos into audio-only files, tap **Extract video audio**. The app creates `NoFocus extracted audio` inside the selected folder and rescans when the batch finishes.
5. Tap **Start / rescan folder**.
6. Open the YouTube app and start a video. Both audio streams should be mixed.
7. Control this app from its notification or from the app UI.

On Android 11 and newer, Android's folder picker may not allow choosing the storage root or the `Download` directory directly. Choose a music subfolder instead.

## What it supports

The scanner recursively finds common local audio files: `.mp3`, `.m4a`, `.aac`, `.flac`, `.ogg`, `.oga`, `.opus`, `.webm`, `.wav`, `.3gp`, `.amr`, `.mid`, and `.midi`, plus files reported by Android as `audio/*`.

The extractor recursively finds common video files such as `.mp4`, `.m4v`, `.mkv`, `.webm`, `.mov`, `.3gp`, and `.3gpp`. It does not convert to MP3 because MP3 is lossy. When Android supports the source audio codec, it copies the original audio track into an audio-only file (`.m4a`, `.webm`, or `.3gp`) without re-encoding, so there is no extra quality loss.

## Limits

This works by avoiding audio focus. Android's own documentation says multiple apps can technically play to the same output stream and be mixed, but the normal design guideline is that media apps should request audio focus and pause/duck when they lose it. This app intentionally does not follow that guideline because your goal is parallel playback.

It cannot override hardware, OEM, or route-specific policies. Some phone vendors, Bluetooth devices, Android Auto, phone calls, or special sound-enhancement modes may still mute, duck, or route audio in ways an app cannot control.
