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

version="${1:-$(./scripts/print-release-version.sh)}"
out="$repo_root/build/release/$version"
mkdir -p "$out"

cd "$repo_root"

./gradlew :sherpa-onnx-kmp:jvmJar :sherpa-onnx-kmp:assemble --console=plain
./scripts/check-android-aar.sh

# The host JNI JAR can be skipped when another job packages it. The release
# workflow runs one `jvm-native-*` job per desktop platform, because a single
# JAR only covers the host that built it.
native_jar=""
native_platform=""
if [[ "${SHERPA_ONNX_KMP_SKIP_NATIVE_JAR:-0}" != "1" ]]; then
  native_jar=$(./scripts/package-jvm-native.sh "$version" | tail -n 1)
  native_platform=$(basename "$native_jar" | sed -e 's/^sherpa-onnx-kmp-jvm-native-//' -e "s/-$version\.jar$//")
  if [[ -z "$native_platform" || "$native_platform" == "$(basename "$native_jar")" ]]; then
    echo "Could not derive the native platform from $native_jar" >&2
    exit 1
  fi
fi

cp -f "sherpa-onnx-kmp/build/libs/sherpa-onnx-kmp-jvm-$version.jar" \
  "$out/sherpa-onnx-kmp-jvm-$version.jar"
cp -f sherpa-onnx-kmp/build/outputs/aar/sherpa-onnx-kmp.aar \
  "$out/sherpa-onnx-kmp-android-$version.aar"
if [[ -n "$native_jar" ]]; then
  cp -f "$native_jar" "$out/"
fi

# The Apple XCFramework is a separate artifact with its own signing and
# verification. It is only produced when Xcode is available, because a release
# that quietly omits the Apple slice is worse than one that says it is missing.
if command -v xcrun >/dev/null 2>&1 && [[ "${SHERPA_ONNX_KMP_SKIP_APPLE:-0}" != "1" ]]; then
  ./scripts/package-apple-xcframework.sh "$version"
else
  echo "Skipping the Apple XCFramework: xcrun is unavailable or SHERPA_ONNX_KMP_SKIP_APPLE=1" >&2
fi

cd "$out"
# The release job merges every platform slice and writes the manifest last, so
# this host may only have a subset. --allow-partial records what is here without
# pretending the rest exists. The script is addressed through the repo root
# because the working directory is now the release directory.
if "$repo_root/scripts/write-release-checksums.sh" "$version" --allow-partial; then :; else
  echo "Could not write the release manifest" >&2
  exit 1
fi

printf '\nArtifact inventory (%s):\n' "$version"
printf '  jvm            sherpa-onnx-kmp-jvm-%s.jar\n' "$version"
if [[ -n "$native_platform" ]]; then
  printf '  jvm native     sherpa-onnx-kmp-jvm-native-%s-%s.jar\n' "$native_platform" "$version"
else
  printf '  jvm native     packaged by the jvm-native-* jobs\n'
fi
printf '  android        sherpa-onnx-kmp-android-%s.aar\n' "$version"
if [[ -f "SherpaOnnxKmp.xcframework.zip" ]]; then
  printf '  apple          SherpaOnnxKmp.xcframework.zip\n'
fi
printf '\nThe Linux and Windows native artifacts are produced on their own hosts by\n'
printf 'scripts/build-native-desktop.sh and scripts/package-release-desktop.sh, and\n'
printf 'attach to the same release. The web bundle comes from\n'
printf 'scripts/package-release-web.sh.\n'
