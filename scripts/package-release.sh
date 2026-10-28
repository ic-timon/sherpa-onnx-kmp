#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Builds the JVM and Android release artifacts for every host this script can
# produce, and writes a checksum manifest.
#
# The artifact set is host-dependent by nature: the JVM native JAR carries the
# bridge for the build host's platform, and the Apple XCFramework needs Xcode.
# This script produces what the current host can produce and says so, rather than
# silently emitting a partial release.
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
version="${1:-1.0.0}"
out="$repo_root/build/release/$version"
mkdir -p "$out"

cd "$repo_root"

./gradlew :sherpa-onnx-kmp:jvmJar :sherpa-onnx-kmp:assemble --console=plain
./scripts/check-android-aar.sh

native_jar=$(./scripts/package-jvm-native.sh "$version" | tail -n 1)
native_platform=$(basename "$native_jar" | sed -e 's/^sherpa-onnx-kmp-jvm-native-//' -e "s/-$version\.jar$//")
if [[ -z "$native_platform" || "$native_platform" == "$(basename "$native_jar")" ]]; then
  echo "Could not derive the native platform from $native_jar" >&2
  exit 1
fi

cp -f "sherpa-onnx-kmp/build/libs/sherpa-onnx-kmp-jvm-$version.jar" \
  "$out/sherpa-onnx-kmp-jvm-$version.jar"
cp -f sherpa-onnx-kmp/build/outputs/aar/sherpa-onnx-kmp.aar \
  "$out/sherpa-onnx-kmp-android-$version.aar"
cp -f "$native_jar" "$out/"

# The Apple XCFramework is a separate artifact with its own signing and
# verification. It is only produced when Xcode is available, because a release
# that quietly omits the Apple slice is worse than one that says it is missing.
if command -v xcrun >/dev/null 2>&1 && [[ "${SHERPA_ONNX_KMP_SKIP_APPLE:-0}" != "1" ]]; then
  ./scripts/package-apple-xcframework.sh "$version"
else
  echo "Skipping the Apple XCFramework: xcrun is unavailable or SHERPA_ONNX_KMP_SKIP_APPLE=1" >&2
fi

cd "$out"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum ./*.aar ./*.jar ./*.zip > SHA256SUMS 2>/dev/null || \
    sha256sum ./*.aar ./*.jar > SHA256SUMS
else
  shasum -a 256 ./*.aar ./*.jar ./*.zip > SHA256SUMS 2>/dev/null || \
    shasum -a 256 ./*.aar ./*.jar > SHA256SUMS
fi
cat SHA256SUMS

printf '\nArtifact inventory (%s):\n' "$version"
printf '  jvm            sherpa-onnx-kmp-jvm-%s.jar\n' "$version"
printf '  jvm native     sherpa-onnx-kmp-jvm-native-%s-%s.jar\n' "$native_platform" "$version"
printf '  android        sherpa-onnx-kmp-android-%s.aar\n' "$version"
if [[ -f "SherpaOnnxKmp.xcframework.zip" ]]; then
  printf '  apple          SherpaOnnxKmp.xcframework.zip\n'
fi
printf '\nThe Linux and Windows native artifacts are produced on their own hosts by\n'
printf 'scripts/build-native-desktop.sh and attach to the same release.\n'
