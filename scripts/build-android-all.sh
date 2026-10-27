#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  echo "=== Building Android ABI: $abi ==="
  ANDROID_ABI="$abi" "$repo_root/scripts/build-android-native.sh"
done
cd "$repo_root"
./gradlew :sherpa-onnx-kmp:assemble --console=plain
