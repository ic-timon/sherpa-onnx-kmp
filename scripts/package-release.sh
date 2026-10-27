#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
version="${1:-0.3.0}"
out="$repo_root/build/release/$version"
mkdir -p "$out"

cd "$repo_root"
./gradlew :sherpa-onnx-kmp:jvmJar :sherpa-onnx-kmp:assemble --console=plain
./scripts/check-android-aar.sh
native_jar=$(./scripts/package-jvm-native.sh | tail -n 1)

cp -f sherpa-onnx-kmp/build/libs/sherpa-onnx-kmp-jvm-$version.jar \
  "$out/sherpa-onnx-kmp-jvm-$version.jar"
cp -f sherpa-onnx-kmp/build/outputs/aar/sherpa-onnx-kmp.aar \
  "$out/sherpa-onnx-kmp-android-$version.aar"
cp -f "$native_jar" \
  "$out/sherpa-onnx-kmp-jvm-native-osx-aarch64-$version.jar"

cd "$out"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum *.aar *.jar > SHA256SUMS
else
  shasum -a 256 *.aar *.jar > SHA256SUMS
fi
cat SHA256SUMS
