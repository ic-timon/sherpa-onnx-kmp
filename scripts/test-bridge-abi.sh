#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Exercises the bridge's real C ABI on the host.
#
# The unit suite covers the registry with test doubles. This check runs the CLI
# against the sherpa-onnx-backed library, which is what every transport ships,
# and asserts that capability discovery reports exactly the kinds that were
# compiled in, that a bad option is rejected without aborting, and that an
# unknown resource kind fails instead of segfaulting.
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

build_dir="${1:-$repo_root/build/native-test}"
cli="$build_dir/portable-bridge/sherpa-onnx-portable-bridge-cli"

if [[ ! -x "$cli" ]]; then
  echo "Bridge CLI is missing: $cli" >&2
  echo "Configure and build the native tree first." >&2
  exit 1
fi

work=$(mktemp -d)
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

# `--info` is not a CLI mode, so probe the library through a tiny C program.
# Keeping the probe in C (not C++) proves the header is a valid C interface.
cat > "$work/probe.c" <<'PROBE'
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sherpa-onnx-portable-bridge.h"

static int failures = 0;

static void check(int condition, const char *message) {
  if (!condition) {
    fprintf(stderr, "FAIL: %s\n", message);
    failures++;
  }
}

int main(void) {
  check(SherpaOnnxBridgeAbiVersion() == SHERPA_ONNX_BRIDGE_ABI_VERSION,
        "ABI version matches the header");

  char *info = SherpaOnnxBridgeInfoJson();
  check(info != NULL, "capability report is not null");
  if (info == NULL) {
    return 1;
  }
  check(strstr(info, "\"abiVersion\"") != NULL, "report has abiVersion");
  check(strstr(info, "\"resources\"") != NULL, "report has resources");
  check(strstr(info, "\"operations\"") != NULL, "report has operations");
  /* The offline path is compiled into every build. */
  check(strstr(info, "\"offline-asr\"") != NULL, "offline-asr is available");
  /* v0.7.0 kinds are compiled in. */
  check(strstr(info, "\"online-asr\"") != NULL, "online-asr is available");
  check(strstr(info, "\"vad\"") != NULL, "vad is available");
  check(strstr(info, "offline-asr.transcribe") != NULL,
        "offline-asr.transcribe is reported");
  check(strstr(info, "online-session.push-audio") != NULL,
        "online-session.push-audio is reported");
  check(strstr(info, "vad.accept-waveform") != NULL,
        "vad.accept-waveform is reported");
  /* v0.8.0 kinds are compiled in. */
  check(strstr(info, "\"tts\"") != NULL, "tts is available");
  check(strstr(info, "\"kws\"") != NULL, "kws is available");
  check(strstr(info, "\"punctuation\"") != NULL, "punctuation is available");
  check(strstr(info, "\"speaker-diarization\"") != NULL,
        "speaker-diarization is available");
  check(strstr(info, "\"speech-denoiser\"") != NULL,
        "speech-denoiser is available");
  check(strstr(info, "tts.synthesize") != NULL, "tts.synthesize is reported");
  check(strstr(info, "speaker-diarization.process") != NULL,
        "speaker-diarization.process is reported");
  printf("%s\n", info);
  SherpaOnnxBridgeFree(info);

  /* An unknown kind is a typed failure, not a crash. */
  uint64_t handle = 12345;
  char *error = NULL;
  int32_t status = SherpaOnnxBridgeCreate("nope", "{}", &handle, &error);
  check(status == SHERPA_ONNX_BRIDGE_UNSUPPORTED, "unknown kind is unsupported");
  check(handle == 0, "failed create leaves the handle at zero");
  check(error != NULL, "failed create reports a message");
  SherpaOnnxBridgeFree(error);

  /* A bad option is reported through TrySetOption instead of aborting. */
  handle = 0;
  error = NULL;
  status = SherpaOnnxBridgeCreate(
      "offline-asr", "{\"not-a-real-option\":\"x\"}", &handle, &error);
  check(status == SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
        "unknown option is an invalid argument");
  check(handle == 0, "invalid option leaves the handle at zero");
  check(error != NULL, "invalid option reports a message");
  SherpaOnnxBridgeFree(error);

  /* A missing model is a runtime failure from validation, not a crash. */
  handle = 0;
  error = NULL;
  status = SherpaOnnxBridgeCreate(
      "offline-asr", "{\"nemo-ctc-model\":\"/nope/model.onnx\","
                     "\"tokens\":\"/nope/tokens.txt\"}",
      &handle, &error);
  check(status != SHERPA_ONNX_BRIDGE_OK, "a missing model is rejected");
  check(handle == 0, "missing model leaves the handle at zero");
  if (error != NULL) {
    SherpaOnnxBridgeFree(error);
  }

  /* Destroying unknown handles is a no-op. */
  SherpaOnnxBridgeDestroy(0);
  SherpaOnnxBridgeDestroy(999999);

  if (failures != 0) {
    fprintf(stderr, "%d bridge ABI checks failed\n", failures);
    return 1;
  }
  printf("bridge ABI probe: OK\n");
  return 0;
}
PROBE

c_compiler="${CC:-cc}"
"$c_compiler" \
  -std=c11 \
  -I"$repo_root/native/portable-bridge/include" \
  "$work/probe.c" \
  -L"$build_dir/lib" \
  -lsherpa-onnx-portable-bridge \
  -Wl,-rpath,"$build_dir/lib" \
  -o "$work/probe"

"$work/probe"

# Real-model feature checks. These run the CLI's generic mode against the
# pinned feature models, which is the only place every bridge kind is proven to
# work end to end without the KMP harness. They are skipped when the feature
# models have not been downloaded, so the script still works on a bare host.
feature_root="${SHERPA_ONNX_KMP_FEATURE_MODEL_DIR:-}"
if [[ -z "$feature_root" ]]; then
  feature_root=$(./scripts/prepare-feature-models.sh --print)
fi

if [[ ! -d "$feature_root/vits-icefall-zh-aishell3" ]]; then
  printf 'feature models are absent in %s; skipping the real-model checks\n' \
    "$feature_root"
  printf 'bridge ABI + feature probe: OK (capability surface only)\n'
  exit 0
fi

# The icefall VITS model is the self-contained TTS path: it ships a lexicon, so
# it needs no external phonemizer. A synthesized sentence with real samples is
# the check that the whole pull-audio loop works across the C ABI.
icefall="$feature_root/vits-icefall-zh-aishell3"
cat > "$work/tts_options.json" <<JSON
{"vits-model":"$icefall/model.onnx","vits-tokens":"$icefall/tokens.txt","vits-lexicon":"$icefall/lexicon.txt"}
JSON
cat > "$work/tts_arguments.json" <<'JSON'
{"text":"你好世界"}
JSON

tts_output=$("$cli" tts "$work/tts_options.json" "$work/tts_arguments.json")
printf '%s\n' "$tts_output"
if ! printf '%s' "$tts_output" | grep -q 'totalSamples=[1-9]'; then
  echo "TTS produced no audio through the bridge" >&2
  exit 1
fi

# Every advertised kind must be present by name too, so a kind cannot be listed
# in `operations` without a resource that creates.
capabilities=$("$cli" info)
for kind in offline-asr online-asr vad tts kws punctuation \
            speaker-diarization speech-denoiser; do
  if ! printf '%s' "$capabilities" | grep -q "\"$kind\""; then
    echo "capability report omits $kind" >&2
    exit 1
  fi
done

printf 'bridge ABI + feature probe: OK\n'
