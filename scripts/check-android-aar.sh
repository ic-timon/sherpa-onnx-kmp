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

# Inventory alone cannot tell a fresh build from a stale one: an old bridge with
# fewer feature kinds has exactly the same file names. Scan the packaged bridge
# for the exported entry points instead, so a build that predates a feature
# cannot be shipped under a version that claims it.
work=$(mktemp -d)
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

unzip -q "$aar" 'jni/*/libsherpa-onnx-portable-bridge.so' -d "$work"
unzip -q "$aar" 'jni/*/libc++_shared.so' -d "$work"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  so="$work/jni/$abi/libsherpa-onnx-portable-bridge.so"
  if [[ ! -f "$so" ]]; then
    echo "Missing packaged bridge for $abi" >&2
    exit 1
  fi
  python3 "$repo_root/scripts/check-elf-exports.py" "$so" \
    SherpaOnnxBridgeAbiVersion \
    SherpaOnnxBridgeInfoJson \
    SherpaOnnxBridgeCreate \
    SherpaOnnxBridgeCall \
    SherpaOnnxBridgePushAudio \
    SherpaOnnxBridgePullAudio \
    SherpaOnnxBridgeDestroy \
    SherpaOnnxBridgeFree \
    SherpaOnnxBridgeFreeAudio || {
      echo "Packaged bridge for $abi does not export the full ABI 2 surface" >&2
      exit 1
    }

  # armeabi-v7a/x86/x86_64 at API 21 have no __tls_get_addr, so those ABIs
  # must be linked with emulated TLS and resolve __emutls_get_address from
  # libc++_shared. Catch a regression where the LTO plugin drops the flag.
  if python3 "$repo_root/scripts/check-elf-exports.py" --undefined "$so" __tls_get_addr >/dev/null 2>&1; then
    echo "Packaged bridge for $abi still references __tls_get_addr" >&2
    exit 1
  fi
  if [[ "$abi" != "arm64-v8a" ]]; then
    if ! python3 "$repo_root/scripts/check-elf-exports.py" --undefined "$so" __emutls_get_address >/dev/null; then
      echo "Packaged bridge for $abi does not use emulated TLS" >&2
      exit 1
    fi
    if ! python3 "$repo_root/scripts/check-elf-exports.py" \
      "$work/jni/$abi/libc++_shared.so" __emutls_get_address >/dev/null; then
      echo "Packaged libc++_shared for $abi does not provide __emutls_get_address" >&2
      exit 1
    fi
  fi
done
echo "AAR ABI inventory is valid: $aar"
