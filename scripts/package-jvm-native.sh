#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
os=$(uname -s | tr '[:upper:]' '[:lower:]')
arch=$(uname -m)
case "$os" in
  darwin) platform="osx-$([[ "$arch" == "arm64" ]] && echo aarch64 || echo x64)" ;;
  linux) platform="linux-$([[ "$arch" == "aarch64" ]] && echo aarch64 || echo x64)" ;;
  *) echo "Unsupported host for JVM native packaging: $os" >&2; exit 1 ;;
esac

stage=$(mktemp -d "${TMPDIR:-/tmp}/sherpa-kmp-jvm-native.XXXXXX")
dest="$stage/sherpa-onnx/native/$platform"
mkdir -p "$dest"
cp -f "$repo_root/build/native/lib/"*"sherpa-onnx-kmp-jni"* "$dest/"
cp -f "$repo_root/build/native/lib/"*"sherpa-onnx-portable-bridge"* "$dest/"

out="$repo_root/build/release/sherpa-onnx-kmp-jvm-native-$platform.jar"
mkdir -p "$(dirname "$out")"
jar --create --file "$out" -C "$stage" .
printf '%s\n' "$out"
