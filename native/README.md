# Native Portable Bridge

## Prerequisites

- CMake 3.20 or newer
- Ninja
- A C++17 compiler
- macOS arm64/x86_64 for the current host build

## Build

```bash
./scripts/fetch-native-deps.sh
source_dir=$(./scripts/bootstrap-sherpa-source.sh | tail -n 1)

cmake -S native -B build/native -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DSHERPA_ONNX_SOURCE_DIR="$source_dir" \
  -DSHERPA_ONNX_KMP_BUILD_TESTS=ON

cmake --build build/native -j 4
ctest --test-dir build/native --output-on-failure
```

The dependency cache lives under:

```text
~/.cache/sherpa-onnx-kmp
```

The pinned integration model is downloaded by:

```bash
./scripts/test-bridge-integration.sh
```

Models are test-only and are not committed to the repository.

## Outputs

```text
build/native/portable-bridge/libsherpa-onnx-portable-bridge.dylib
build/native/portable-bridge/sherpa-onnx-portable-bridge-cli
build/native/portable-bridge/tests/sherpa-onnx-portable-bridge-test
```

## Android

```bash
./scripts/build-android-all.sh
./gradlew :sherpa-onnx-kmp:assemble
./scripts/check-android-aar.sh
```

## Apple

```bash
./scripts/build-apple-native.sh all
./scripts/package-apple-xcframework.sh 0.4.0
./scripts/test-apple-samples.sh 0.4.0
```

The Apple build uses `sherpa-onnx-portable-bridge-static` and creates one
aggregate static archive per architecture with the bridge, sherpa-onnx, and
ONNX Runtime. See `docs/apple-transport.md`.
