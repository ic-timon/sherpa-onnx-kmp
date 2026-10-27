# Apple Transport

Status: v0.4.0 preview

## Targets

- `iosArm64`
- `iosSimulatorArm64`
- `macosArm64`

Minimum deployment targets are iOS 15 and macOS 13. `iosX64` and `macosX64`
remain outside the core release matrix.

## Native pipeline

```text
Kotlin common engine
  -> ApplePortableTransport
  -> Kotlin/Native cinterop
  -> aggregate Apple static archive
       Portable Bridge
       sherpa-onnx
       ONNX Runtime
  -> SherpaOnnxKmp.framework
```

The cinterop header is
`native/portable-bridge/include/sherpa-onnx-portable-bridge.h`. The generated
bindings are internal; handles and pointers never appear in the public API.

The CMake targets for the shim are `sherpa-onnx-portable-bridge-static` and
`sherpa-onnx-portable-bridge`. The JNI path continues to use the shared library;
Apple KMP targets use the static target.

## Build

```bash
./scripts/build-apple-native.sh all
./scripts/package-apple-xcframework.sh 0.4.0
```

The build script downloads the pinned static ONNX Runtime distributions and
creates one aggregate archive per target:

```text
build/apple/macos-arm64/lib/libsherpa-onnx-kmp-apple.a
build/apple/ios-arm64/lib/libsherpa-onnx-kmp-apple.a
build/apple/ios-simulator-arm64/lib/libsherpa-onnx-kmp-apple.a
```

The packaging script creates and signs:

```text
build/release/0.4.0/SherpaOnnxKmp.xcframework
build/release/0.4.0/SherpaOnnxKmp.xcframework.zip
```

The XCFramework contains one framework per Apple slice. It contains no
`onnxruntime.framework`; `otool` must show no external ONNX Runtime dependency
and each slice must expose exactly one `_OrtGetApiBase` and one
`_SherpaOnnxBridgeAbiVersion` symbol.

## Model stores

```kotlin
public class FileModelStore(rootPath: String) : ModelStore

public class BundleModelStore(
    resourceRoot: String = "models",
    bundle: NSBundle = NSBundle.mainBundle,
) : ModelStore
```

`BundleModelStore` checks both the bundle resource root and the nested
`Resources` location used by some Xcode folder-reference configurations.

Test and sample model files whose upstream filename ends in `.onnx` may need a
non-`.onnx` filename when embedded directly in an iOS app. Apple's code-signing
UTI handling can otherwise treat the model as an unsigned package. The sample
uses `model.onnxdata`; sherpa-onnx does not require the `.onnx` suffix.

## Swift interop and errors

All public suspend operations are annotated with `@Throws`, so Kotlin failures
arrive in Swift as `NSError` from the generated completion handler. Without
that annotation, Kotlin/Native terminates the process when the error crosses the
Objective-C boundary.

## Tests and samples

```bash
./scripts/prepare-apple-sample-assets.sh
SHERPA_ONNX_KMP_TEST_MODEL_DIR="$HOME/.cache/sherpa-onnx-kmp/models/sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26" \
  ./gradlew :sherpa-onnx-kmp:macosArm64Test
SHERPA_ONNX_KMP_TEST_MODEL_DIR="$HOME/.cache/sherpa-onnx-kmp/models/sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26" \
  ./gradlew :sherpa-onnx-kmp:iosSimulatorArm64Test --device "iPhone 18 Pro"
./scripts/test-apple-samples.sh 0.4.0
```

The sample test verifies macOS inference, iOS Simulator installation and
inference, and an unsigned generic iOS device build. A physical-device run is
still required before Apple support is marked stable.
