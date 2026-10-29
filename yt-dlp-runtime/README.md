# Gaplin yt-dlp runtime

This module vendors the MIT-licensed Java bridge API from `ffmpegkit-maintained/yt-dlp-android` and builds its own embedded Python runtime. The Python engine is pinned to `yt-dlp==2026.10.26` and `certifi==2026.10.23`; update these pins only through a reviewed engine update and verified build.

The option adapter is maintained locally and rejects unknown CLI-style flags rather than silently ignoring them. It explicitly maps retry/concurrency options and parses extractor arguments into yt-dlp's Python API structure.

Supported ABIs: `arm64-v8a`, `x86_64`. Minimum Android API: 24.
