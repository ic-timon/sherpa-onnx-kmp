#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Downloads the small pinned models used by the feature integration tests.
#
# The main ASR suite uses one Persian NeMo CTC archive. Every other feature
# needs its own model, and most of those are only a few megabytes, so they are
# fetched here instead of being re-downloaded by each platform script.
#
# Usage:
#   ./scripts/prepare-feature-models.sh            # download what is missing
#   ./scripts/prepare-feature-models.sh --print    # print the resolved root
set -euo pipefail

source "$(dirname "$0")/lib.sh"

root="$(cache_root)/feature-models"
mkdir -p "$root"

if [[ "${1:-}" == "--print" ]]; then
  printf '%s\n' "$root"
  exit 0
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "gh is required to download the feature models" >&2
  exit 1
fi

fetch() {
  local tag=$1
  local name=$2
  if [[ -f "$root/$name" ]]; then
    # A download interrupted mid-flight leaves a truncated archive that `gh`
    # would otherwise treat as cached. Verify it before trusting it.
    if [[ "$name" == *.tar.bz2 ]] && ! tar -tjf "$root/$name" >/dev/null 2>&1; then
      printf 'discarding a truncated archive: %s\n' "$name" >&2
      rm -f "$root/$name"
    else
      printf 'cached: %s\n' "$name"
      return
    fi
  fi
  printf 'downloading: %s\n' "$name"
  gh_release_download "$tag" k2-fsa/sherpa-onnx "$name" "$root"
}

# Silero VAD: 643 KB. `asr-models` carries the ONNX file directly, plus a
# 1.9 MB speech sample the VAD test feeds through the detector.
fetch asr-models silero_vad.onnx
fetch asr-models test_silero_vad.wav

# Keyword spotting: a 3.3 MB transducer with its own keywords file.
fetch kws-models sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2

# Punctuation: CT-Transformer, int8.
fetch punctuation-models \
  sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2

# Speech enhancement: GTCRN, a few hundred kilobytes.
fetch speech-enhancement-models gtcrn_simple.onnx
# Streaming ASR: 20M Zipformer transducer, English.
fetch asr-models sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2

# TTS, path one: an icefall Chinese VITS that ships `lexicon.txt`, so it needs
# no external phonemizer. sherpa-onnx v2.0.0 removed the built-in espeak-ng
# phonemizer, which makes this the only self-contained VITS path.
fetch tts-models vits-icefall-zh-aishell3.tar.bz2

# TTS, path two: a Piper VITS that needs caller-supplied phoneme codepoints.
# Kept because it is the model the phonemizer-free failure and the
# `phonemeCodepoints` success path are both asserted against.
fetch tts-models vits-piper-en_US-amy-low.tar.bz2

# Speaker diarization: Pyannote segmentation plus a 3D-Speaker embedding, and
# the two-speaker English clip the turn assertions run on.
fetch speaker-segmentation-models \
  sherpa-onnx-pyannote-segmentation-3-0.tar.bz2
fetch speaker-segmentation-models 2-two-speakers-en.wav
fetch speaker-recongition-models \
  3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx

# Expand every archive once. `tar.bz2` is idempotent here because the archives
# extract into their own directory and an existing directory is skipped. A
# partial extraction from an earlier interrupted run leaves a directory with
# missing members, so the marker is the archive's own file list rather than the
# directory's existence.
shopt -s nullglob
for archive in "$root"/*.tar.bz2; do
  name=$(basename "$archive" .tar.bz2)
  if [[ -d "$root/$name" && -f "$root/$name/tokens.txt" ]] ||
     [[ -d "$root/$name" && -f "$root/$name/model.onnx" ]] ||
     [[ -d "$root/$name" && -f "$root/$name/model.int8.onnx" ]]; then
    continue
  fi
  printf 'extracting: %s\n' "$name"
  rm -rf "$root/$name"
  tar -xjf "$archive" -C "$root"
done
shopt -u nullglob

cat > "$root/manifest.txt" <<MANIFEST
# Pinned feature models used by the integration suites.
# Regenerate with ./scripts/prepare-feature-models.sh
silero_vad.onnx
sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8
gtcrn_simple.onnx
sherpa-onnx-streaming-zipformer-en-20M-2023-02-17
vits-icefall-zh-aishell3
vits-piper-en_US-amy-low
sherpa-onnx-pyannote-segmentation-3-0
3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx
2-two-speakers-en.wav
MANIFEST

printf 'Feature models: %s\n' "$root"
