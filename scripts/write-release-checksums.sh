#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Writes the release's top-level SHA256SUMS from whatever artifacts are present
# in build/release/<version>.
#
# Every platform slice is packaged on its own runner, so the manifest is
# regenerated last and must cover all of them. Failing when an expected slice is
# missing is deliberate: a manifest that quietly omits Windows is how a release
# ends up claiming coverage it does not have.
#
# Usage:
#   ./scripts/write-release-checksums.sh [version] [--allow-partial]
#
# --allow-partial permits a subset, for local packaging on one host. The release
# job never passes it.
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)

version="1.0.0"
allow_partial=0
for arg in "$@"; do
  case "$arg" in
    --allow-partial) allow_partial=1 ;;
    -*) echo "Unknown option: $arg" >&2; exit 2 ;;
    *) version="$arg" ;;
  esac
done

out="$repo_root/build/release/$version"
if [[ ! -d "$out" ]]; then
  echo "Release directory is missing: $out" >&2
  exit 1
fi

cd "$out"

# Artifact sets that must all exist for a complete release. Each entry is a glob;
# a glob with no match is a missing slice.
required=(
  "sherpa-onnx-kmp-android-$version.aar"
  "sherpa-onnx-kmp-jvm-$version.jar"
  "sherpa-onnx-kmp-jvm-native-linux-x64-$version.jar"
  "sherpa-onnx-kmp-jvm-native-linux-aarch64-$version.jar"
  "sherpa-onnx-kmp-jvm-native-osx-aarch64-$version.jar"
  "sherpa-onnx-kmp-jvm-native-windows-x64-$version.jar"
  "SherpaOnnxKmp.xcframework.zip"
  "sherpa-onnx-kmp-linux-x64-$version.zip"
  "sherpa-onnx-kmp-linux-arm64-$version.zip"
  "sherpa-onnx-kmp-windows-x64-$version.zip"
  "sherpa-onnx-kmp-web-$version.zip"
)

missing=()
for pattern in "${required[@]}"; do
  # shellcheck disable=SC2206
  matches=($pattern)
  if [[ ! -e "${matches[0]}" ]]; then
    missing+=("$pattern")
  fi
done

if (( ${#missing[@]} > 0 )); then
  if (( allow_partial )); then
    echo "Packaging a partial release; missing:" >&2
    printf '  %s\n' "${missing[@]}" >&2
  else
    echo "Release is incomplete; missing:" >&2
    printf '  %s\n' "${missing[@]}" >&2
    echo "Build every platform before writing the manifest, or pass --allow-partial." >&2
    exit 1
  fi
fi

# Hash only artifacts, never the manifest itself and never the per-slice
# .sha256 sidecars that were written next to their archives. `find -printf` and
# `mapfile` are GNU-only, and the release is also packaged on macOS.
files=()
for pattern in ./*.aar ./*.jar ./*.zip ./*.tar.gz; do
  [[ -e "$pattern" ]] || continue
  files+=("$(basename "$pattern")")
done

if (( ${#files[@]} == 0 )); then
  echo "No artifacts to hash in $out" >&2
  exit 1
fi

sorted=$(printf '%s\n' "${files[@]}" | sort)
rm -f SHA256SUMS
while IFS= read -r file; do
  [[ -n "$file" ]] || continue
  sha256 "$file" | awk -v name="$file" '{print $1"  "name}' >> SHA256SUMS
done <<< "$sorted"

printf 'Release manifest (%s):\n' "$version"
cat SHA256SUMS
