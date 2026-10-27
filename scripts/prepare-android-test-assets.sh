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
  if command -v gh >/dev/null 2>&1; then
    gh release download asr-models \
      --repo k2-fsa/sherpa-onnx \
      --pattern "$archive_name" \
      --dir "$cache" \
      --clobber
  else
    download \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive_name" \
      "$archive" \
      e5369d24aeee14afac8277937e43ebaed20444a09e34de452387916293b07b9d
  fi
fi

if [[ "$(sha256 "$archive")" != "e5369d24aeee14afac8277937e43ebaed20444a09e34de452387916293b07b9d" ]]; then
  echo "Unexpected test model checksum" >&2
  exit 1
fi

if [[ ! -d "$model_root" ]]; then
  mkdir -p "$cache"
  tar -xjf "$archive" -C "$cache"
fi

dest="$repo_root/sherpa-onnx-kmp/src/androidDeviceTest/assets/models/nemo-ctc"
mkdir -p "$dest/test_wavs"
cp -f "$model_root/model.int8.onnx" "$dest/model.int8.onnx"
cp -f "$model_root/tokens.txt" "$dest/tokens.txt"
cp -f "$model_root/test_wavs/0.wav" "$dest/test_wavs/0.wav"
du -sh "$dest"
