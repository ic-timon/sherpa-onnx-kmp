#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Downloads the pinned offline-ASR model into the shared cache.
#
# The Apple and Android scripts do this as a side effect of copying the model
# into a sample or test-asset directory. The native desktop jobs need the cache
# entry itself, because `SHERPA_ONNX_KMP_TEST_MODEL_DIR` points at it directly,
# so the download lives here and those scripts keep their copy step.
#
# Prints nothing but progress; the caller already knows the cache layout.
set -euo pipefail

source "$(dirname "$0")/lib.sh"

cache="$(cache_root)/models"
archive_name=sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26.tar.bz2
archive="$cache/$archive_name"
model_root="$cache/sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26"
checksum=e5369d24aeee14afac8277937e43ebaed20444a09e34de452387916293b07b9d

mkdir -p "$cache"

if [[ ! -f "$archive" ]]; then
  if command -v gh >/dev/null 2>&1; then
    gh_release_download asr-models k2-fsa/sherpa-onnx "$archive_name" "$cache"
  else
    download \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive_name" \
      "$archive" \
      "$checksum"
  fi
fi

if [[ "$(sha256 "$archive")" != "$checksum" ]]; then
  echo "Unexpected test model checksum" >&2
  exit 1
fi

if [[ ! -d "$model_root" ]]; then
  tar -xjf "$archive" -C "$cache"
fi

printf 'Pinned model: %s\n' "$model_root"
