#!/usr/bin/env bash
set -euo pipefail

ABI="${1:?Usage: build-ffmpeg-android.sh <arm64-v8a|x86_64>}"
API="${ANDROID_API:-24}"
FFMPEG_TAG="${FFMPEG_TAG:-n7.1.1}"
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}"
ROOT="${RUNNER_TEMP:-/tmp}/gaplin-ffmpeg-${ABI}"
PREFIX="${ROOT}/install"
SRC="${ROOT}/src"
OUT="yt-dlp-runtime/src/main/jniLibs/${ABI}/libffmpeg.so"
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
  --enable-static \
  --disable-shared \
  --enable-gpl \
  --enable-libmp3lame \
  --enable-ffmpeg \
  --disable-ffplay \
  --disable-ffprobe \
  --disable-doc \
  --disable-debug \
  --disable-network \
  --disable-autodetect \
  --disable-avdevice \
  --disable-postproc \
  --disable-everything \
  --enable-ffmpeg \
  --enable-protocol=file,pipe \
  --enable-demuxer=aac,flac,matroska,mov,mp3,ogg,wav,webm \
  --enable-muxer=ipod,matroska,mp3,mp4,ogg,opus,wav,webm \
  --enable-decoder=aac,flac,mp3,opus,vorbis,pcm_s16le \
  --enable-encoder=aac,libmp3lame \
  --enable-parser=aac,flac,mpegaudio,opus,vorbis \
  --enable-libmp3lame \
  --pkg-config-flags="--static" \
  --extra-cflags="-I$PREFIX/include $CFLAGS" \
  --extra-ldflags="-L$PREFIX/lib $LDFLAGS" \
  --extra-libs="-lm -ldl"

make -j2
make install
install -D -m 0755 "$PREFIX/bin/ffmpeg" "$OUT"
"$STRIP" "$OUT"
"$READELF" -h "$OUT" | grep -E "Class:.*ELF64|Machine:"
"$OUT" -version | head -n 2
"$OUT" -hide_banner -encoders 2>/dev/null | grep -q "libmp3lame"
"$OUT" -hide_banner -muxers 2>/dev/null | grep -q "mp4"
echo "FFmpeg + libmp3lame ready: $OUT ($(stat -c%s "$OUT") bytes)"
