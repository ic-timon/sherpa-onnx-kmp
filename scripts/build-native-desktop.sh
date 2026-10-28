#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

target="${1:-all}"
jobs="${SHERPA_ONNX_KMP_BUILD_JOBS:-4}"

source_dir=$(./scripts/bootstrap-sherpa-source.sh | tail -n 1)
deps_root="$(cache_root)/deps"
desktop_root="$repo_root/build/native-desktop"

# Linux and Windows libraries can only be produced on their own platform
# because they need the platform C++ runtime and ONNX Runtime distribution.
host_os="$(uname -s)"

SHERPA_ONNX_KMP_FETCH_DESKTOP=1 ./scripts/fetch-native-deps.sh

extract_zip_once() {
  local archive=$1
  local destination=$2
  if [[ ! -d "$destination" ]]; then
    mkdir -p "$destination"
    unzip -q "$archive" -d "$destination"
  fi
}

extract_tar_once() {
  local archive=$1
  local destination=$2
  local strip=$3
  if [[ ! -d "$destination" ]]; then
    mkdir -p "$destination"
    tar -xf "$archive" -C "$destination" --strip-components="$strip"
  fi
}

# Collect every static archive produced by the sherpa-onnx build so the bridge
# and its dependencies can be merged into one archive per target.
sherpa_static_archives() {
  local build_dir=$1
  find "$build_dir/lib" -maxdepth 1 -name "*.a" \
    ! -name "libsherpa-onnx-kmp-*" | sort
}

