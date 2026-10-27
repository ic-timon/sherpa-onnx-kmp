#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

version="${1:-0.4.0}"
out_root="$repo_root/build/release/$version"
out="$out_root/SherpaOnnxKmp.xcframework"
zip="$out_root/SherpaOnnxKmp.xcframework.zip"

if [[ "${SHERPA_ONNX_KMP_SKIP_NATIVE:-0}" != "1" ]]; then
  ./scripts/build-apple-native.sh all
fi

./gradlew \
  :sherpa-onnx-kmp:linkReleaseFrameworkMacosArm64 \
  :sherpa-onnx-kmp:linkReleaseFrameworkIosArm64 \
  :sherpa-onnx-kmp:linkReleaseFrameworkIosSimulatorArm64 \
  --console=plain

frameworks=(
  "$repo_root/sherpa-onnx-kmp/build/bin/macosArm64/releaseFramework/SherpaOnnxKmp.framework"
  "$repo_root/sherpa-onnx-kmp/build/bin/iosArm64/releaseFramework/SherpaOnnxKmp.framework"
  "$repo_root/sherpa-onnx-kmp/build/bin/iosSimulatorArm64/releaseFramework/SherpaOnnxKmp.framework"
)

for framework in "${frameworks[@]}"; do
  binary="$framework/SherpaOnnxKmp"
  if [[ ! -e "$binary" ]]; then
    echo "Missing framework binary: $binary" >&2
    exit 1
  fi
  codesign --force --deep --sign - "$framework"
  codesign --verify --deep --strict --verbose=2 "$framework"
  if otool -L "$binary" | grep -qi 'onnxruntime'; then
    echo "Framework has an external ONNX Runtime dependency: $binary" >&2
    exit 1
  fi
  ort_count=$(nm -gU "$binary" | awk '$3 == "_OrtGetApiBase" {count++} END {print count+0}')
  bridge_count=$(nm -gU "$binary" | awk '$3 == "_SherpaOnnxBridgeAbiVersion" {count++} END {print count+0}')
  if [[ "$ort_count" != "1" || "$bridge_count" != "1" ]]; then
    echo "Unexpected embedded symbols in $binary (ORT=$ort_count bridge=$bridge_count)" >&2
    exit 1
  fi
done

mkdir -p "$out_root"
rm -rf "$out" "$zip"
xcodebuild -create-xcframework \
  -framework "${frameworks[0]}" \
  -framework "${frameworks[1]}" \
  -framework "${frameworks[2]}" \
  -output "$out"

for framework in "$out"/*/SherpaOnnxKmp.framework; do
  codesign --verify --deep --strict --verbose=2 "$framework"
done

if find "$out" -iname '*onnxruntime*' | grep -q .; then
  echo "XCFramework contains a duplicate ONNX Runtime framework" >&2
  exit 1
fi

plutil -lint "$out/Info.plist"
ditto -c -k --sequesterRsrc --keepParent "$out" "$zip"
(
  cd "$out_root"
  shasum -a 256 "$(basename "$zip")" > "$(basename "$zip").sha256"
  shasum -a 256 "$(basename "$zip")" > SHA256SUMS
)
printf 'XCFramework: %s\n' "$out"
printf 'Archive:     %s\n' "$zip"
cat "$zip.sha256"
