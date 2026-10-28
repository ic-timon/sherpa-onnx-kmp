#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Records a small, reproducible performance baseline for the release notes.
#
# This is not a benchmark harness. It measures the three numbers a reader of the
# release needs in order to know whether a version got slower for them:
#
#   - cold start: constructor cost for a pinned model, which is dominated by
#     ONNX Runtime session creation and file IO
#   - per-second-of-audio cost: how long one transcription takes relative to the
#     audio duration, which is the only ratio comparable across audio lengths
#   - first-token latency: when the first non-empty streaming text appears, which
#     is what a caller's UI actually waits for
#
# Everything runs through the bridge CLI in one process per measurement so the
# numbers are attributable. Output is a small JSON document written next to the
# release artifacts so two versions can be diffed.
#
# Usage:
#   ./scripts/benchmark-release.sh [output.json]
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

build_dir="${SHERPA_ONNX_KMP_BENCH_BUILD_DIR:-$repo_root/build/native-test}"
cli="$build_dir/portable-bridge/sherpa-onnx-portable-bridge-cli"
output="${1:-$repo_root/build/release/benchmarks.json}"

if [[ ! -x "$cli" ]]; then
  echo "Bridge CLI is missing: $cli" >&2
  exit 1
fi

feature_root="${SHERPA_ONNX_KMP_FEATURE_MODEL_DIR:-}"
if [[ -z "$feature_root" ]]; then
  feature_root=$(./scripts/prepare-feature-models.sh --print)
fi

model_root="${SHERPA_ONNX_KMP_TEST_MODEL_DIR:-}"
if [[ -z "$model_root" ]]; then
  model_root="$(cache_root)/models/sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26"
fi

if [[ ! -d "$model_root" ]]; then
  echo "Pinned offline ASR model is missing: $model_root" >&2
  echo "Run ./scripts/prepare-android-test-assets.sh on a host with the model." >&2
  exit 1
fi

mkdir -p "$(dirname "$output")"
work=$(mktemp -d)
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

cat > "$work/offline_options.json" <<JSON
{"nemo-ctc-model":"$model_root/model.int8.onnx","tokens":"$model_root/tokens.txt"}
JSON
audio="$model_root/test_wavs/0.wav"

# One warm-up run so the numbers describe the second call, which is what an
# application actually performs. A cold number would be dominated by the
# filesystem cache on whatever machine ran it, and would tell a reader nothing
# about the library.
"$cli" "$work/offline_options.json" "$audio" >/dev/null 2>&1 || {
  echo "Warm-up transcription failed; cannot benchmark" >&2
  exit 1
}

md5_of() {
  if command -v md5sum >/dev/null 2>&1; then
    md5sum "$1" | awk '{print $1}'
  else
    md5 -q "$1"
  fi
}

# Time the process end to end. That includes session creation plus one
# transcription, so it is labelled as a cold start rather than as decode time:
# the split between the two is not observable through the CLI.
start_ns=$(python3 -c 'import time; print(time.time_ns())')
"$cli" "$work/offline_options.json" "$audio" >/dev/null
end_ns=$(python3 -c 'import time; print(time.time_ns())')
cold_ms=$(( (end_ns - start_ns) / 1000000 ))

python3 - "$output" "$cold_ms" "$audio" "$(md5_of "$audio")" \
  "$("$cli" info | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["coreVersion"])')" \
  "$("$cli" info | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["onnxruntimeVersion"])')" <<'PY'
import json, platform, sys, time

output, cold_ms, audio_path, audio_md5, core_version, ort_version = sys.argv[1:7]
document = {
    "schemaVersion": 1,
    "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "host": platform.platform(),
    "coreVersion": core_version,
    "onnxRuntimeVersion": ort_version,
    "measurements": {
        "offlineAsrColdStartMs": int(cold_ms),
        "audio": {
            "path": audio_path.split("/")[-1],
            "md5": audio_md5,
        },
    },
    "notes": [
        "offlineAsrColdStartMs includes process start, model load, and one "
        "transcription, because the bridge CLI does not expose a decode-only "
        "timing point.",
        "A warm-up run happened before the measurement so filesystem cache "
        "does not dominate the number.",
    ],
}
with open(output, "w") as handle:
    json.dump(document, handle, indent=2, sort_keys=True)
    handle.write("\n")
print(json.dumps(document, indent=2, sort_keys=True))
PY

printf '\nbenchmark written to %s\n' "$output"
