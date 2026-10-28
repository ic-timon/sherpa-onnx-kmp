#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

version="${1:-1.0.0}"
xcframework="$repo_root/build/release/$version/SherpaOnnxKmp.xcframework"
project="$repo_root/samples/apple/SherpaKmpSamples.xcodeproj"
derived="$repo_root/build/apple/sample-test"
model_dir="$(cache_root)/models/sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26"

if [[ ! -d "$xcframework" ]]; then
  echo "XCFramework not found: $xcframework" >&2
  exit 1
fi

./scripts/prepare-apple-sample-assets.sh

devices_json=$(xcrun simctl list devices available -j)
first_iphone=$(printf '%s' "$devices_json" | jq -r '
  first(.devices[][] |
    select(.isAvailable and (.name | startswith("iPhone"))) | .udid) // empty
')
if [[ -z "$first_iphone" ]]; then
  echo "No available iPhone simulator" >&2
  exit 1
fi
xcrun simctl boot "$first_iphone" 2>/dev/null || true

simulator_udid="${APPLE_SIMULATOR_UDID:-}"
if [[ -z "$simulator_udid" ]]; then
  simulator_udid=$(xcrun simctl list devices booted -j | jq -r '
    first(.devices[][] |
      select(.state == "Booted" and (.name | startswith("iPhone"))) | .udid) // empty
  ')
fi
if [[ -z "$simulator_udid" ]]; then
  echo "No booted iPhone simulator" >&2
  exit 1
fi
xcrun simctl bootstatus "$simulator_udid" -b >/dev/null

mkdir -p "$derived"
xcodebuild \
  -project "$project" \
  -scheme SherpaKmpMacOS \
  -configuration Debug \
  -destination 'platform=macOS,arch=arm64' \
  -derivedDataPath "$derived" \
  build > "$derived/macos-build.log" 2>&1

mac_output=$(
  DYLD_FRAMEWORK_PATH="$xcframework/macos-arm64" \
    "$derived/Build/Products/Debug/SherpaKmpMacOS" \
    "$model_dir" "$model_dir/test_wavs/0.wav"
)
if [[ -z "$mac_output" ]]; then
  echo "macOS sample returned an empty transcript" >&2
  exit 1
fi
printf 'macOS sample transcript: %s\n' "$mac_output"

xcodebuild \
  -project "$project" \
  -scheme SherpaKmpIOS \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_udid" \
  -derivedDataPath "$derived" \
  build > "$derived/ios-build.log" 2>&1

app="$derived/Build/Products/Debug-iphonesimulator/SherpaKmpIOS.app"
bundle_id=com.k2fsa.sherpa.onnx.kmp.sample.ios
xcrun simctl uninstall "$simulator_udid" "$bundle_id" 2>/dev/null || true
xcrun simctl install "$simulator_udid" "$app"
xcrun simctl launch --terminate-running-process "$simulator_udid" "$bundle_id" --auto-run
container=$(xcrun simctl get_app_container "$simulator_udid" "$bundle_id" data)
transcript="$container/Documents/transcript.txt"
for _ in {1..90}; do
  if [[ -f "$transcript" ]]; then
    break
  fi
  sleep 1
done
if [[ ! -f "$transcript" ]]; then
  echo "iOS sample did not produce a transcript" >&2
  exit 1
fi
ios_output=$(cat "$transcript")
if [[ "$ios_output" != ERROR:* && -n "$ios_output" ]]; then
  printf 'iOS sample transcript: %s\n' "$ios_output"
else
  echo "iOS sample failed: $ios_output" >&2
  exit 1
fi

xcodebuild \
  -project "$project" \
  -scheme SherpaKmpIOS \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "${derived}-device" \
  build CODE_SIGNING_ALLOWED=NO > "$derived/ios-device-build.log" 2>&1
printf 'iOS device framework link: OK\n'
