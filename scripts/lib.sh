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

# `gh release download` reaches api.github.com, which intermittently times out
# on hosted CI runners. Retry with linear backoff so a transient network error
# does not fail a whole workflow.
gh_release_download() {
  local tag=$1
  local repo=$2
  local pattern=$3
  local dir=$4
  local attempt=1
  local max_attempts="${SHERPA_ONNX_KMP_DOWNLOAD_ATTEMPTS:-4}"
  while true; do
    if gh release download "$tag" \
        --repo "$repo" \
        --pattern "$pattern" \
        --dir "$dir" \
        --clobber; then
      return 0
    fi
    if (( attempt >= max_attempts )); then
      echo "giving up on $pattern after $attempt attempts" >&2
      return 1
    fi
    echo "retrying $pattern (attempt $((attempt + 1))/$max_attempts) after a network error" >&2
    sleep "$(( attempt * 5 ))"
    attempt=$((attempt + 1))
  done
}