merge_linux_archive() {
  local build_dir=$1
  local onnxruntime_archive=$2
  local output="$build_dir/lib/libsherpa-onnx-kmp-native-desktop.a"

  rm -f "$output"
  # ar-based merging is the portable equivalent of libtool -static on Linux.
  local objects
  objects=$(mktemp -d)
  (
    cd "$objects"
    for archive in $(sherpa_static_archives "$build_dir") "$onnxruntime_archive"; do
      ar x "$archive"
    done
  )
  rm -f "$output"
  (
    cd "$objects"
    ar rcs "$output" ./*.o
  )
  rm -rf "$objects"

  nm -g --defined-only "$output" > "$build_dir/native-symbols.txt"
  grep -q 'SherpaOnnxBridgeAbiVersion' "$build_dir/native-symbols.txt"
  grep -q 'OrtGetApiBase' "$build_dir/native-symbols.txt"
  printf 'Linux aggregate: %s (%s bytes)\n' "$output" "$(stat -c%s "$output")"
}

build_linux_x64() {
  if [[ "$host_os" != "Linux" ]]; then
    echo "linux-x64 must be built on Linux (host is $host_os)" >&2
    return 1
  fi
  local ort_archive="$deps_root/onnxruntime-linux-x64-static_lib-1.28.2-glibc2_17.zip"
  local ort_root="$desktop_root/onnxruntime-linux-x64"
  extract_zip_once "$ort_archive" "$ort_root"
  local ort_dir
  ort_dir=$(find "$ort_root" -maxdepth 1 -mindepth 1 -type d | head -n 1)

  local build_dir="$desktop_root/linux-x64"
  export SHERPA_ONNXRUNTIME_LIB_DIR="$ort_dir/lib"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ort_dir/include"
  cmake -S native -B "$build_dir" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DSHERPA_ONNX_SOURCE_DIR="$source_dir" \
    -DSHERPA_ONNX_KMP_BUILD_JNI=OFF \
    -DSHERPA_ONNX_KMP_BUILD_TESTS=OFF \
    -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON
  cmake --build "$build_dir" -j "$jobs" \
    --target sherpa-onnx-portable-bridge-static
  merge_linux_archive "$build_dir" "$ort_dir/lib/libonnxruntime.a"
}

build_linux_arm64() {
  if [[ "$host_os" != "Linux" ]]; then
    echo "linux-arm64 must be built on Linux (host is $host_os)" >&2
    return 1
  fi
  # Kotlin/Native only supports x86_64 Linux as a compiler host, so the
  # aarch64 library is cross-compiled from x64 and tested under qemu. A native
  # aarch64 host is accepted too, in which case the default compiler is used.
  local ort_archive="$deps_root/onnxruntime-linux-aarch64-static_lib-1.28.2-glibc2_17.zip"
  local ort_root="$desktop_root/onnxruntime-linux-arm64"
  extract_zip_once "$ort_archive" "$ort_root"
  local ort_dir
  ort_dir=$(find "$ort_root" -maxdepth 1 -mindepth 1 -type d | head -n 1)

  local build_dir="$desktop_root/linux-arm64"
  export SHERPA_ONNXRUNTIME_LIB_DIR="$ort_dir/lib"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ort_dir/include"

  local toolchain_args=()
  if [[ "$(uname -m)" != "aarch64" && "$(uname -m)" != "arm64" ]]; then
    if ! command -v aarch64-linux-gnu-g++ >/dev/null 2>&1; then
      echo "aarch64 cross toolchain is required: install g++-aarch64-linux-gnu" >&2
      return 1
    fi
    toolchain_args=(
      -DCMAKE_TOOLCHAIN_FILE="$repo_root/native/cmake/aarch64-linux-toolchain.cmake"
    )
  fi

  cmake -S native -B "$build_dir" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DSHERPA_ONNX_SOURCE_DIR="$source_dir" \
    -DSHERPA_ONNX_KMP_BUILD_JNI=OFF \
    -DSHERPA_ONNX_KMP_BUILD_TESTS=OFF \
    -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON \
    "${toolchain_args[@]}"
  cmake --build "$build_dir" -j "$jobs" \
    --target sherpa-onnx-portable-bridge-static
  merge_linux_archive "$build_dir" "$ort_dir/lib/libonnxruntime.a"
}

build_windows_x64() {
  if [[ "$host_os" != "MINGW64_NT"* && "$host_os" != "MSYS_NT"* && "$host_os" != "Windows_NT"* ]]; then
    echo "windows-x64 must be built on Windows (host is $host_os)" >&2
    return 1
  fi
  local ort_archive="$deps_root/onnxruntime-win-x64-static_lib-MT-Release-1.28.2.tar.bz2"
  local ort_root="$desktop_root/onnxruntime-win-x64"
  extract_tar_once "$ort_archive" "$ort_root" 1

  # sherpa-onnx's onnxruntime.cmake treats `onnxruntime.lib` on WIN32 as a DLL
  # import library and then requires a DLL to glob. This package is the static
  # distribution, so stage the same archive under the `libonnxruntime.a` name
  # that takes the upstream static-lib branch instead.
  local staged="$desktop_root/onnxruntime-win-x64-staged"
  mkdir -p "$staged/lib" "$staged/include"
  cp -f "$ort_root/lib/onnxruntime.lib" "$staged/lib/libonnxruntime.a"
  cp -rf "$ort_root/include/." "$staged/include/"

  local build_dir="$desktop_root/windows-x64"
  export SHERPA_ONNXRUNTIME_LIB_DIR="$staged/lib"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$staged/include"
  # Windows links the bridge as a shared library. ONNX Runtime is static, so the
  # bridge carries ORT internally and only the bridge DLL ships next to the app.
  #
  # The toolchain must be MSVC: sherpa-onnx injects MSVC-only flags (/wd4018,
  # /bigobj, ...) on WIN32 unconditionally, so a MinGW g++ build fails at the
  # first compile. See ADR 0007.
  local generator="Visual Studio 17 2022"
  local arch_flag=(-A x64)
  if ! command -v cmake >/dev/null 2>&1; then
    echo "cmake is required" >&2
    return 1
  fi
  # `-G` with the VS generator writes a multi-config build tree.
  cmake -S native -B "$build_dir" -G "$generator" "${arch_flag[@]}" \
    -DCMAKE_CXX_STANDARD=17 \
    -DSHERPA_ONNX_SOURCE_DIR="$source_dir" \
    -DSHERPA_ONNX_KMP_BUILD_JNI=OFF \
    -DSHERPA_ONNX_KMP_BUILD_TESTS=OFF \
    -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON
  cmake --build "$build_dir" --config Release -j "$jobs" \
    --target sherpa-onnx-portable-bridge

  local dll
  dll=$(find "$build_dir" -name "sherpa-onnx-portable-bridge.dll" | head -n 1)
  if [[ -z "$dll" ]]; then
    echo "Bridge DLL was not produced" >&2
    return 1
  fi
  mkdir -p "$build_dir/lib"
  cp -f "$dll" "$build_dir/lib/"
  # Kotlin/Native links against the import library that MSVC emits next to the
  # DLL.
  local implib
  implib=$(find "$build_dir" -name "sherpa-onnx-portable-bridge.lib" | head -n 1)
  if [[ -n "$implib" ]]; then
    cp -f "$implib" "$build_dir/lib/"
  fi
  printf 'Windows bridge: %s\n' "$build_dir/lib/sherpa-onnx-portable-bridge.dll"
}

case "$target" in
  linux-x64) build_linux_x64 ;;
  linux-arm64) build_linux_arm64 ;;
  windows-x64) build_windows_x64 ;;
  all)
    build_linux_x64
    build_linux_arm64
    build_windows_x64
    ;;
  *)
    echo "Usage: $0 [all|linux-x64|linux-arm64|windows-x64]" >&2
    exit 2
    ;;
esac
