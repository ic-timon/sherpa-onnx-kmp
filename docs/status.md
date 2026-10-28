# Status

Last updated: 2026-10-29

Status: 1.0.0 stable

## Current state

The terminal architecture in [`design.md`](design.md) is implemented for every
target family it names. 1.0.0 freezes the public API, the bridge ABI, and the
result schema; the compatibility contract is in
[`versioning.md`](versioning.md).

### Targets

- Android (`androidTarget`, JNI): AAR for arm64-v8a, armeabi-v7a, x86, x86_64.
  Device suite passes on an API 36.1 arm64 emulator, including the packaged
  bridge's exported-symbol and capability checks.
- iOS (`iosArm64`, `iosSimulatorArm64`) and macOS (`macosArm64`): one shared
  Kotlin/Native cinterop transport in `nativeMain`. Simulator arm64 and macOS
  arm64 run the shared contract suite and the eight real-model feature suites.
  The device slice builds, links, and signs.
- Linux (`linuxX64`, `linuxArm64`) and Windows (`mingwX64`): the same transport,
  different linking. Linux ships a shared bridge with a sibling
  `libonnxruntime.so`; Windows ships a DLL with static ONNX Runtime. See ADR 0007
  and ADR 0008.
- JVM: JNI transport shared with Android, with native-library discovery either
  from an explicit path or from a classpath native JAR.
- Web (Kotlin/JS): Emscripten module in a dedicated Web Worker. The build now
  enables TTS and speaker diarization, so the browser advertises the same ten
  resources as the native builds.

### Feature surface

All eight features are implemented in `commonMain` on top of one option mapper
and one result mapper: offline ASR, streaming ASR, VAD, TTS, keyword spotting,
punctuation, speaker diarization, and speech enhancement.

`SherpaCapabilities.features` is derived from the bridge's resource list, so a
build that omits a feature says so instead of failing at call time.

### Bridge

- ABI version 2, schema version 1.
- 10 resources, 35 operations.
- `SherpaOnnxBridgePushAudio` and `SherpaOnnxBridgePullAudio` carry streaming
  input and generated audio out of band; JSON never carries samples.
- Two upstream prerequisites:
  `patches/0001-parse-options-try-set-option.patch` and
  `patches/0002-normal-data-generator-avoid-thread-local.patch`. The second
  removes the only `thread_local` that a minSdk 21 Android build cannot link;
  see `platform-matrix.md`.

### Verification recorded for this release

- `jvmTest` 45, `jsNodeTest` 36, `jsBrowserTest` 36, `macosArm64Test` 42,
  `iosSimulatorArm64Test` 42, Android device 2 — all passing.
- Eight real-model feature suites on JVM, macOS arm64, and iOS Simulator.
- Bridge C ABI probe: ABI integer, 10 resources, 35 operations, typed failures,
  real TTS synthesis and audio pull.
- Release hardening: header/library ABI agreement, dependency audit, bounded
  memory growth over repeated create/destroy.
- Capability parity probe for the web module.
- Chrome transcribes the pinned model through the worker path.

## Known gaps

- Physical iOS and Android device runs are documented gates, not automated CI.
  The iOS device slice builds, links, and signs; the Android device suite runs on
  an emulator.
- `linuxArm64` and Windows are built and tested in CI on native runners, not on
  the development host.
- The glibc floor on Linux is the build host's, not a policy value.
- Core ML is not enabled on Apple; CPU only.
- No upstream RFC or issue has been opened for the `TrySetOption` patch.

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

These are deferred deliberately, not unfinished:

- Whether iOS x64 and macOS x64 remain compatibility-only JVM targets.
- Minimum supported browser versions beyond "WebAssembly SIMD required".
- Repository publication coordinates (Maven Central and the Apple registry).
- Whether Core ML becomes an opt-in provider.
- Whether the physical-device gates become automated through a hosted runner.

## Immediate workstreams

1. Publish every platform artifact for 1.0.0. The `release.yml` workflow builds
   each slice on its own runner and refuses to write a manifest when one is
   missing; run it once the GitHub Actions runners used by the matrix are
   available.
2. Record physical-device runs for iOS and Android when hardware is available.
3. Publish to the chosen Maven and Apple coordinates.
4. Track upstream sherpa-onnx releases and extend the pinned core range after the
   bridge suite passes against them.
