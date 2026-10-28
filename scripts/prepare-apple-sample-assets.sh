#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cache="$(cache_root)/models"
archive_name=sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26.tar.bz2
archive="$cache/$archive_name"
model_root="$cache/sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26"

if [[ ! -f "$archive" ]]; then
  mkdir -p "$cache"
  gh_release_download asr-models k2-fsa/sherpa-onnx "$archive_name" "$cache"
fi

expected=e5369d24aeee14afac8277937e43ebaed20444a09e34de452387916293b07b9d
actual=$(sha256 "$archive")
if [[ "$actual" != "$expected" ]]; then
  echo "Unexpected test model checksum: $actual" >&2
  exit 1
fi

if [[ ! -d "$model_root" ]]; then
  tar -xjf "$archive" -C "$cache"
fi

dest="$repo_root/samples/apple/iOS/SherpaKmpSample/SherpaModels/models/nemo-ctc"
mkdir -p "$dest/test_wavs"
cp -f "$model_root/model.int8.onnx" "$dest/model.onnxdata"
cp -f "$model_root/tokens.txt" "$dest/tokens.txt"
cp -f "$model_root/test_wavs/0.wav" "$dest/test_wavs/0.wav"
du -sh "$dest"
