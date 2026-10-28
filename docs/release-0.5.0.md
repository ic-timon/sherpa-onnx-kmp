# Release 0.5.0: Native Desktop

Status: released

## Scope

Linux x64, Linux arm64, and Windows x64 run through the same Kotlin/Native
cinterop transport that Apple uses. Only linking and packaging differ.

## Artifacts

- `libsherpa-onnx-kmp-native-desktop.a`
  - `linux-x64`: aggregate static archive with bridge, sherpa-onnx, and ONNX
    Runtime merged.
  - `linux-arm64`: same aggregate archive built on an aarch64 host.
- `sherpa-onnx-portable-bridge.dll`
  - `windows-x64`: shared bridge with static ONNX Runtime embedded; no
    `onnxruntime.dll` is required.

See [ADR 0007](../docs/adr/0007-native-desktop-linkage.md) for the linkage
decision and [native desktop transport](../docs/native-desktop-transport.md) for
consumer packaging notes.

## Verification

- `linuxX64`, `linuxArm64`, and `mingwX64` compile and link in CI.
- The aggregate archive exposes exactly one `SherpaOnnxBridgeAbiVersion` and one
  `OrtGetApiBase`, and leaves no undefined ONNX Runtime symbol.
- The Windows bridge DLL exposes the same two symbols and needs no external ONNX
  Runtime.
- The shared contract suite passes on all three desktop targets.
- Real-model inference runs when `SHERPA_ONNX_KMP_TEST_MODEL_DIR` is set.
- Apple, JVM, and JS targets continue to compile and pass their tests.

## Reproduction

```bash
# Linux x64 host
./scripts/build-native-desktop.sh linux-x64
./gradlew :sherpa-onnx-kmp:linuxX64Test

# Linux arm64 host
./scripts/build-native-desktop.sh linux-arm64
./gradlew :sherpa-onnx-kmp:linuxArm64Test

# Windows host with MSYS2 MINGW64 in PATH
./scripts/build-native-desktop.sh windows-x64
./gradlew :sherpa-onnx-kmp:mingwX64Test
```

## Known limitations

- `linuxArm64` requires an aarch64 build host; the Kotlin/Native and CMake
  builds both run natively.
- Desktop real-model inference is exercised in CI on native runners. The
  development host is Apple Silicon and cannot link Linux or Windows binaries.
