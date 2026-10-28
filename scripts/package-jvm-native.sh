#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
version="${1:-1.0.0}"
os=$(uname -s | tr '[:upper:]' '[:lower:]')
arch=$(uname -m)
case "$os" in
  darwin) platform="osx-$([[ "$arch" == "arm64" ]] && echo aarch64 || echo x64)" ;;
  linux) platform="linux-$([[ "$arch" == "aarch64" ]] && echo aarch64 || echo x64)" ;;
  # Git Bash reports MSYS_NT or MINGW64_NT, and uname -m reports x86_64 there.
  msys* | mingw* | cygwin*) platform="windows-x64" ;;
  *) echo "Unsupported host for JVM native packaging: $os" >&2; exit 1 ;;
esac

# CI configures the portable bridge at build/native; older trees used
# build/native-test. Accept either so a developer can package a local build.
lib_dir=""
for candidate in "$repo_root/build/native/lib" "$repo_root/build/native-test/lib"; do
  if compgen -G "$candidate/*sherpa-onnx-kmp-jni*" >/dev/null; then
    lib_dir="$candidate"
    break
  fi
done
if [[ -z "$lib_dir" ]]; then
  echo "No JNI library found under build/native/lib or build/native-test/lib" >&2
  echo "Configure and build the native tree first (see .github/workflows/portable-bridge.yml)." >&2
  exit 1
fi

stage=$(mktemp -d "${TMPDIR:-/tmp}/sherpa-kmp-jvm-native.XXXXXX")
dest="$stage/sherpa-onnx/native/$platform"
mkdir -p "$dest"
# Only the runtime artefacts belong in the JAR. The same directory also holds
# static archives (.a/.lib) that a Kotlin/Native consumer links against, and
# shipping those would triple the download for no runtime benefit.
for built in "$lib_dir/"*"sherpa-onnx-kmp-jni"* "$lib_dir/"*"sherpa-onnx-portable-bridge"*; do
  case "$built" in
    *.a|*.lib) continue ;;
  esac
  cp -f "$built" "$dest/"
done

# Non-Apple hosts also need the ONNX Runtime shared library beside the bridge,
# because the bridge records it as DT_NEEDED.
if [[ "$os" == "linux" ]]; then
  ort=$(find "$repo_root/build/native-desktop" -name "libonnxruntime.so" 2>/dev/null | head -n 1)
  if [[ -z "$ort" ]]; then
    echo "libonnxruntime.so was not found under build/native-desktop" >&2
    exit 1
  fi
  cp -f "$ort" "$dest/"
fi

# The JAR belongs in the release directory with every other artifact. Writing it
# to build/release/ root made the manifest glob and the release upload miss it.
out="$repo_root/build/release/$version/sherpa-onnx-kmp-jvm-native-$platform-$version.jar"
mkdir -p "$(dirname "$out")"
jar --create --file "$out" -C "$stage" .
printf '%s\n' "$out"
