#!/usr/bin/env bash
set -euo pipefail

ABI="${1:?Usage: build-ffmpeg-android.sh <arm64-v8a|x86_64>}"
API="${ANDROID_API:-24}"
FFMPEG_TAG="${FFMPEG_TAG:-n7.1.1}"
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}"
REPO_ROOT="$(pwd)"
ROOT="${RUNNER_TEMP:-/tmp}/gaplin-ffmpeg-${ABI}"
PREFIX="${ROOT}/install"
SRC="${ROOT}/src"
OUT="${REPO_ROOT}/yt-dlp-runtime/src/main/jniLibs/${ABI}/libffmpeg.so"
HOST="linux-x86_64"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST}"
SYSROOT="${TOOLCHAIN}/sysroot"

case "$ABI" in
  arm64-v8a)
    TARGET="aarch64-linux-android"
    ARCH="aarch64"
    CPU="armv8-a"
    ;;
  x86_64)
    TARGET="x86_64-linux-android"
    ARCH="x86_64"
    CPU="x86-64"
    ;;
  *)
    echo "Unsupported ABI: $ABI" >&2
    exit 2
    ;;
esac

CC="${TOOLCHAIN}/bin/${TARGET}${API}-clang"
CXX="${TOOLCHAIN}/bin/${TARGET}${API}-clang++"
AR="${TOOLCHAIN}/bin/llvm-ar"
RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
STRIP="${TOOLCHAIN}/bin/llvm-strip"
READELF="${TOOLCHAIN}/bin/llvm-readelf"
CFLAGS="-O2 -fPIC -DANDROID"
LDFLAGS="-Wl,-z,max-page-size=16384"

mkdir -p "$PREFIX" "$SRC"
export PATH="${PREFIX}/bin:${PATH}"
export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="${PREFIX}/lib/pkgconfig"

echo "Building LGPL/GPL FFmpeg toolchain for $ABI (Android API $API)"
echo "Compiler: $CC"
test -x "$CC"

# LAME is built from source and linked statically into the FFmpeg executable.
cd "$SRC"
if [ ! -d lame-3.100 ]; then
  curl -fL --retry 3 "https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz" -o lame-3.100.tar.gz
  tar -xzf lame-3.100.tar.gz
fi
cd lame-3.100
CC="$CC" CXX="$CXX" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" ./configure \
  --prefix="$PREFIX" \
  --host="$TARGET" \
  --enable-static \
  --disable-shared \
  --disable-frontend \
  --with-pic
make -j2
make install

# Build a compact FFmpeg CLI with stream muxing and MP3/AAC audio conversion.
cd "$SRC"
if [ ! -d FFmpeg ]; then
  git clone --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg.git FFmpeg
fi
cd FFmpeg
PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" ./configure \
  --prefix="$PREFIX" \
  --target-os=android \
  --arch="$ARCH" \
  --cpu="$CPU" \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --sysroot="$SYSROOT" \
  --enable-cross-compile \
  --enable-pic \
  --enable-zlib \
  --enable-static \
  --disable-shared \
  --enable-gpl \
  --enable-libmp3lame \
  --enable-ffmpeg \
  --enable-ffprobe \
  --disable-ffplay \
  --disable-doc \
  --disable-debug \
  --disable-network \
  --disable-autodetect \
  --disable-avdevice \
  --disable-postproc \
  --disable-everything \
  --enable-ffmpeg \
  --enable-ffprobe \
  --enable-protocol=file,pipe \
  --enable-demuxer=aac,flac,image2,webp_pipe,matroska,mov,mp3,ogg,wav \
  --enable-muxer=image2,ipod,matroska,mp3,mov,mp4,ogg,opus,wav,webm \
  --enable-decoder=aac,flac,mjpeg,mp3,opus,png,vorbis,pcm_s16le,webp \
  --enable-encoder=aac,libmp3lame,mjpeg,png \
  --enable-filter=aresample,scale \
  --enable-parser=aac,flac,mpegaudio,opus,vorbis \
  --enable-libmp3lame \
  --pkg-config-flags="--static" \
  --extra-cflags="-I$PREFIX/include $CFLAGS" \
  --extra-ldflags="-L$PREFIX/lib $LDFLAGS" \
  --extra-libs="-lm -ldl"

# Check feature selection in FFmpeg's generated build configuration.
grep -Rqs '^CONFIG_FFPROBE=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_LIBMP3LAME_ENCODER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_MOV_MUXER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_MP4_MUXER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_IMAGE2_DEMUXER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_IMAGE_WEBP_PIPE_DEMUXER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_IMAGE2_MUXER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_MJPEG_DECODER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_PNG_DECODER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_WEBP_DECODER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_MJPEG_ENCODER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_PNG_ENCODER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_ZLIB=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_SCALE_FILTER=yes' ffbuild/config.mak
grep -Rqs '^CONFIG_ARESAMPLE_FILTER=yes' ffbuild/config.mak

make -j2
make install
install -D -m 0755 "$PREFIX/bin/ffmpeg" "$OUT"
PROBE_OUT="${REPO_ROOT}/yt-dlp-runtime/src/main/jniLibs/${ABI}/libffprobe.so"
echo "Packaging FFmpeg to $OUT"
install -m 0755 "$PREFIX/bin/ffprobe" "$PROBE_OUT"
echo "Stripping FFmpeg and FFprobe"
"$STRIP" "$OUT" "$PROBE_OUT"

# Cross-compiled Android ELF files cannot run on the Linux x86_64 runner.
# Verify ABI and executable files statically here; Android instrumentation runs them later.
echo "Verifying executable files exist"
test -s "$OUT"
test -s "$PROBE_OUT"
echo "Reading ELF headers with $READELF"
FFMPEG_HEADER="${ROOT}/ffmpeg-elf-header.txt"
FFPROBE_HEADER="${ROOT}/ffprobe-elf-header.txt"
"$READELF" -h "$OUT" > "$FFMPEG_HEADER"
"$READELF" -h "$PROBE_OUT" > "$FFPROBE_HEADER"
echo "Verifying ELF class"
grep -q "Class:.*ELF64" "$FFMPEG_HEADER"
grep -q "Class:.*ELF64" "$FFPROBE_HEADER"
echo "Verifying native machine architecture for $ABI"
case "$ABI" in
  arm64-v8a)
    grep -q "Machine:.*AArch64" "$FFMPEG_HEADER"
    grep -q "Machine:.*AArch64" "$FFPROBE_HEADER"
    ;;
  x86_64)
    grep -q "Machine:.*Advanced Micro Devices X86-64" "$FFMPEG_HEADER"
    grep -q "Machine:.*Advanced Micro Devices X86-64" "$FFPROBE_HEADER"
    ;;
esac
echo "FFmpeg + FFprobe + libmp3lame verified for $ABI: $OUT ($(stat -c%s "$OUT") bytes), $(stat -c%s "$PROBE_OUT") bytes"
