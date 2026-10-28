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
    gh_release_download asr-models k2-fsa/sherpa-onnx "$archive_name" "$cache"
  else
    download \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive_name" \
      "$archive" \
      e5369d24aeee14afac8277937e43ebaed20444a09e34de452387916293b07b9d
  fi
fi

actual=$(sha256 "$archive")
if [[ "$actual" != "e5369d24aeee14afac8277937e43ebaed20444a09e34de452387916293b07b9d" ]]; then
  echo "Unexpected test model checksum: $actual" >&2
  exit 1
fi

if [[ ! -d "$model_root" ]]; then
  mkdir -p "$cache"
  tar -xjf "$archive" -C "$cache"
fi

work="$repo_root/build/bridge-integration"
mkdir -p "$work"
options="$work/options.json"
cat > "$options" <<JSON
{
  "nemo-ctc-model": "$model_root/model.int8.onnx",
  "tokens": "$model_root/tokens.txt",
  "num-threads": "2",
  "debug": "false"
}
JSON

cli="$repo_root/build/native/portable-bridge/sherpa-onnx-portable-bridge-cli"
result=$("$cli" "$options" "$model_root/test_wavs/0.wav")


invalid_options="$work/invalid-options.json"
cat > "$invalid_options" <<JSON
{
  "nemo-ctc-model": "$model_root/model.int8.onnx",
  "tokens": "$model_root/tokens.txt",
  "num-threads": "not-an-integer"
}
JSON
if "$cli" "$invalid_options" "$model_root/test_wavs/0.wav" >/dev/null 2>&1; then
  echo "Bridge accepted an invalid integer option" >&2
  exit 1
fi

RESULT_JSON="$result" python3 - <<'PY'
import json
import os
result = json.loads(os.environ["RESULT_JSON"])
assert result["schemaVersion"] == 1
assert result["text"].strip()
assert result["tokens"]
assert result["rawJson"]
print(json.dumps({
    "schemaVersion": result["schemaVersion"],
    "text": result["text"],
    "tokenCount": len(result["tokens"]),
}, ensure_ascii=False))
PY
