#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
aar="$repo_root/sherpa-onnx-kmp/build/outputs/aar/sherpa-onnx-kmp.aar"
if [[ ! -f "$aar" ]]; then
  echo "AAR not found: $aar" >&2
  exit 1
fi

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  for name in libsherpa-onnx-kmp-jni.so libsherpa-onnx-portable-bridge.so libonnxruntime.so libc++_shared.so; do
    count=$(unzip -l "$aar" | awk -v path="jni/$abi/$name" '$4 == path {count++} END {print count+0}')
    if [[ "$count" != "1" ]]; then
      echo "Expected exactly one jni/$abi/$name, found $count" >&2
      exit 1
    fi
  done
done
echo "AAR ABI inventory is valid: $aar"
