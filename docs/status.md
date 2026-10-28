# Status

Last updated: 2026-10-28

## Current state

- Public repository created.
- Terminal architecture documented.
- Platform boundaries documented.
- KMP Gradle project exists under `sherpa-onnx-kmp/`.
- Public contract layer compiles for JVM, JS, iOS simulator, macOS arm64,
  Linux x64/arm64, and Windows x64.
- JVM and JS Node contract tests pass.
- v0.3.0 JVM and Android integration is released.
- JVM JNI transport works with both explicit native paths and classpath native
  JAR extraction.
- Android AAR is built for arm64-v8a, armeabi-v7a, x86, and x86_64.
- JVM CLI and Android arm64 emulator tests pass with a pinned model.
- Android sample app builds.
- v0.4.0 Apple integration is released as a preview for iOS and Apple Silicon
  macOS. The prerelease attaches the signed self-contained
  `SherpaOnnxKmp.xcframework.zip` and its SHA-256 checksum.
- Kotlin/Native cinterop, `FileModelStore`, and `BundleModelStore` are working.
- macOS arm64 and iOS Simulator transcribe the pinned model.
- The self-contained Apple XCFramework builds, signs, and contains no external
  ONNX Runtime framework.
- The iOS sample installs, launches, and transcribes in Simulator.
- The iOS device slice and sample app link without a physical device.
- Native desktop (`linuxX64`, `linuxArm64`, `mingwX64`) shares
  `NativePortableTransport` with Apple in `nativeMain`.
- `FileModelStore` works on Linux and Windows; `BundleModelStore` is Apple-only.
- Linux and Windows native libraries build with dedicated scripts and CI jobs.
- Linux ships a shared bridge library
  (`libsherpa-onnx-kmp-native-desktop.so`) with `libonnxruntime.so` as a sibling
  runtime. The earlier aggregate static archive could not link: the C++ graph is
  built against the host glibc/libstdc++, while Kotlin/Native links with the
  glibc 2.19 konan sysroot. See ADR 0008.
- Both Linux architectures also ship `libsherpa-onnx-portable-bridge.so`, the
  bridge's `SONAME`. Linking uses the Kotlin alias, but the loader resolves the
  `DT_NEEDED` name recorded from that `SONAME`, so shipping only the alias
  produced binaries that exited 127 at startup.
- The Web Emscripten build now uses upstream's supported
  `SHERPA_ONNX_ENABLE_WASM` path, which pins the `onnxruntime-wasm-static_lib`
  package. Upstream's downloader otherwise rejects Emscripten outright.
- The Web Worker transport is implemented for Kotlin/JS: `WebWorkerTransport`
  serializes bridge calls across the worker boundary, transfers audio as a
  `Float32Array` buffer, and backs `HttpModelStore`/`MemoryModelStore`.
- The Emscripten bridge builds and its C ABI is verified on the JS glue surface.
  At `-O3` Emscripten renames the internal Wasm export table, so the check
  asserts the stable `Module['_SherpaOnnxBridge*']` names the worker calls.
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

## Open release gates

- Run Apple inference on a physical iPhone and record the device, OS version,
  model checksum, and transcript before marking Apple support stable.
- Whether iOS x64 and macOS x64 remain compatibility-only targets.
- Minimum supported browser versions for the web target.
- Repository publication coordinates and release automation.

## Immediate workstreams

1. Complete the physical iPhone release gate when hardware is available.
2. Run the web sample against the pinned model in a real browser and record the
   result, then close the 0.6.0 gate.
3. Build the complete platform packaging and release matrix.
