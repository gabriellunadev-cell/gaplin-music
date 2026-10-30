# Gaplin yt-dlp runtime

This module vendors the MIT-licensed Java bridge API from `ffmpegkit-maintained/yt-dlp-android` and builds its own embedded Python runtime and Android-native command-line tools.

## Pinned engine components

- `yt-dlp==2026.10.26`
- `yt-dlp-ejs==0.8.0`
- `certifi==2026.10.23`
- QuickJS-NG `v0.17.0`, compiled for Android `arm64-v8a` and `x86_64`
- FFmpeg `n7.1.1` with FFprobe, compiled for Android API 24 and the same ABIs
- LAME `3.100` / `libmp3lame` for MP3 extraction

The EJS package supplies the official JavaScript challenge solver scripts, while QuickJS-NG executes them on-device. FFmpeg and FFprobe are packaged beside QuickJS in `jniLibs`, extracted by Android's package manager into `nativeLibraryDir`, and executed there to comply with modern Android executable-storage restrictions.

## Downloader capabilities

- Video quality presets: up to 2160p (4K), 1080p (Full HD), 720p (HD), and 480p (SD).
- Separate video and audio streams are merged with FFmpeg. The selected resolution is an upper bound; the highest available resolution below it is used.
- Video output prefers MP4 and falls back to MKV when the source codecs are not MP4-compatible.
- Audio output: MP3 V0 or M4A/AAC, converted by FFmpeg.
- The metadata preflight reports actual video heights and audio/video format availability before transfer.
- YouTube cookie import is optional. Only YouTube-domain Netscape cookies are retained in the app-private no-backup directory; cookie values are never logged.

The app does not remove DRM, bypass paid access, defeat age or regional restrictions, or fabricate platform tokens. Download only content you own or are authorized to save.

## Build pipeline

`.github/workflows/android-build.yml` installs Android NDK 27.2. The QuickJS builder compiles `qjs`; `scripts/build-ffmpeg-android.sh` compiles LAME and FFmpeg/FFprobe from source for each supported ABI. The executables are stored under `yt-dlp-runtime/src/main/jniLibs/<abi>/` as `libqjs.so`, `libffmpeg.so`, and `libffprobe.so`.

The app verifies that QuickJS runs JavaScript, FFmpeg starts and exposes the MP3 encoder and MP4 muxer, and FFprobe starts before it enables the downloader.

## Android and licensing

- Supported ABIs: `arm64-v8a`, `x86_64`.
- Minimum Android API: 24.
- FFmpeg is built with GPL components; the resulting FFmpeg executable is distributed under GPLv3.
- LAME is statically linked and licensed under LGPLv2.
- Full notices and license texts are in `THIRD-PARTY-NOTICES.txt` and `licenses/`.

Source references: [FFmpeg n7.1.1](https://github.com/FFmpeg/FFmpeg/tree/n7.1.1), [LAME 3.100](https://sourceforge.net/projects/lame/files/lame/3.100/), [QuickJS-NG v0.17.0](https://github.com/quickjs-ng/quickjs/tree/v0.17.0).
