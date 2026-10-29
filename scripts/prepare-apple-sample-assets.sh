#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
"$repo_root/scripts/prepare-pinned-model.sh"
cache="$(cache_root)/models"
model_root="$cache/sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26"

dest="$repo_root/samples/apple/iOS/SherpaKmpSample/SherpaModels/models/nemo-ctc"
mkdir -p "$dest/test_wavs"
cp -f "$model_root/model.int8.onnx" "$dest/model.onnxdata"
cp -f "$model_root/tokens.txt" "$dest/tokens.txt"
cp -f "$model_root/test_wavs/0.wav" "$dest/test_wavs/0.wav"
du -sh "$dest"
