#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Release-hardening checks (0.9.0).
#
# Three things are verified here that no behavioral test can express:
#
# 1. Repeated create/destroy does not grow native memory without bound. A leak
#    in the handle registry or in a backend's own state is invisible to a
#    functional test because each iteration closes what it opened.
# 2. The bridge reports the same ABI version the header declares, so a
#    transport pinned to one version fails fast instead of misreading a struct.
# 3. The prebuilt library does not depend on a path outside the release bundle.
#
# Usage:
#   ./scripts/check-release-hardening.sh [build-dir]
#
# The memory check needs the sherpa-onnx-backed library and at least one real
# model; it skips itself when the feature models are absent.
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

build_dir="${1:-}"
if [[ -z "$build_dir" ]]; then
  for candidate in "$repo_root/build/native" "$repo_root/build/native-test"; do
    if [[ -x "$candidate/portable-bridge/sherpa-onnx-portable-bridge-cli" ]]; then
      build_dir="$candidate"
      break
    fi
  done
fi
build_dir="${build_dir:-$repo_root/build/native}"
lib_dir="$build_dir/lib"
cli="$build_dir/portable-bridge/sherpa-onnx-portable-bridge-cli"

if [[ ! -d "$lib_dir" ]]; then
  echo "Native build directory is missing: $lib_dir" >&2
  exit 1
fi

feature_root="${SHERPA_ONNX_KMP_FEATURE_MODEL_DIR:-}"
if [[ -z "$feature_root" ]]; then
  feature_root=$(./scripts/prepare-feature-models.sh --print)
fi

work=$(mktemp -d)
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

printf '== header and library ABI agreement ==\n'
header_version=$(awk '/#define SHERPA_ONNX_BRIDGE_ABI_VERSION/ {print $3}' \
  native/portable-bridge/include/sherpa-onnx-portable-bridge.h)
if [[ -z "$header_version" ]]; then
  echo "Could not read the ABI version from the header" >&2
  exit 1
fi
runtime_version=$("$cli" info | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["abiVersion"])')
if [[ "$header_version" != "$runtime_version" ]]; then
  echo "Header says ABI $header_version but the library reports $runtime_version" >&2
  exit 1
fi
printf 'ABI %s agreed by the header and the library\n' "$header_version"

