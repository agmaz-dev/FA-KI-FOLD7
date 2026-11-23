#!/usr/bin/env bash
# Phonon (Stimme „Anton“, ab 4.9): baut libptts_ffi.so (C-Schnittstelle von gradium-ai/xn-ptts) für arm64-v8a.
# Wird von app/build.gradle.kts nur in GitHub Actions (CI=true) aufgerufen; dort sind Rust und das Android-NDK schon da.
# Klappt der Bau nicht, entsteht die APK einfach ohne Phonon (Exit-Code immer 0) – dann liest Supertonic vor.
#   build-phonon.sh <dir>  -> legt <dir>/arm64-v8a/libptts_ffi.so an
set -u
TAG="v0.7.1"
OUT="${1:?Zielordner fehlt}"
if [ -f "$OUT/arm64-v8a/libptts_ffi.so" ]; then exit 0; fi

NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [ -z "$NDK" ] && [ -n "${ANDROID_HOME:-}" ]; then NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"; fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then echo "Phonon: kein Android-NDK – APK ohne Phonon" >&2; exit 0; fi
export PATH="$HOME/.cargo/bin:$PATH"
if ! command -v cargo >/dev/null 2>&1; then echo "Phonon: kein Rust – APK ohne Phonon" >&2; exit 0; fi

WORK="$(mktemp -d)"
if ! git clone -q --depth 1 --branch "$TAG" https://github.com/gradium-ai/xn-ptts.git "$WORK/xn"; then
  echo "Phonon: xn-ptts $TAG nicht geladen – APK ohne Phonon" >&2; rm -rf "$WORK"; exit 0
fi
cd "$WORK/xn" || exit 0
rustup target add aarch64-linux-android >/dev/null 2>&1 || true

BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC_aarch64_linux_android="$BIN/aarch64-linux-android26-clang"
export CXX_aarch64_linux_android="$BIN/aarch64-linux-android26-clang++"
export AR_aarch64_linux_android="$BIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android26-clang"
# Like android/build.sh of xn-ptts: ARMv8.2 kernels (dotprod, fp16), stripped, 16 KB pages.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C strip=symbols -C link-arg=-Wl,-z,max-page-size=16384 -C target-cpu=generic -C target-feature=+dotprod,+fp16"
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS

if ! cargo build --release -p ptts-ffi --target aarch64-linux-android; then
  echo "Phonon: Bau fehlgeschlagen – APK ohne Phonon" >&2; rm -rf "$WORK"; exit 0
fi
mkdir -p "$OUT/arm64-v8a"
cp -f target/aarch64-linux-android/release/libptts_ffi.so "$OUT/arm64-v8a/" || true
ls -la "$OUT/arm64-v8a" || true
rm -rf "$WORK"
exit 0
