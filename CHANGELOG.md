# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Added

- Foundation KMP module and public API contracts.

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
