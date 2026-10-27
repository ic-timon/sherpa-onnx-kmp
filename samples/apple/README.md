# Apple Swift Samples

These samples consume the self-contained `SherpaOnnxKmp.xcframework`. The
framework embeds the portable bridge, sherpa-onnx, and ONNX Runtime; no separate
ONNX Runtime framework is required.

## Prerequisites

- macOS 13 or newer
- Xcode with an iOS Simulator runtime
- The XCFramework produced by `scripts/package-apple-xcframework.sh`
- The pinned test model, populated by `scripts/prepare-apple-sample-assets.sh`

## Build and test

From the repository root:

```bash
./scripts/build-apple-native.sh all
./scripts/package-apple-xcframework.sh 0.4.0
./scripts/test-apple-samples.sh 0.4.0
```

`SherpaKmpSamples.xcodeproj` is generated from `project.yml` with XcodeGen.
The generated project is checked in so XcodeGen is not required to build the
samples.

- `SherpaKmpMacOS` is a macOS command-line sample.
- `SherpaKmpIOS` is a SwiftUI app that transcribes a bundled WAV using
  `BundleModelStore`.

The iOS target accepts `--auto-run` to transcribe without pressing the button.
This mode writes the result to `Documents/transcript.txt`, which the test script
uses as runtime evidence.

The test model is copied with the extension `model.onnxdata` because Apple's
codesign machinery treats `.onnx` as a package type and otherwise rejects the
application bundle.