printf '\n== dependency audit ==\n'
case "$(uname -s)" in
  Darwin)
    for library in "$lib_dir"/*.dylib; do
      # A dependency outside the system prefixes has to ship with the library;
      # one pointing at a builder's home directory is a broken release.
      if otool -L "$library" | tail -n +2 | grep -vE \
          '/usr/lib/|/System/Library/|@rpath/|@loader_path/' |
          grep -qE '/Users/|/home/'; then
        echo "Absolute builder path in $(basename "$library"):" >&2
        otool -L "$library" | tail -n +2 >&2
        exit 1
      fi
    done
    ;;
  Linux)
    for library in "$lib_dir"/*.so; do
      if readelf -d "$library" | grep NEEDED |
          grep -qE '/Users/|/home/'; then
        echo "Absolute builder path in $(basename "$library"):" >&2
        readelf -d "$library" >&2
        exit 1
      fi
    done
    ;;
esac
printf 'no builder paths leak into the release libraries\n'

if [[ ! -x "$cli" ]] || [[ ! -d "$feature_root/vits-icefall-zh-aishell3" ]]; then
  printf '\nfeature models or the bridge CLI are absent; skipping the memory check\n'
  printf 'release hardening: OK (ABI and dependency audit only)\n'
  exit 0
fi

if [[ "${SHERPA_ONNX_KMP_LEAK_FAST:-0}" == "1" ]]; then
  printf '\nSHERPA_ONNX_KMP_LEAK_FAST is set; skipping the memory-growth run\n'
  printf 'release hardening: OK (ABI and dependency audit only)\n'
  exit 0
fi

printf '\n== repeated create/destroy memory growth ==\n'
icefall="$feature_root/vits-icefall-zh-aishell3"
cat > "$work/tts_options.json" <<JSON
{"vits-model":"$icefall/model.onnx","vits-tokens":"$icefall/tokens.txt","vits-lexicon":"$icefall/lexicon.txt"}
JSON
cat > "$work/tts_arguments.json" <<'JSON'
{"text":"你好世界"}
JSON

# A separate process per measurement is the only way to compare resident size
# honestly: an in-process loop keeps allocator arenas alive even after free, and
# that plateau would hide a real leak. Each probe synthesizes a fixed number of
# times and then exits, so its peak RSS is attributable to that workload.
#
# The comparison deliberately starts after a warm-up point. ONNX Runtime keeps
# its own arena, and a model's first few syntheses raise peak RSS from about
# 135 MB to a plateau near 240 MB on this workload. Measuring 1-vs-N would
# report that arena as growth on every run. Two warm measurements instead
# separate "the arena reached its size" from "each iteration adds memory", which
# is the only distinction that matters for a release.
warmup_iterations="${SHERPA_ONNX_KMP_LEAK_WARMUP:-384}"
steady_iterations="${SHERPA_ONNX_KMP_LEAK_ITERATIONS:-768}"

cat > "$work/probe.py" <<'PY'
import ctypes, json, os, resource, sys

library, options_path, arguments_path, iterations = sys.argv[1:5]
iterations = int(iterations)

lib = ctypes.CDLL(library)
lib.SherpaOnnxBridgeCreate.argtypes = [
    ctypes.c_char_p, ctypes.c_char_p,
    ctypes.POINTER(ctypes.c_uint64), ctypes.POINTER(ctypes.c_char_p)]
lib.SherpaOnnxBridgeCreate.restype = ctypes.c_int32
lib.SherpaOnnxBridgeCall.argtypes = [
    ctypes.c_uint64, ctypes.c_char_p, ctypes.c_char_p,
    ctypes.POINTER(ctypes.c_char_p), ctypes.POINTER(ctypes.c_char_p)]
lib.SherpaOnnxBridgeCall.restype = ctypes.c_int32
lib.SherpaOnnxBridgePullAudio.argtypes = [
    ctypes.c_uint64, ctypes.c_char_p, ctypes.c_char_p,
    ctypes.POINTER(ctypes.c_int32), ctypes.POINTER(ctypes.POINTER(ctypes.c_float)),
    ctypes.POINTER(ctypes.c_int32),
    ctypes.POINTER(ctypes.c_char_p), ctypes.POINTER(ctypes.c_char_p)]
lib.SherpaOnnxBridgePullAudio.restype = ctypes.c_int32
lib.SherpaOnnxBridgeFree.argtypes = [ctypes.c_char_p]
lib.SherpaOnnxBridgeFreeAudio.argtypes = [ctypes.POINTER(ctypes.c_float)]
lib.SherpaOnnxBridgeDestroy.argtypes = [ctypes.c_uint64]

options = open(options_path).read()
arguments = open(arguments_path).read()

for _ in range(iterations):
    handle = ctypes.c_uint64(0)
    error = ctypes.c_char_p()
    status = lib.SherpaOnnxBridgeCreate(
        b"tts", options.encode(), ctypes.byref(handle), ctypes.byref(error))
    if status != 0:
        print(f"create failed: {status} {error.value!r}", file=sys.stderr)
        sys.exit(1)
    result = ctypes.c_char_p()
    error = ctypes.c_char_p()
    status = lib.SherpaOnnxBridgeCall(
        handle.value, b"tts.synthesize", arguments.encode(),
        ctypes.byref(result), ctypes.byref(error))
    if status != 0:
        print(f"synthesize failed: {status} {error.value!r}", file=sys.stderr)
        sys.exit(1)
    lib.SherpaOnnxBridgeFree(result)
    while True:
        sample_rate = ctypes.c_int32(0)
        samples = ctypes.POINTER(ctypes.c_float)()
        count = ctypes.c_int32(0)
        meta = ctypes.c_char_p()
        error = ctypes.c_char_p()
        status = lib.SherpaOnnxBridgePullAudio(
            handle.value, b"tts.take-chunk", b"{}", ctypes.byref(sample_rate),
            ctypes.byref(samples), ctypes.byref(count), ctypes.byref(meta),
            ctypes.byref(error))
        if status != 0:
            print(f"pull failed: {status} {error.value!r}", file=sys.stderr)
            sys.exit(1)
        last = meta.value is not None and b'"isLast":true' in meta.value
        lib.SherpaOnnxBridgeFreeAudio(samples)
        lib.SherpaOnnxBridgeFree(meta)
        if last:
            break
    lib.SherpaOnnxBridgeDestroy(handle.value)

peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
# ru_maxrss is bytes on macOS and kilobytes on Linux. Normalize to KiB so the
# comparison and the budgets mean the same thing on every runner.
if sys.platform == "darwin":
    peak //= 1024
print(peak)
PY

measure() {
  local count=$1
  local library="$lib_dir/libsherpa-onnx-portable-bridge.dylib"
  if [[ ! -f "$library" ]]; then
    library="$lib_dir/libsherpa-onnx-portable-bridge.so"
  fi
  DYLD_LIBRARY_PATH="$lib_dir" LD_LIBRARY_PATH="$lib_dir" \
    python3 "$work/probe.py" "$library" "$work/tts_options.json" \
      "$work/tts_arguments.json" "$count"
}

baseline=$(measure "$warmup_iterations")
loaded=$(measure "$steady_iterations")

printf 'peak RSS: %s iterations = %s, %s iterations = %s\n' \
  "$warmup_iterations" "$baseline" "$steady_iterations" "$loaded"

# Both measurements must be past the point where ONNX Runtime's arena has
# reached its nominal size. Measured on the development host (macOS arm64, VITS
# icefall zh, one sentence per iteration, peak RSS in KiB):
#
#     32 -> 128 iterations     +31424 KB    +327 KB/iteration
#    128 -> 384 iterations     +40592 KB    +158 KB/iteration
#    512 -> 1024 iterations    -34000 KB     -66 KB/iteration
#
# The growth rate falls and then goes negative: that is arena reuse and
# reuse-order variance, not a per-iteration leak. Measuring 1-vs-N would report
# the arena itself as growth on every run, which is why the defaults start far
# enough in that the signal is "does it keep growing", not "did it allocate".
#
# The per-iteration budget is what a leak scales with. The total budget is a
# ceiling on the whole comparison so a small iteration delta cannot pass by
# dividing a large jump by a small divisor; it is wider than the per-iteration
# budget times the delta because plateau noise is tens of MB.
#
# The defaults are deliberately long because the plateau is the signal. Set
# SHERPA_ONNX_KMP_LEAK_FAST=1 for a quicker smoke run that only checks ABI and
# dependencies, which is what the pull-request CI job does.
budget_per_iteration_kb="${SHERPA_ONNX_KMP_LEAK_ITERATION_BUDGET_KB:-512}"
budget_total_kb="${SHERPA_ONNX_KMP_LEAK_BUDGET_KB:-98304}"
growth=$((loaded - baseline))
per_iteration=$((growth / (steady_iterations - warmup_iterations)))
printf 'growth from %s to %s iterations: %s KB (%s KB per iteration)\n' \
  "$warmup_iterations" "$steady_iterations" "$growth" "$per_iteration"
printf 'budgets: %s KB per iteration, %s KB total\n' \
  "$budget_per_iteration_kb" "$budget_total_kb"
if [[ "$growth" -gt "$budget_total_kb" ]]; then
  echo "Repeated create/destroy grew native memory beyond the total budget" >&2
  exit 1
fi
if [[ "$per_iteration" -gt "$budget_per_iteration_kb" ]]; then
  echo "Repeated create/destroy grew native memory per iteration" >&2
  exit 1
fi

printf '\nrelease hardening: OK\n'
