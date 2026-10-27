#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

target="${1:-${APPLE_TARGET:-all}}"
deployment_target="${APPLE_DEPLOYMENT_TARGET:-13.0}"
source_dir=$(./scripts/bootstrap-sherpa-source.sh | tail -n 1)
apple_root="$repo_root/build/apple"
deps_root="$(cache_root)/deps"

SHERPA_ONNX_KMP_FETCH_APPLE=1 ./scripts/fetch-native-deps.sh

extract_zip_once() {
  local archive=$1
  local destination=$2
  if [[ ! -d "$destination" ]]; then
    mkdir -p "$destination"
    unzip -q "$archive" -d "$destination"
  fi
}

macos_ort_archive="$deps_root/onnxruntime-osx-arm64-static_lib-1.28.2.zip"
ios_ort_archive="$deps_root/onnxruntime-ios-static-xcframework-1.28.2.xcframework.zip"
extract_zip_once "$macos_ort_archive" "$apple_root/onnxruntime-osx-arm64-static"
extract_zip_once "$ios_ort_archive" "$apple_root/onnxruntime-ios-static"

macos_ort_root="$apple_root/onnxruntime-osx-arm64-static/onnxruntime-osx-arm64-static_lib-1.28.2"
ios_ort_xcframework="$apple_root/onnxruntime-ios-static/onnxruntime.xcframework"

common_cmake_args=(
  -G Ninja
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_OSX_ARCHITECTURES=arm64
  -DBUILD_SHARED_LIBS=OFF
  -DSHERPA_ONNX_SOURCE_DIR="$source_dir"
  -DSHERPA_ONNX_KMP_BUILD_JNI=OFF
  -DSHERPA_ONNX_KMP_BUILD_TESTS=OFF
  -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON
)

merge_apple_archive() {
  local build_dir=$1
  local onnxruntime_archive=$2
  local output="$build_dir/lib/libsherpa-onnx-kmp-apple.a"

  # libtool overwrites the aggregate archive in place on incremental builds.
  libtool -static -o "$output" \
    "$build_dir/lib/libsherpa-onnx-portable-bridge.a" \
    "$build_dir/lib/libsherpa-onnx-core.a" \
    "$build_dir/lib/libkaldi-native-fbank-core.a" \
    "$build_dir/lib/libkissfft-float.a" \
    "$build_dir/lib/libutf8proc.a" \
    "$build_dir/lib/libkaldi-decoder-core.a" \
    "$build_dir/lib/libsherpa-onnx-kaldifst-core.a" \
    "$build_dir/lib/libssentencepiece_core.a" \
    "$build_dir/lib/libsherpa-onnx-fstfar.a" \
    "$build_dir/lib/libsherpa-onnx-fst.a" \
    "$onnxruntime_archive"

  nm -gU "$output" > "$build_dir/native-symbols.txt"
  grep -q '_SherpaOnnxBridgeAbiVersion' "$build_dir/native-symbols.txt"
  grep -q '_OrtGetApiBase' "$build_dir/native-symbols.txt"
  printf 'Apple aggregate: %s (%s bytes)\n' "$output" "$(stat -f%z "$output")"
}

build_macos() {
  local build_dir="$apple_root/macos-arm64"
  export SHERPA_ONNXRUNTIME_LIB_DIR="$macos_ort_root/lib"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$macos_ort_root/include"
  cmake -S native -B "$build_dir" "${common_cmake_args[@]}" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$deployment_target"
  cmake --build "$build_dir" -j "${APPLE_BUILD_JOBS:-6}" \
    --target sherpa-onnx-portable-bridge-static
  merge_apple_archive "$build_dir" "$macos_ort_root/lib/libonnxruntime.a"
}

build_ios() {
  local build_dir="$apple_root/ios-arm64"
  export SHERPA_ONNXRUNTIME_LIB_DIR="$ios_ort_xcframework/ios-arm64"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ios_ort_xcframework/ios-arm64/onnxruntime.framework/Headers"
  cmake -S native -B "$build_dir" "${common_cmake_args[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$source_dir/toolchains/ios.toolchain.cmake" \
    -DPLATFORM=OS64 \
    -DDEPLOYMENT_TARGET="$deployment_target" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$deployment_target" \
    -DENABLE_BITCODE=0 \
    -DENABLE_ARC=1 \
    -DENABLE_VISIBILITY=0
  cmake --build "$build_dir" -j "${APPLE_BUILD_JOBS:-6}" \
    --target sherpa-onnx-portable-bridge-static
  merge_apple_archive \
    "$build_dir" \
    "$ios_ort_xcframework/ios-arm64/onnxruntime.framework/onnxruntime"
}

build_ios_simulator() {
  local build_dir="$apple_root/ios-simulator-arm64"
  local thin_ort="$build_dir/onnxruntime-arm64.a"
  export SHERPA_ONNXRUNTIME_LIB_DIR="$ios_ort_xcframework/ios-arm64_x86_64-simulator"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ios_ort_xcframework/ios-arm64_x86_64-simulator/onnxruntime.framework/Headers"
  cmake -S native -B "$build_dir" "${common_cmake_args[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$source_dir/toolchains/ios.toolchain.cmake" \
    -DPLATFORM=SIMULATORARM64 \
    -DDEPLOYMENT_TARGET="$deployment_target" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$deployment_target" \
    -DENABLE_BITCODE=0 \
    -DENABLE_ARC=1 \
    -DENABLE_VISIBILITY=0
  cmake --build "$build_dir" -j "${APPLE_BUILD_JOBS:-6}" \
    --target sherpa-onnx-portable-bridge-static
  mkdir -p "$build_dir"
  lipo -thin arm64 \
    "$ios_ort_xcframework/ios-arm64_x86_64-simulator/onnxruntime.framework/onnxruntime" \
    -output "$thin_ort"
  merge_apple_archive "$build_dir" "$thin_ort"
}

case "$target" in
  macos-arm64) build_macos ;;
  ios-arm64) build_ios ;;
  ios-simulator-arm64) build_ios_simulator ;;
  all)
    build_macos
    build_ios
    build_ios_simulator
    ;;
  *)
    echo "Usage: $0 [all|macos-arm64|ios-arm64|ios-simulator-arm64]" >&2
    exit 2
    ;;
esac
