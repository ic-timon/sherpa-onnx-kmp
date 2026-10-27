# 0006: Self-contained Apple XCFramework

- Status: Accepted
- Date: 2026-10-28

## Context

Kotlin/Native cinterop imports the Portable Bridge without duplicating feature
logic. Apple applications also need a deterministic way to ship sherpa-onnx,
ONNX Runtime, and the Kotlin runtime without requiring consumers to assemble
several native frameworks.

The Kotlin framework must remain a dynamic framework so the linker can resolve
the statically linked bridge and engine into one signed binary. The app-facing
distribution should contain one framework name for every Apple slice and no
separate ONNX Runtime framework.

## Decision

Apple releases use a single `SherpaOnnxKmp.xcframework` with these slices:

- `ios-arm64`
- `ios-arm64-simulator`
- `macos-arm64`

For each slice, the CMake build produces one aggregate static archive containing:

- the Portable Bridge;
- sherpa-onnx;
- its native dependencies;
- the matching static ONNX Runtime library.

Kotlin/Native links that archive into the dynamic `SherpaOnnxKmp.framework`.
The resulting framework is ad-hoc signed for local verification and is re-signed
by the consuming application. The XCFramework must not contain another ONNX
Runtime framework or reference an external ONNX Runtime library.

The initial Apple deployment targets are iOS 15 and macOS 13. The iOS floor is
the minimum accepted by the current Xcode iOS Simulator SDK. Apple CPU inference
is the supported provider in v0.4.0; CoreML is a later capability addition.

Apple model files are resolved through `ModelStore`. `FileModelStore` uses a
filesystem root, while `BundleModelStore` resolves a directory copied into the
application bundle. Public suspend APIs use `@Throws` so Swift receives Kotlin
failures as `NSError`.

## Consequences

- Consumers embed one framework and do not install ONNX Runtime separately.
- The Kotlin framework is larger because inference is statically embedded.
- Apple target publication requires supplying the XCFramework or the matching
  aggregate static archive; a klib alone is not a runnable distribution.
- Adding CoreML or another provider requires a separate packaging and
  capability change.
- Physical-device execution remains a release gate even when the device slice
  compiles and links.
