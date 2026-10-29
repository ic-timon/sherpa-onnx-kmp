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

# The desktop libraries exist to be linked by Kotlin/Native, which needs only the
# bridge. The JVM native JAR additionally needs the JNI library, so allow the
# release job to ask for both from one configure step rather than duplicating the
# per-platform CMake flags in a workflow.
jni_flag="OFF"
if [[ "${SHERPA_ONNX_KMP_DESKTOP_WITH_JNI:-0}" == "1" ]]; then
  jni_flag="ON"
fi

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

# Verify a produced shared library before it is published to Kotlin/Native.
#
# A shared library is the only workable Linux linkage here: Kotlin/Native links
# with the konan sysroot (glibc 2.19), while the C++ graph is compiled against
# the build host's newer glibc/libstdc++. Static linkage therefore always ends
# in undefined `__isoc23_*`, `__libc_single_threaded`, and libstdc++ symbols.
# Inside a `.so` those references resolve at runtime from the host's system
# libraries, and only the bridge's C ABI is a link-time dependency.
inspect_linux_shared_library() {
  local build_dir=$1
  local library="$build_dir/lib/libsherpa-onnx-kmp-native-desktop.so"
  local symbols="$build_dir/native-symbols.txt"

  # Cross builds need the target archiver/nm; the host tools cannot read
  # aarch64 objects. Pick the prefixed tools when they exist.
  local nm_bin=nm
  local readelf_bin=readelf
  if [[ "$(uname -m)" != "aarch64" && "$(uname -m)" != "arm64" ]] && \
     [[ "$build_dir" == *"linux-arm64"* ]] && \
     command -v aarch64-linux-gnu-nm >/dev/null 2>&1; then
    nm_bin=aarch64-linux-gnu-nm
    readelf_bin=aarch64-linux-gnu-readelf
  fi

  if [[ ! -s "$library" ]]; then
    echo "Shared library was not produced: $library" >&2
    return 1
  fi

  # Exported symbols only: the visibility preset hides everything that is not
  # part of the bridge C ABI.
  "$nm_bin" -D --defined-only "$library" > "$symbols" 2>/dev/null || true
  local required
  for required in \
    SherpaOnnxBridgeAbiVersion \
    SherpaOnnxBridgeInfoJson \
    SherpaOnnxBridgeCreate \
    SherpaOnnxBridgeCall \
    SherpaOnnxBridgePushAudio \
    SherpaOnnxBridgeDestroy \
    SherpaOnnxBridgeFree; do
    if ! grep -qw "$required" "$symbols"; then
      echo "Shared library does not export $required" >&2
      return 1
    fi
  done

  # ONNX Runtime is a sibling shared library in this packaging model, so the
  # bridge must record it as a NEEDED entry. A bridge without it would fail at
  # load time with an unresolved `OrtGetApiBase`.
  if ! "$readelf_bin" -d "$library" | grep -q 'libonnxruntime'; then
    echo "Bridge does not record a libonnxruntime dependency" >&2
    "$readelf_bin" -d "$library" >&2
    return 1
  fi

  # The SONAME is what a consumer's `DT_NEEDED` records, so the packaged set
  # must contain a file with that exact name. Without this check a renamed copy
  # links fine and then fails with exit code 127 at test time.
  local soname
  soname=$("$readelf_bin" -d "$library" | sed -n 's/.*SONAME.*\[\(.*\)\].*/\1/p')
  if [[ -z "$soname" ]]; then
    echo "Bridge does not declare a SONAME" >&2
    return 1
  fi
  if [[ ! -f "$build_dir/lib/$soname" ]]; then
    echo "Packaged bridge is missing its SONAME file: $soname" >&2
    ls -l "$build_dir/lib" >&2
    return 1
  fi

  # `libonnxruntime.so` lives in the extracted ONNX Runtime package, not in the
  # CMake build tree. Copy it beside the bridge so the pair ships together and
  # the rpath lookup next to the executable resolves it.
  local runtime
  runtime=$(find "$desktop_root" -name "libonnxruntime.so" | head -n 1)
  if [[ -z "$runtime" ]]; then
    echo "libonnxruntime.so was not found under $desktop_root" >&2
    return 1
  fi
  mkdir -p "$build_dir/lib"
  cp -f "$runtime" "$build_dir/lib/libonnxruntime.so"

  printf 'Linux shared library: %s (%s bytes)\n' \
    "$library" "$(stat -c%s "$library")"
  printf 'ONNX Runtime runtime: %s/lib/libonnxruntime.so\n' "$build_dir"
  printf 'Exported symbols: %s\n' "$(grep -c . < "$symbols")"
}

# Prepare the Linux bridge for Kotlin/Native consumption.
#
# CMake already writes the bridge into `<build>/lib` under its output name, and
# that name is also the library's SONAME (no `SOVERSION` is set). A consumer
# linked through the Kotlin alias therefore records
# `libsherpa-onnx-portable-bridge.so` in `DT_NEEDED`, and the loader resolves
# that name, not the `-l` name. The CMake file is kept as-is and the alias is an
# additional copy, so the link name and the runtime name stay in agreement.
#
# The path is exact rather than discovered with `find`: on a rerun the previous
# alias copy is itself a match, and `cp` would fail on "are the same file".
package_linux_shared_library() {
  local build_dir=$1
  local soname_file="$build_dir/lib/libsherpa-onnx-portable-bridge.so"
  local alias_file="$build_dir/lib/libsherpa-onnx-kmp-native-desktop.so"
  if [[ ! -s "$soname_file" ]]; then
    echo "Bridge shared library was not produced at $soname_file" >&2
    return 1
  fi
  mkdir -p "$build_dir/lib"
  cp -f "$soname_file" "$alias_file"
  printf 'Linux bridge: %s\n' "$alias_file"
  printf 'Linux bridge SONAME: %s\n' "$soname_file"
}

