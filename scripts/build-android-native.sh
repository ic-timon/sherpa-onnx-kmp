#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ ! -d "$sdk" ]]; then
  echo "Android SDK not found: $sdk" >&2
  exit 1
fi

if [[ -n "${ANDROID_NDK:-}" ]]; then
  ndk="$ANDROID_NDK"
else
  ndk=$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
fi
if [[ -z "${ndk:-}" || ! -d "$ndk" ]]; then
  echo "Android NDK not found under $sdk/ndk" >&2
  exit 1
fi

source_dir=$(cd "$repo_root" && ./scripts/bootstrap-sherpa-source.sh | tail -n 1)
SHERPA_ONNX_KMP_FETCH_ANDROID=1 "$repo_root/scripts/fetch-native-deps.sh"

abi="${ANDROID_ABI:-arm64-v8a}"
case "$abi" in
  arm64-v8a)
    build_name=android-arm64
    onnx_abi=arm64-v8a
    clang_triple=aarch64-linux-android
    ;;
  x86_64)
    build_name=android-x86_64
    onnx_abi=x86_64
    clang_triple=x86_64-linux-android
    ;;
  armeabi-v7a)
    build_name=android-armv7
    onnx_abi=armeabi-v7a
    clang_triple=arm-linux-androideabi
    ;;
  x86)
    build_name=android-x86
    onnx_abi=x86
    clang_triple=i686-linux-android
    ;;
  *)
    echo "Unsupported Android ABI: $abi" >&2
    exit 1
    ;;
esac

build_dir="$repo_root/build/$build_name"
cmake -S "$repo_root/native" -B "$build_dir" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$abi" \
  -DANDROID_PLATFORM=android-21 \
  -DANDROID_STL=c++_shared \
  -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
  -DBUILD_SHARED_LIBS=ON \
  -DSHERPA_ONNX_SOURCE_DIR="$source_dir" \
  -DSHERPA_ONNX_KMP_BUILD_JNI=ON \
  -DSHERPA_ONNX_KMP_BUILD_TESTS=OFF

cmake --build "$build_dir" -j 4 --target sherpa-onnx-kmp-jni

jni_dir="$repo_root/sherpa-onnx-kmp/src/androidMain/jniLibs/$onnx_abi"
mkdir -p "$jni_dir"
cp -f "$build_dir/lib/libsherpa-onnx-kmp-jni.so" "$jni_dir/"
cp -f "$build_dir/lib/libsherpa-onnx-portable-bridge.so" "$jni_dir/"
onnxruntime=$(find "$build_dir/_deps/onnxruntime-src" -path "*/$onnx_abi/libonnxruntime.so" | head -1)
if [[ -z "$onnxruntime" ]]; then
  echo "libonnxruntime.so was not found in the Android build" >&2
  exit 1
fi
cp -f "$onnxruntime" "$jni_dir/"
libcxx_shared=$(find "$ndk/toolchains/llvm/prebuilt"   -path "*/sysroot/usr/lib/$clang_triple/libc++_shared.so" | head -1)
if [[ -z "$libcxx_shared" ]]; then
  echo "libc++_shared.so was not found for $abi" >&2
  exit 1
fi
cp -f "$libcxx_shared" "$jni_dir/"
ls -lh "$jni_dir"
