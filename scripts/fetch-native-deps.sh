#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib.sh"

cache="$(cache_root)/deps"
mkdir -p "$cache" /tmp

fetch() {
  local name=$1
  local url=$2
  local hash=$3
  download "$url" "$cache/$name" "$hash"
  cp -f "$cache/$name" "/tmp/$name"
}

fetch kaldi-native-fbank-1.22.4.tar.gz \
  https://codeload.github.com/csukuangfj/kaldi-native-fbank/tar.gz/refs/tags/v1.22.4 \
  257fea639585ca6ee00860493bb9d7ab32488b8a5407ffe434c1f361ea545fae

fetch kissfft-febd4caeed32e33ad8b2e0bb5ea77542c40f18ec.zip \
  https://codeload.github.com/mborgerding/kissfft/zip/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec \
  497103e664168ebe39580b757adbe616f6cf85a16572af581ca7bc42d0ab13fd

fetch utf8proc-2.12.0.tar.gz \
  https://codeload.github.com/JuliaStrings/utf8proc/tar.gz/refs/tags/v2.12.0 \
  f564011d38b2888d583d510b08e69ffa15aa117155db1b9b49ef1dfe1fa25111

fetch kaldi-decoder-0.3.0.tar.gz \
  https://codeload.github.com/k2-fsa/kaldi-decoder/tar.gz/refs/tags/v0.3.0 \
  b9f34cfb4fd3b1344100eead79ef4d37aa15962274b9e3056de345021f76a1b0

fetch kaldifst-1.8.0.tar.gz \
  https://codeload.github.com/k2-fsa/kaldifst/tar.gz/refs/tags/v1.8.0 \
  3f247b7e5a2409071202f5e2bc6200060f66728c0a3443c03923ad2723e040b3

fetch openfst-1.8.5-2026-07-09.tar.gz \
  https://codeload.github.com/csukuangfj/openfst/tar.gz/refs/tags/v1.8.5-2026-07-09 \
  2ff712a32952fcb01d351121a6bc8ccf4fdc6b2aa06ce8df2b3095dedd518c0e

fetch simple-sentencepiece-0.7.tar.gz \
  https://codeload.github.com/pkufool/simple-sentencepiece/tar.gz/refs/tags/v0.7 \
  1748a822060a35baa9f6609f84efc8eb54dc0e74b9ece3d82367b7119fdc75af

fetch json-3.12.0.tar.gz \
  https://codeload.github.com/nlohmann/json/tar.gz/refs/tags/v3.12.0 \
  4b92eb0c06d10683f7447ce9406cb97cd4b453be18d7279320f7b2f025c10187

fetch eigen-5.0.1.tar.gz \
  https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz \
  e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec

if [[ "$(uname -s)" == "Darwin" ]]; then
  case "$(uname -m)" in
    arm64)
      onnxruntime_name=onnxruntime-osx-arm64-static_lib-1.28.2.zip
      onnxruntime_hash=bf6e35dc9440d2899c3d21f52b1045fb99176a6ae822281b7245c5418cc338ab
      ;;
    x86_64)
      onnxruntime_name=onnxruntime-osx-x86_64-static_lib-1.28.2.zip
      onnxruntime_hash=cf879fe1fae47078d84fea4d8d4f8d5e38da813c9c3890dd5cc2ff28524c71c8
      ;;
    *)
      echo "Unsupported macOS architecture: $(uname -m)" >&2
      exit 1
      ;;
  esac

  if [[ ! -f "$cache/$onnxruntime_name" ]] || \
     [[ "$(sha256 "$cache/$onnxruntime_name")" != "$onnxruntime_hash" ]]; then
    if command -v gh >/dev/null 2>&1; then
      gh release download v1.28.2 \
        --repo csukuangfj/onnxruntime-libs \
        --pattern "$onnxruntime_name" \
        --dir "$cache" \
        --clobber
    else
      download \
        "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/$onnxruntime_name" \
        "$cache/$onnxruntime_name" "$onnxruntime_hash"
    fi
  fi
  cp -f "$cache/$onnxruntime_name" "/tmp/$onnxruntime_name"
