# Download tools

NoFocus launches the following command-line programs locally. Versions, download URLs and SHA-256 hashes are pinned in `download-tools.json`. No credentials or code from Visual-Music-Lyrics are included.

* **yt-dlp 2026.08.19** — [source and release](https://github.com/yt-dlp/yt-dlp/tree/2026.08.19), Unlicense (see `yt-dlp.txt`). The Windows executable also contains third-party dependencies covered by their upstream licenses; see [yt-dlp licensing](https://github.com/yt-dlp/yt-dlp#license), including GPLv3+. The bundled EJS components are from [yt-dlp/ejs](https://github.com/yt-dlp/ejs).
* **Android native runtimes 0.18.1** — unmodified `jni/` payloads from [JunkFood02/youtubedl-android](https://github.com/JunkFood02/youtubedl-android) Maven Central `library` and `ffmpeg` AARs. The upstream project is GPLv3 (`GPL-3.0.txt`). NoFocus does not include its wrapper classes or link its Android API. Payloads include Python, QuickJS, FFmpeg and their runtime dependencies. The native library files are executables and compressed distributions; NoFocus's Java process launcher is independently implemented.
* **Python** — Python Software Foundation license and included notices (`Python.txt`); [source](https://github.com/python/cpython). Android packaging/build instructions: [BUILD_PYTHON.md](https://github.com/JunkFood02/youtubedl-android/blob/master/BUILD_PYTHON.md).
* **QuickJS** — MIT (`QuickJS.txt`); [source](https://github.com/quickjs-ng/quickjs).
* **FFmpeg** — GPL-enabled builds, GPLv3 or later (`GPL-3.0.txt`). [FFmpeg source](https://git.ffmpeg.org/ffmpeg.git). Android packaging instructions: [BUILD_FFMPEG.md](https://github.com/JunkFood02/youtubedl-android/blob/master/BUILD_FFMPEG.md), with dependency sources and patches in [termux-packages](https://github.com/termux/termux-packages). Windows uses the unmodified [Gyan FFmpeg 9.0.2 essentials build](https://github.com/GyanD/codexffmpeg/releases/tag/9.0.2). [Build information and source](https://www.gyan.dev/ffmpeg/builds/#about-these-builds); the installer preserves license files from the distribution.
* **Deno 2.9.7** (Windows) — MIT (`Deno.txt`); [source](https://github.com/denoland/deno/tree/v2.9.7).
* **Zip4j 2.11.6** (Android) — Apache 2.0 (`Zip4j.txt`), Copyright Srikanth Reddy Lingala; [source](https://github.com/srikanth-lingala/zip4j/tree/v2.11.6).

These notices and license texts are included as Android assets and embedded Windows resources. Windows also writes them beside its downloaded helpers in `%LOCALAPPDATA%/NoFocus Speaker/download-tools/licenses`.

The upstream licenses continue to apply to the tools and their dependencies. When redistributing binaries, retain the notices and provide the corresponding source, patches and build scripts for the exact distributions, as required by those licenses. Changing a pinned binary also requires reviewing its upstream license and source distribution.