build_linux_x64() {
  if [[ "$host_os" != "Linux" ]]; then
    echo "linux-x64 must be built on Linux (host is $host_os)" >&2
    return 1
  fi
  local ort_archive="$deps_root/onnxruntime-linux-x64-glibc2_17-Release-1.28.2.zip"
  local ort_root="$desktop_root/onnxruntime-linux-x64"
  extract_zip_once "$ort_archive" "$ort_root"
  local ort_dir
  ort_dir=$(find "$ort_root" -maxdepth 1 -mindepth 1 -type d | head -n 1)

  local build_dir="$desktop_root/linux-x64"
  export SHERPA_ONNXRUNTIME_LIB_DIR="$ort_dir/lib"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ort_dir/include"
  # A shared bridge is required, not a preference. Kotlin/Native links with the
  # konan sysroot (glibc 2.19), so a static C++ graph compiled against the
  # runner's glibc/libstdc++ can never resolve. A `.so` closes those references
  # inside itself at runtime; only the C ABI crosses into Kotlin.
  #
  # `SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF` stops upstream from appending
  # `-static-libstdc++ -static-libgcc`, which would reintroduce the mixing.
  cmake -S native -B "$build_dir" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
    -DSHERPA_ONNX_SOURCE_DIR="$source_dir" \
    -DSHERPA_ONNX_KMP_BUILD_JNI=${jni_flag} \
    -DSHERPA_ONNX_KMP_BUILD_TESTS=OFF \
    -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON
  local targets=(sherpa-onnx-portable-bridge)
  if [[ "$jni_flag" == "ON" ]]; then
    targets+=(sherpa-onnx-kmp-jni)
  fi
  cmake --build "$build_dir" -j "$jobs" --target "${targets[@]}"
  package_linux_shared_library "$build_dir"
  inspect_linux_shared_library "$build_dir"
}

build_linux_arm64() {
  if [[ "$host_os" != "Linux" ]]; then
    echo "linux-arm64 must be built on Linux (host is $host_os)" >&2
    return 1
  fi
  # Kotlin/Native only supports x86_64 Linux as a compiler host, so the
  # aarch64 library is cross-compiled from x64 and tested under qemu. A native
  # aarch64 host is accepted too, in which case the default compiler is used.
  local ort_archive="$deps_root/onnxruntime-linux-aarch64-glibc2_17-Release-1.28.2.zip"
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
    -DBUILD_SHARED_LIBS=ON \
    -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
    -DSHERPA_ONNX_SOURCE_DIR="$source_dir" \
    -DSHERPA_ONNX_KMP_BUILD_JNI=${jni_flag} \
    -DSHERPA_ONNX_KMP_BUILD_TESTS=OFF \
    -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON \
    "${toolchain_args[@]}"
  local targets=(sherpa-onnx-portable-bridge)
  if [[ "$jni_flag" == "ON" ]]; then
    targets+=(sherpa-onnx-kmp-jni)
  fi
  cmake --build "$build_dir" -j "$jobs" --target "${targets[@]}"
  package_linux_shared_library "$build_dir"

  # arm64 uses the aarch64 archiver/nm, which the host toolchain does not put on
  # PATH under the canonical name when cross-compiling.
  inspect_linux_shared_library "$build_dir"
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
  # The pinned Windows ONNX Runtime package is the `MT` (static CRT) build, so
  # the whole tree must use the static runtime. Mixing /MD and /MT fails at link
  # time with LNK2038 across libcpmt/msvcprt.
  local generator="Visual Studio 17 2022"
  local arch_flag=(-A x64)
  if ! command -v cmake >/dev/null 2>&1; then
    echo "cmake is required" >&2
    return 1
  fi
  cmake -S native -B "$build_dir" -G "$generator" "${arch_flag[@]}" \
    -DCMAKE_CXX_STANDARD=17 \
    -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded \
    -DSHERPA_ONNX_SOURCE_DIR="$source_dir" \
    -DSHERPA_ONNX_KMP_BUILD_JNI=${jni_flag} \
    -DSHERPA_ONNX_KMP_BUILD_TESTS=OFF \
    -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON
  local targets=(sherpa-onnx-portable-bridge)
  if [[ "$jni_flag" == "ON" ]]; then
    targets+=(sherpa-onnx-kmp-jni)
  fi
  cmake --build "$build_dir" --config Release -j "$jobs" --target "${targets[@]}"

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

  # MSVC writes a DLL next to its own target directory, not into
  # LIBRARY_OUTPUT_DIRECTORY, so stage the JNI DLL too when it was built.
  if [[ "$jni_flag" == "ON" ]]; then
    local jni_dll
    jni_dll=$(find "$build_dir" -name "sherpa-onnx-kmp-jni.dll" | head -n 1)
    if [[ -z "$jni_dll" ]]; then
      echo "JNI DLL was not produced" >&2
      return 1
    fi
    cp -f "$jni_dll" "$build_dir/lib/"
    printf 'Windows JNI: %s\n' "$build_dir/lib/sherpa-onnx-kmp-jni.dll"
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
