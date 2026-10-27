#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cache="$(cache_root)/upstream"
archive="$cache/sherpa-onnx-v2.0.1.tar.gz"
source_dir="$repo_root/build/upstream/sherpa-onnx"
patch_file="$repo_root/patches/0001-parse-options-try-set-option.patch"

download \
  https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/refs/tags/v2.0.1 \
  "$archive" \
  24ecc0fccf6344c2556e7c229c849e9f61bf9456b915615197d85f35e3f177e7

if [[ ! -d "$source_dir" ]]; then
  mkdir -p "$source_dir"
  tar -xzf "$archive" --strip-components=1 -C "$source_dir"
fi

if ! grep -q "TrySetOption" "$source_dir/sherpa-onnx/csrc/parse-options.h"; then
  patch -d "$source_dir" -p1 < "$patch_file"
fi

printf '%s\n' "$source_dir"