fi

if [[ "${SHERPA_ONNX_KMP_FETCH_APPLE:-0}" == "1" ]]; then
  ios_onnxruntime_name=onnxruntime-ios-static-xcframework-1.28.2.xcframework.zip
  ios_onnxruntime_hash=2c2299acbb461d26d4bac4bc85985d40e7c7177ed6072703ae0846d88b0b4599
  if [[ ! -f "$cache/$ios_onnxruntime_name" ]] || \
     [[ "$(sha256 "$cache/$ios_onnxruntime_name")" != "$ios_onnxruntime_hash" ]]; then
    if command -v gh >/dev/null 2>&1; then
      gh release download v1.28.2 \
        --repo csukuangfj/onnxruntime-libs \
        --pattern "$ios_onnxruntime_name" \
        --dir "$cache" \
        --clobber
    else
      download \
        "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/$ios_onnxruntime_name" \
        "$cache/$ios_onnxruntime_name" "$ios_onnxruntime_hash"
    fi
  fi
fi

if [[ "${SHERPA_ONNX_KMP_FETCH_ANDROID:-0}" == "1" ]]; then  android_name=onnxruntime-android-1.28.2.zip
  android_hash=01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d
  if [[ ! -f "$cache/$android_name" ]] || \
     [[ "$(sha256 "$cache/$android_name")" != "$android_hash" ]]; then
    if command -v gh >/dev/null 2>&1; then
      gh release download v1.28.2 \
        --repo csukuangfj/onnxruntime-libs \
        --pattern "$android_name" \
        --dir "$cache" \
        --clobber
    else
      download \
        "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/$android_name" \
        "$cache/$android_name" "$android_hash"
    fi
  fi
  cp -f "$cache/$android_name" "/tmp/$android_name"
fi

if [[ "${SHERPA_ONNX_KMP_FETCH_DESKTOP:-0}" == "1" ]]; then
  fetch_desktop() {
    local name=$1
    local hash=$2
    if [[ ! -f "$cache/$name" ]] || \
       [[ "$(sha256 "$cache/$name")" != "$hash" ]]; then
      if command -v gh >/dev/null 2>&1; then
        gh release download v1.28.2 \
          --repo csukuangfj/onnxruntime-libs \
          --pattern "$name" \
          --dir "$cache" \
          --clobber
      else
        download \
          "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/$name" \
          "$cache/$name" "$hash"
      fi
    fi
    local actual
    actual=$(sha256 "$cache/$name")
    if [[ "$actual" != "$hash" ]]; then
      echo "Checksum mismatch for $cache/$name" >&2
      echo "expected: $hash" >&2
      echo "actual:   $actual" >&2
      exit 1
    fi
    # Upstream `cmake/onnxruntime-wasm-simd.cmake` probes `/tmp` for the Wasm
    # runtime before downloading it, so publish the file there as well. This
    # keeps the Emscripten build offline-deterministic and pinned.
    cp -f "$cache/$name" "/tmp/$name"
  }

  fetch_desktop onnxruntime-linux-x64-glibc2_17-Release-1.28.2.zip \
    c4f8994d56191d9d2c92a961b39fe790459f2c5d155f912b239506ea31359534
  fetch_desktop onnxruntime-linux-aarch64-glibc2_17-Release-1.28.2.zip \
    28083273643f40fa6477ef9d53986fe216304710c4b1c743b217a4effd016339
  fetch_desktop onnxruntime-win-x64-static_lib-MT-Release-1.28.2.tar.bz2 \
    77c6cc2a419828f450a570851d7d6d2385523c411dbe858f841a4c7338a78881
  # WebAssembly runtime for the browser bridge. This hash matches the one
  # pinned by upstream `cmake/onnxruntime-wasm-simd.cmake`, so a version bump
  # cannot silently desynchronize the two.
  fetch_desktop onnxruntime-wasm-static_lib-simd-1.28.2.zip \
    88688849b67fed9ca0e9fb2bf92ce19a5def9e8c2c8c2df34eb235d25893232a
fi
