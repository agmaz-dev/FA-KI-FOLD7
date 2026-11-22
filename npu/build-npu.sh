#!/usr/bin/env bash
# NPU (Hexagon) für GGUF-Modelle: baut libggml-hexagon.so und die DSP-Bibliotheken libggml-htp-v73…v85.so aus
# llama.cpp b12287 (dieselbe Version wie app/src/main/cpp) im offiziellen Snapdragon-Werkzeug-Image (Android-NDK + Hexagon-SDK).
# Wird von app/build.gradle.kts nur in GitHub Actions (CI=true) aufgerufen. Fehlt Docker oder klappt der Bau nicht,
# entsteht die APK einfach ohne NPU (Exit-Code immer 0).
#   build-npu.sh ndk          -> gibt die NDK-Version des Images aus (z. B. 28.1.13356709)
#   build-npu.sh build <dir>  -> legt die Bibliotheken nach <dir>/arm64-v8a
set -u
IMAGE="ghcr.io/snapdragon-toolchain/arm64-android:v0.8"
TAG="b12287"
MODE="${1:-build}"

# Gemerkte NDK-Version (liegt im Build-Ordner), damit das Image nicht bei jedem Gradle-Aufruf geladen wird.
NDK_FILE="$(cd "$(dirname "$0")/.." && pwd)/app/build/npu-ndk.txt"

if [ "$MODE" = "ndk" ]; then
  if [ -s "$NDK_FILE" ]; then cat "$NDK_FILE"; exit 0; fi
  command -v docker >/dev/null 2>&1 || { echo "NPU: kein Docker – APK ohne NPU" >&2; exit 0; }
  V="$(docker run --rm --platform linux/amd64 "$IMAGE" bash -c \
    'sed -n "s/^Pkg\.Revision *= *//p" "$ANDROID_NDK_ROOT/source.properties"' 2>/dev/null | tr -d '\r' | tail -1)"
  if [ -n "$V" ]; then mkdir -p "$(dirname "$NDK_FILE")"; echo "$V" > "$NDK_FILE"; echo "$V"; fi
  exit 0
fi

command -v docker >/dev/null 2>&1 || { echo "NPU: kein Docker – APK ohne NPU" >&2; exit 0; }

OUT="${2:?Zielordner fehlt}"
if [ -f "$OUT/arm64-v8a/libggml-hexagon.so" ] && [ -f "$OUT/arm64-v8a/libggml-htp-v79.so" ]; then exit 0; fi

WORK="$(mktemp -d)"
if ! git clone -q --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$WORK/llama.cpp"; then
  echo "NPU: llama.cpp $TAG nicht geladen – APK ohne NPU" >&2; exit 0
fi

if ! docker run --rm --platform linux/amd64 -e OWNER="$(id -u):$(id -g)" -v "$WORK:/work" -w /work/llama.cpp "$IMAGE" bash -c '
  set -e
  trap "chown -R \"\$OWNER\" /work || true" EXIT
  cp docs/backend/snapdragon/CMakeUserPresets.json .
  cmake --preset arm64-android-snapdragon-release -B build \
    -DGGML_OPENCL=OFF -DGGML_HEXAGON=ON \
    -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=OFF -DGGML_NATIVE=OFF \
    -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_COMMON=OFF -DLLAMA_CURL=OFF \
    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    "-DCMAKE_MODULE_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
  cmake --build build --target ggml-hexagon htp-v73 htp-v75 htp-v79 htp-v81 htp-v85 -j "$(nproc)"
'; then
  echo "NPU: Bau im Snapdragon-Image fehlgeschlagen – APK ohne NPU" >&2; rm -rf "$WORK"; exit 0
fi

mkdir -p "$OUT/arm64-v8a"
find "$WORK/llama.cpp/build" -type f \( -name 'libggml-hexagon.so' -o -name 'libggml-htp-v*.so' \) -exec cp -f {} "$OUT/arm64-v8a/" \;
ls -la "$OUT/arm64-v8a"
if [ ! -f "$OUT/arm64-v8a/libggml-hexagon.so" ] || [ ! -f "$OUT/arm64-v8a/libggml-htp-v79.so" ]; then
  echo "NPU: Bibliotheken fehlen nach dem Bau – APK ohne NPU" >&2; rm -rf "$OUT"
fi
rm -rf "$WORK"
docker image rm "$IMAGE" >/dev/null 2>&1 || true   # Platz auf dem Bau-Rechner freigeben
exit 0
