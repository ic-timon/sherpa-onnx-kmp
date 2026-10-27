#!/usr/bin/env bash
set -euo pipefail

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

download() {
  local url=$1
  local output=$2
  local expected=$3
  mkdir -p "$(dirname "$output")"
  if [[ -f "$output" && "$(sha256 "$output")" == "$expected" ]]; then
    return
  fi
  curl --http1.1 -L --fail --retry 8 --retry-all-errors \
    --max-time 300 -o "$output" "$url"
  local actual
  actual=$(sha256 "$output")
  if [[ "$actual" != "$expected" ]]; then
    echo "Checksum mismatch for $output" >&2
    echo "expected: $expected" >&2
    echo "actual:   $actual" >&2
    exit 1
  fi
}

cache_root() {
  printf '%s\n' "${XDG_CACHE_HOME:-$HOME/.cache}/sherpa-onnx-kmp"
}
