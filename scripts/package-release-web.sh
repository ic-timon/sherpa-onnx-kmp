#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Packages the web bundle: the Emscripten module, the Web Worker, and the
# Kotlin/JS production bundle that drives them.
#
# The worker and the Emscripten glue are the public contract here. A consumer
# that installs the Kotlin/JS artifact also needs both files, and a release that
# ships only the `.mjs` bundle cannot run in a browser.
#
# Usage:
#   ./scripts/package-release-web.sh [version]
#
# Output: build/release/<version>/sherpa-onnx-kmp-web-<version>.zip
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

version="${1:-1.0.0}"
out="$repo_root/build/release/$version"
dist="$repo_root/samples/web/dist"
mkdir -p "$out"

if [[ ! -d "$dist" ]]; then
  echo "Web distribution is missing: $dist" >&2
  echo "Run ./scripts/build-web-emscripten.sh first." >&2
  exit 1
fi

# The whole point of the worker is that the page main thread never touches the
# Wasm heap. A bundle without the worker cannot demonstrate that, and one
# without the Wasm module cannot run at all.
for required in sherpa-onnx-bridge.js sherpa-onnx-bridge.wasm sherpa-onnx-worker.js; do
  if [[ ! -f "$dist/$required" ]]; then
    echo "Web bundle is missing: $dist/$required" >&2
    exit 1
  fi
done

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

root="$stage/sherpa-onnx-kmp-web-$version"
mkdir -p "$root"

# Copy the runtime files and the Kotlin/JS bundle. Skip `models/`: the sample
# page mounts its model there, and a release artifact must not carry test data.
for file in "$dist"/*; do
  name=$(basename "$file")
  [[ "$name" == "models" ]] && continue
  if [[ -d "$file" ]]; then
    cp -R "$file" "$root/$name"
  else
    cp -f "$file" "$root/$name"
  fi
done

cat > "$root/BUILD-INFO.txt" <<EOF
sherpa-onnx-kmp $version
platform: web
built-on: $(uname -s) $(uname -m)
built-at: $(date -u +%Y-%m-%dT%H:%M:%SZ)
commit: $(git rev-parse HEAD 2>/dev/null || echo unknown)
bridge-abi: $(grep -oE 'SHERPA_ONNX_BRIDGE_ABI_VERSION +[0-9]+' native/portable-bridge/include/sherpa-onnx-portable-bridge.h | grep -oE '[0-9]+$' || echo unknown)
EOF

archive="$out/sherpa-onnx-kmp-web-$version.zip"
rm -f "$archive"
make_zip "$archive" "$root"

summary="$out/sherpa-onnx-kmp-web-$version.zip.sha256"
(cd "$out" && sha256 "$(basename "$archive")" > "$(basename "$summary")")

printf 'Web archive: %s\n' "$archive"
printf 'Contents:\n'
list_zip "$archive"
cat "$summary"
