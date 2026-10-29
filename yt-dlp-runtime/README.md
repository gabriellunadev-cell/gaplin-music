# Gaplin yt-dlp runtime

This module vendors the MIT-licensed Java bridge API from `ffmpegkit-maintained/yt-dlp-android` and builds its own embedded Python runtime.

## Pinned engine components

- `yt-dlp==2026.10.26`
- `yt-dlp-ejs==0.8.0`
- `certifi==2026.10.23`
- QuickJS-NG `v0.17.0`, compiled with the Android NDK for `arm64-v8a` and `x86_64`

The EJS companion package provides the official JavaScript challenge solver scripts. QuickJS-NG is the actual Android executable used by yt-dlp. Both are required for full YouTube extraction support. The app verifies the QuickJS executable, runs a JavaScript self-test, verifies the EJS package version, and only then enables the downloader.

## Build pipeline

The GitHub Actions workflow pins QuickJS-NG to `v0.17.0`, compiles its `qjs` executable for both supported Android ABIs with NDK 27.2, and packages the binaries under `src/main/assets/quickjs/<abi>/qjs`. The app extracts the matching executable into its private no-backup storage and uses its private cache for temporary EJS JavaScript files.

## Downloader behavior

The Python option adapter is maintained locally and rejects unknown CLI-style flags rather than silently ignoring them. It explicitly maps retry/concurrency options, parses extractor arguments into yt-dlp's Python API structure, and configures the JavaScript runtime via the documented Python API mapping.

YouTube cookie import is optional. Only YouTube-domain Netscape cookies are retained in the app-private no-backup directory; cookie values are never logged. This does not bypass DRM, paid access, age checks, regional restrictions, or other controls.

Audio downloads select audio-only formats. Video downloads select a single progressive stream with both audio and video because this runtime does not bundle FFmpeg for merging separate DASH streams. Preflight inspection detects unavailable content, DRM, and missing compatible formats before a task enters transfer.

Supported ABIs: `arm64-v8a`, `x86_64`. Minimum Android API: 24.

Third-party licenses are retained in `THIRD-PARTY-NOTICES.txt`.
