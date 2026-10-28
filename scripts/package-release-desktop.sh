#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Packages the Linux and Windows native libraries into per-platform archives
# that attach to the same release as the AAR, the JARs, and the XCFramework.
#
# These cannot be produced on one host: the Linux bridge needs the glibc that
# the build machine ships, and the Windows bridge is an MSVC DLL. Each CI runner
# packages its own slice and the release job merges the resulting archives.
#
# Usage:
#   ./scripts/package-release-desktop.sh <linux-x64|linux-arm64|windows-x64> [version]
#
# Output: build/release/<version>/sherpa-onnx-kmp-<platform>-<version>.zip
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

platform="${1:-}"
version="${2:-1.0.0}"

case "$platform" in
  linux-x64 | linux-arm64 | windows-x64) ;;
  *)
    echo "Usage: $0 <linux-x64|linux-arm64|windows-x64> [version]" >&2
    exit 2
    ;;
esac

out="$repo_root/build/release/$version"
src="$repo_root/build/native-desktop/$platform"
mkdir -p "$out"

if [[ ! -d "$src" ]]; then
  echo "Native desktop build is missing: $src" >&2
  echo "Run ./scripts/build-native-desktop.sh $platform first." >&2
  exit 1
fi

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

root="$stage/sherpa-onnx-kmp-$platform-$version"
mkdir -p "$root/lib" "$root/include"

# Every platform publishes the public C header, so a consumer can compile
# against the same contract the libraries were built from.
header=native/portable-bridge/include/sherpa-onnx-portable-bridge.h
if [[ ! -f "$header" ]]; then
  echo "Bridge header is missing: $header" >&2
  exit 1
fi
cp -f "$header" "$root/include/"

case "$platform" in
  linux-*)
    # The SONAME copy is what the KMP library records as a DT_NEEDED entry; the
    # alias is what the Kotlin/Native cinterop links against. Both ship, plus
    # the ONNX Runtime they resolve at runtime.
    for lib in libsherpa-onnx-kmp-native-desktop.so \
               libsherpa-onnx-portable-bridge.so \
               libonnxruntime.so; do
      if [[ ! -f "$src/lib/$lib" ]]; then
        echo "Missing Linux library: $src/lib/$lib" >&2
        exit 1
      fi
      cp -f "$src/lib/$lib" "$root/lib/"
    done
    ;;
  windows-x64)
    if [[ ! -f "$src/lib/sherpa-onnx-portable-bridge.dll" ]]; then
      echo "Missing Windows DLL: $src/lib/sherpa-onnx-portable-bridge.dll" >&2
      exit 1
    fi
    cp -f "$src/lib/sherpa-onnx-portable-bridge.dll" "$root/lib/"
    # The import library lets a Kotlin/Native or C consumer link without
    # LoadLibrary/GetProcAddress.
    for implib in "$src"/lib/*.dll.a "$src"/lib/*.lib; do
      [[ -f "$implib" ]] && cp -f "$implib" "$root/lib/"
    done
    ;;
esac

# A library that still points at the build machine is a broken artifact, not a
# portability caveat. This is the same invariant check-release-hardening.sh
# asserts for the host build. `readelf` only exists on Linux, and this slice is
# only ever packaged on a Linux runner, so its absence is an error rather than a
# silent skip.
if [[ "$platform" == linux-* ]]; then
  if ! command -v readelf >/dev/null 2>&1; then
    echo "readelf is required to audit Linux libraries; install binutils" >&2
    exit 1
  fi
  for lib in "$root"/lib/*.so; do
    if readelf -d "$lib" 2>/dev/null | grep -qE 'RPATH|RUNPATH'; then
      paths=$(readelf -d "$lib" | grep -E 'RPATH|RUNPATH')
      case "$paths" in
        *"/home/"* | *"/Users/"* | *"/runner/"*)
          echo "Release library $lib leaks a build path: $paths" >&2
          exit 1
          ;;
      esac
    fi
  done
fi

# Record the exact provenance of this slice next to the binaries.
cat > "$root/BUILD-INFO.txt" <<EOF
sherpa-onnx-kmp $version
platform: $platform
built-on: $(uname -s) $(uname -m)
built-at: $(date -u +%Y-%m-%dT%H:%M:%SZ)
commit: $(git rev-parse HEAD 2>/dev/null || echo unknown)
bridge-abi: $(grep -oE 'SHERPA_ONNX_BRIDGE_ABI_VERSION +[0-9]+' "$header" | grep -oE '[0-9]+$' || echo unknown)
EOF

archive="$out/sherpa-onnx-kmp-$platform-$version.zip"
rm -f "$archive"
(
  cd "$stage"
  zip -q -r -X "$archive" "$(basename "$root")"
)

summary="$out/sherpa-onnx-kmp-$platform-$version.zip.sha256"
(cd "$out" && sha256 "$(basename "$archive")" > "$(basename "$summary")")

printf 'Desktop archive: %s\n' "$archive"
printf 'Contents:\n'
unzip -l "$archive" | sed -n '4,$p'
cat "$summary"
