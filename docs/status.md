# Status

Last updated: 2026-10-27

## Current state

- Public repository created.
- Terminal architecture documented.
- Platform boundaries documented.
- No Gradle project or implementation code yet.
- No upstream change has been proposed yet.

## Accepted direction

- One typed KMP API and one common engine.
- One additive Portable Bridge C ABI in sherpa-onnx.
- Three transport implementations:
  - JNI for Android and JVM
  - Native cinterop for iOS, macOS, Linux, and Windows x64
  - Web Worker for Kotlin/JS and Emscripten
- Android remains a JVM target with JNI.
- Web uses Kotlin/JS and the Emscripten sherpa-onnx build. Kotlin/Wasm is not
  used as a second wrapper runtime.
- Kotlin/Native desktop is supported for native desktop integrations.
- JVM remains supported for Compose Desktop, Windows ARM64, Intel macOS, and
  Java interoperability.

## Open decisions

- Exact portable bridge operation names and versioning policy.
- Native library distribution format for Linux and Windows.
- Whether iOS x64 and macOS x64 remain release targets or compatibility-only.
- Minimum supported browser versions for the web target.
- Repository publication coordinates and release automation.

## Immediate workstreams

1. Specify the Portable Bridge ABI and stable result schema.
2. Add a docs-only upstream RFC and tracking issue.
3. Create the KMP Gradle project and common API skeleton.
4. Implement the common option/result mappers and lifecycle tests.
5. Implement JNI, Native, and Web transports behind the same contract.
6. Build the platform packaging and CI matrix.
