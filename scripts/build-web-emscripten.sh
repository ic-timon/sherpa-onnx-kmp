#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

out_dir="${1:-$repo_root/build/web}"
jobs="${SHERPA_ONNX_KMP_BUILD_JOBS:-4}"

if ! command -v emcmake >/dev/null 2>&1; then
  echo "Emscripten is required. Install the emsdk and source emsdk_env.sh." >&2
  exit 1
fi

source_dir=$(./scripts/bootstrap-sherpa-source.sh | tail -n 1)

# Publish the pinned Wasm ONNX Runtime into the cache. Upstream's
# `cmake/onnxruntime-wasm-simd.cmake` probes `/tmp` first, so doing this before
# configure keeps the build offline-deterministic and hash-verified.
SHERPA_ONNX_KMP_FETCH_DESKTOP=1 ./scripts/fetch-native-deps.sh

build_dir="$repo_root/build/web-emscripten"
mkdir -p "$build_dir"

# A cached build tree is only reusable while the Emscripten SDK sits at the same
# path. `emcmake` bakes absolute paths to `Emscripten.cmake` and to `emcc` into
# `CMakeFiles/<version>/CMakeSystem.cmake`, and CI installs the SDK under a fresh
# temporary directory on every run, so a restored cache points at a compiler that
# no longer exists. Detect that and start clean instead of failing configuration.
if [[ -f "$build_dir/CMakeCache.txt" ]]; then
  cached_emcc=$(sed -n 's/^CMAKE_C_COMPILER:.*=//p' "$build_dir/CMakeCache.txt" | head -n 1)
  if [[ -n "$cached_emcc" && ! -x "$cached_emcc" ]]; then
    printf 'Discarding the stale Emscripten build tree: %s is gone\n' "$cached_emcc"
    rm -rf "$build_dir"
    mkdir -p "$build_dir"
  fi
fi

# `SHERPA_ONNX_ENABLE_WASM` is upstream's supported WebAssembly path. Without
# it `cmake/onnxruntime.cmake` rejects Emscripten outright ("Only support Linux,
# macOS, Windows, and Android"), because its downloader has no Wasm branch. With
# it, the pinned `onnxruntime-wasm-static_lib-simd` package is used, ALSA probing
# is skipped, and `macros.h` selects the Wasm logging branch.
#
# Deliberately NOT set: `SHERPA_ONNX_ENABLE_WASM_ASR` and
# `SHERPA_ONNX_ENABLE_WASM_WEB`. Those flags only gate upstream's demo targets
# under `wasm/`, and `wasm/asr/CMakeLists.txt` aborts configuration unless
# model files were pre-downloaded into `wasm/asr/assets`. The bridge links
# `sherpa-onnx-core` directly and does not need any demo target.
emcmake cmake -S native -B "$build_dir" \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DSHERPA_ONNX_ENABLE_WASM=ON \
  -DSHERPA_ONNX_SOURCE_DIR="$source_dir" \
  -DSHERPA_ONNX_KMP_BUILD_JNI=OFF \
  -DSHERPA_ONNX_KMP_BUILD_TESTS=OFF \
  -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
  -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
  -DSHERPA_ONNX_ENABLE_BINARY=OFF \
  -DSHERPA_ONNX_ENABLE_TTS=OFF \
  -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF

cmake --build "$build_dir" -j "$jobs" --target sherpa-onnx-bridge-web

mkdir -p "$out_dir"

# CMake emits the pair next to each other; copy them to the conventional output
# directory the Kotlin/JS worker loads.
for artifact in sherpa-onnx-bridge.js sherpa-onnx-bridge.wasm; do
  source_file="$build_dir/web/$artifact"
  if [[ ! -f "$source_file" ]]; then
    echo "Emscripten output is missing: $source_file" >&2
    exit 1
  fi
  cp -f "$source_file" "$out_dir/$artifact"
done

printf 'Web bridge: %s\n' "$out_dir/sherpa-onnx-bridge.js"
printf 'Wasm module: %s\n' "$out_dir/sherpa-onnx-bridge.wasm"

# Fail loudly if the bridge entry points disappeared from the surface the worker
# actually calls.
#
# At `-O3` Emscripten renames the *Wasm export table* to single letters; its
# `MINIFY_WASM_*` settings are internal and cannot be disabled from the command
# line. The renaming pass rewrites the generated glue in lockstep, and the glue
# keeps publishing the stable `Module['_SherpaOnnxBridge*']` names. Those names,
# not the minified table, are the contract: they are what the worker resolves and
# what the public C ABI promises. Inspecting the table for unmangled names would
# therefore reject a correct build.
glue="$out_dir/sherpa-onnx-bridge.js"
for symbol in \
  _SherpaOnnxBridgeAbiVersion \
  _SherpaOnnxBridgeInfoJson \
  _SherpaOnnxBridgeCreate \
  _SherpaOnnxBridgeCall \
  _SherpaOnnxBridgePushAudio \
  _SherpaOnnxBridgeDestroy \
  _SherpaOnnxBridgeFree; do
  if ! grep -Fq "$symbol" "$glue"; then
    echo "JS glue does not publish $symbol" >&2
    exit 1
  fi
done
echo "JS glue inspection: OK"

# The exported function count is still a useful tripwire: a build that exported
# nothing would pass a substring search on a stale glue file.
wasm_symbols="$build_dir/wasm-exports.txt"
if command -v wasm-objdump >/dev/null 2>&1; then
  wasm-objdump -x "$out_dir/sherpa-onnx-bridge.wasm" > "$wasm_symbols" 2>/dev/null || true
  exported_functions=$(grep -c -- '- func\[' "$wasm_symbols" || true)
  if [[ "${exported_functions:-0}" -lt 9 ]]; then
    echo "Wasm module exports too few functions: ${exported_functions:-0}" >&2
    sed -n '/^Export\[/,$p' "$wasm_symbols" | head -20 >&2
    exit 1
  fi
  printf 'Wasm exported functions: %s\n' "$exported_functions"
else
  echo "wasm-objdump is not available; skipping the Wasm export count" >&2
fi
