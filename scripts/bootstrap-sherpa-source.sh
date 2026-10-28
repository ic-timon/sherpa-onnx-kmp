#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cache="$(cache_root)/upstream"
archive="$cache/sherpa-onnx-v2.0.1.tar.gz"
source_dir="$repo_root/build/upstream/sherpa-onnx"
patch_file="$repo_root/patches/0001-parse-options-try-set-option.patch"
# `thread_local` in normal-data-generator.cc requires __tls_get_addr, which
# Bionic does not export below API 29. Removing the TLS reference is cheaper
# than carrying an emulated-TLS flag through every Android build; the patch is
# also a genuine upstream portability fix.
tls_patch="$repo_root/patches/0002-normal-data-generator-avoid-thread-local.patch"

download \
  https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/refs/tags/v2.0.1 \
  "$archive" \
  24ecc0fccf6344c2556e7c229c849e9f61bf9456b915615197d85f35e3f177e7

if [[ ! -d "$source_dir" ]]; then
  mkdir -p "$source_dir"
  # Windows toolchains cannot create the symlinks that exist in upstream
  # example and script directories. Only the library sources are needed, so a
  # partial extraction is acceptable as long as the core headers and sources
  # landed. The verification below is the real gate.
  tar -xzf "$archive" --strip-components=1 -C "$source_dir" || true
fi

if [[ ! -f "$source_dir/sherpa-onnx/csrc/parse-options.h" ]]; then
  echo "Extracted sherpa-onnx source is incomplete: $source_dir" >&2
  echo "Retry after removing $source_dir" >&2
  exit 1
fi

if ! grep -q "TrySetOption" "$source_dir/sherpa-onnx/csrc/parse-options.h"; then
  patch -d "$source_dir" -p1 < "$patch_file"
fi

if grep -q "static thread_local RNGHolder" \
    "$source_dir/sherpa-onnx/csrc/normal-data-generator.cc"; then
  patch -d "$source_dir" -p1 < "$tls_patch"
fi

printf '%s\n' "$source_dir"
