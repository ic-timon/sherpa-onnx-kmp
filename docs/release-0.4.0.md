# Release 0.4.0: Apple

Status: preview

The iOS device slice compiles and links, but no physical iPhone was available on
the development host. Physical-device inference remains the release gate before
Apple support is marked stable.

## Artifacts

- `SherpaOnnxKmp.xcframework.zip`
  - SHA-256: `6dff3de5f7e7b28059e6b9e9df954c899b8d4320f80816f0be3d0a41c53a3330`
  - Compressed size: 30 MiB
  - Expanded size: 109 MiB
- XCFramework slices:
  - `ios-arm64`, minimum iOS 15.0
  - `ios-arm64-simulator`, minimum iOS 15.0
  - `macos-arm64`, minimum macOS 13.0
- Swift samples:
  - `samples/apple/macOS`
  - `samples/apple/iOS`

## Verification evidence

- macOS arm64 transcribes the pinned Persian NeMo CTC model through the Swift
  sample.
- iOS Simulator installs, launches, and transcribes the same bundled model.
- The iOS device slice compiles and links for `generic/platform=iOS` without a
  connected device.
- Each XCFramework slice exports exactly one `_SherpaOnnxBridgeAbiVersion` and
  one `_OrtGetApiBase`.
- `otool -L` reports no external ONNX Runtime library or framework.
- No `onnxruntime.framework` exists inside the XCFramework.
- Each slice passes `codesign --verify --deep --strict`.
- Kotlin/JVM and Kotlin/JS contract tests pass.
- Kotlin metadata and iOS, macOS, Linux x64/arm64, and Windows x64 compilation
  pass.

## Reproduction

```bash
./scripts/build-apple-native.sh all
SHERPA_ONNX_KMP_TEST_MODEL_DIR="$HOME/.cache/sherpa-onnx-kmp/models/sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26" \
  ./gradlew :sherpa-onnx-kmp:macosArm64Test
SHERPA_ONNX_KMP_TEST_MODEL_DIR="$HOME/.cache/sherpa-onnx-kmp/models/sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26" \
  ./gradlew :sherpa-onnx-kmp:iosSimulatorArm64Test --device "iPhone 18 Pro"
SHERPA_ONNX_KMP_SKIP_NATIVE=1 ./scripts/package-apple-xcframework.sh 0.4.0
./scripts/test-apple-samples.sh 0.4.0
```
