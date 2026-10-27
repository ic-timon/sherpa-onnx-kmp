# Status

Last updated: 2026-10-27

## Current state

- Public repository created.
- Terminal architecture documented.
- Platform boundaries documented.
- KMP Gradle project exists under `sherpa-onnx-kmp/`.
- Public contract layer compiles for JVM, JS, iOS simulator, macOS arm64,
  Linux x64/arm64, and Windows x64.
- JVM and JS Node contract tests pass.
- v0.2.0 Portable Bridge ABI is implemented and locally verified.
- The bridge builds against patched sherpa-onnx v2.0.1 on macOS arm64.
- Offline ASR bridge integration passes with a pinned model.
- KMP transport implementations are not implemented yet.
- An upstream RFC/issue has not been opened yet.

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

1. Run the portable bridge workflow on the target platform matrix.
2. Add a docs-only upstream RFC and tracking issue.
3. Implement JNI transport for JVM and Android.
4. Implement Native and Web transports behind the same contract.
5. Build the platform packaging and CI matrix.
