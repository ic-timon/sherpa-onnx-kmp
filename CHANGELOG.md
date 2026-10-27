# Changelog

All notable changes to this project are documented here.

## [Unreleased]

No unreleased changes.

## [0.4.0] - 2026-10-28

### Added

- Kotlin/Native cinterop transport for the Portable Bridge.
- Apple targets for iOS device, iOS Simulator, and Apple Silicon macOS.
- `FileModelStore` and `BundleModelStore` for Apple.
- Self-contained `SherpaOnnxKmp.xcframework` with the bridge, sherpa-onnx,
  and ONNX Runtime statically embedded.
- SwiftUI iOS sample and Swift macOS command-line sample.
- Apple CI covering native builds, cinterop, real-model inference, simulator
  execution, XCFramework creation, code signing, and device linking.
- `@Throws` annotations on asynchronous public APIs so Swift receives Kotlin
  failures as `NSError` instead of terminating the process.

### Verification

- macOS arm64 and iOS Simulator transcribe the pinned Persian ASR model.
- The iOS device framework and sample app link without a connected device.
- XCFramework slices contain exactly one bridge and one ONNX Runtime symbol.
- No external ONNX Runtime framework is referenced by the XCFramework.
- The sample app installs, launches, and transcribes in iOS Simulator.

### Known release gate

- A physical iPhone execution test was not available on the development host.
  Device compilation and linking pass, but Apple support remains preview until
  that test is recorded.

## [0.3.0] - 2026-10-27

### Added

- Shared JVM/Android JNI transport.
- JVM `FileModelStore` and classpath native-library extraction.
- JVM CLI sample and JVM native JAR packaging.
- Android KMP library using AGP 9.4 built-in Kotlin support.
- Android `AssetModelStore` and device-test assets workflow.
- Android AAR packaging for arm64-v8a, armeabi-v7a, x86, and x86_64.
- Android sample application.
- Android x86_64 CI path and local arm64 device validation.
- JNI integration with the Portable Bridge and offline ASR.

### Fixed

- Android API 21 x86_64 link failure from upstream FunASR Nano thread-local
  containers, added to the upstream patch.
- Android native STL packaging now uses `libc++_shared.so`.

### Verification

- JVM CLI transcribes the pinned model through the JNI bridge.
- Android arm64 emulator transcribes the pinned model.
- All four Android ABIs compile.
- 64-bit ELF segments use 16 KB alignment.
- JVM and Android sample applications build.

## [0.2.0] - 2026-10-27

### Added

- Additive Portable Bridge C ABI for offline ASR.
- Flat string-valued option protocol backed by ParseOptions registration.
- Non-fatal `TrySetOption` upstream patch.
- Opaque bridge handles and internal resource registry.
- Stable structured offline ASR result schema with `rawJson` fallback.
- Host bridge CLI and native CTest suite.
- Pinned Persian ASR model integration test.
- Kotlin common option mapper for offline ASR.

### Integration evidence

- Bridge CTest passes.
- The pinned model produces a structured transcript through the bridge CLI.
- JVM common option mapping tests pass.

## [0.1.1] - 2026-10-27

### Fixed

- Replaced machine-specific npm mirror URLs in `kotlin-js-store/yarn.lock` with
  the official npm registry.
- Added a repository-level `.yarnrc` and CI lockfile portability check.

## [0.1.0] - 2026-10-27

- Initial public contract release.
- Added multi-target KMP compilation and contract tests.
- Added the end-to-end release roadmap through 1.0.0.
