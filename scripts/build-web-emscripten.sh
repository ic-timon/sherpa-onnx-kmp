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
deps_root="$(cache_root)/deps"

# The Emscripten build needs a Wasm ONNX Runtime. sherpa-onnx can also build
# without it for C API parity, but inference requires the Wasm runtime.
wasm_ort="$deps_root/onnxruntime-wasm"
mkdir -p "$wasm_ort"

build_dir="$repo_root/build/web-emscripten"
mkdir -p "$build_dir"

emcmake cmake -S native -B "$build_dir" \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DSHERPA_ONNX_SOURCE_DIR="$source_dir" \
  -DSHERPA_ONNX_KMP_BUILD_JNI=OFF \
  -DSHERPA_ONNX_KMP_BUILD_TESTS=OFF \
  -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
  -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
  -DSHERPA_ONNX_ENABLE_BINARY=OFF

cmake --build "$build_dir" -j "$jobs" --target sherpa-onnx-portable-bridge-static

mkdir -p "$out_dir"

# The exported symbol set is the bridge ABI plus the Emscripten runtime
# helpers the worker needs. Capability discovery must reflect exactly these.
EXPORTED_FUNCTIONS='["_SherpaOnnxBridgeAbiVersion","_SherpaOnnxBridgeInfoJson","_SherpaOnnxBridgeCreate","_SherpaOnnxBridgeCall","_SherpaOnnxBridgePushAudio","_SherpaOnnxBridgeDestroy","_SherpaOnnxBridgeFree","_malloc","_free"]'

emcc -O3 \
  -s WASM=1 \
  -s MODULARIZE=1 \
  -s EXPORT_NAME=createSherpaOnnxBridgeModule \
  -s EXPORT_ES6=0 \
  -s ENVIRONMENT=worker \
  -s ALLOW_MEMORY_GROWTH=1 \
  -s INITIAL_MEMORY=268435456 \
  -s MAXIMUM_MEMORY=2147483648 \
  -s "EXPORTED_FUNCTIONS=$EXPORTED_FUNCTIONS" \
  -s "EXPORTED_RUNTIME_METHODS=['UTF8ToString','getValue','setValue','FS']" \
  -s FILESYSTEM=1 \
  -s ASSERTIONS=0 \
  -o "$out_dir/sherpa-onnx-bridge.js" \
  "$build_dir/lib/libsherpa-onnx-portable-bridge.a" \
  $(find "$build_dir/lib" -maxdepth 1 -name "*.a" \
      ! -name "libsherpa-onnx-portable-bridge.a" | sort)

printf 'Web bridge: %s\n' "$out_dir/sherpa-onnx-bridge.js"
printf 'Wasm module: %s\n' "$out_dir/sherpa-onnx-bridge.wasm"

# Fail loudly if the export set lost a bridge entry point.
for symbol in SherpaOnnxBridgeInfoJson SherpaOnnxBridgeCreate SherpaOnnxBridgeCall \
              SherpaOnnxBridgePushAudio SherpaOnnxBridgeDestroy; do
  if ! nm -g "$build_dir/lib/libsherpa-onnx-portable-bridge.a" | grep -q "$symbol"; then
    echo "Bridge archive is missing $symbol" >&2
    exit 1
  fi
done
echo "Bridge export inspection: OK"
