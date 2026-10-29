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
  The device suite transcribes a pinned model and asserts the packaged bridge's
  exported-symbol and capability surface. It runs on an API 36 x86_64 emulator,
  which is the only ABI the GitHub-hosted runner can execute without
  cross-architecture emulation; an arm64-v8a emulator job covers the arm64 slice.
- iOS (`iosArm64`, `iosSimulatorArm64`) and macOS (`macosArm64`): one shared
  Kotlin/Native cinterop transport in `nativeMain`. Simulator arm64 and macOS
  arm64 run the shared contract suite and the shared real-model feature suite,
  which covers six of the eight features: VAD, streaming ASR, TTS via the
  lexicon path, speaker diarization, punctuation, and keyword spotting. The
  remaining two — speech enhancement and the Piper phoneme TTS path — are
  asserted by the JVM suite. The device slice builds, links, and signs.
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
- Eight real-model feature suites on the JVM. The shared `nativeFeatureTest`
  suite that macOS arm64, iOS Simulator, Linux, and Windows run covers six:
  VAD, streaming ASR, lexicon TTS, speaker diarization, punctuation, and
  keyword spotting.
- Bridge C ABI probe: ABI integer, 10 resources, 35 operations, typed failures,
  real TTS synthesis and audio pull.
- Release hardening: header/library ABI agreement, dependency audit, bounded
  memory growth over repeated create/destroy.
- Capability parity probe for the web module.
- Chrome transcribes the pinned model through the worker path.

### What the platform runs prove

The three transports each carry a different amount of real-inference evidence,
and they are not interchangeable:

- JNI (Android, JVM) — eight features against pinned models, on the JVM. The
  Android device suite transcribes one model and asserts the packaged `.so`
  advertises every feature, so the Android half is transport and packaging
  evidence rather than per-feature inference evidence.
- Native cinterop (Apple, Linux, Windows) — six features against pinned models
  on macOS arm64 and iOS Simulator. Linux and Windows run the same suite, but
  only exercise it when a feature model directory is configured.
- Web worker — offline ASR end to end in Chromium. Every other feature is
  covered by the capability-parity probe, which proves the module was built with
  the resource, not that the call completes in a worker.

## Known gaps

- Physical iOS and Android device runs are documented gates, not automated CI.
  The iOS device slice builds, links, and signs; the Android device suite runs on
  a pinned emulator.
- The Android device suite executes on x86_64. The arm64-v8a slice is compiled
  and its exported symbols are checked, but no arm64 code has executed in CI.
- `linuxArm64` and Windows are built and tested in CI on native runners, not on
  the development host.
- The memory-growth check runs in a shortened mode on the CI runner; the full
  run is a local gate.
- Speech enhancement and the Piper phoneme TTS path have no native-transport
  feature test, so the Apple and desktop suites cover six of the eight features.
- Web feature coverage beyond offline ASR is capability parity, not a behavioral
  assertion. The browser end-to-end check drives Chromium only.
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

1. Every 1.0.0 artifact is published: the AAR, the JVM API JAR, four JVM native
   JARs, the XCFramework, Linux x64/arm64, Windows x64, the web bundle, and
   `SHA256SUMS`. `release.yml` builds each slice on its own runner and refuses to
   write a manifest when one is missing, so a future release cannot silently
   omit a platform the way the v1.0.0 notes originally claimed.
2. Record physical-device runs for iOS and Android when hardware is available.
3. Publish to the chosen Maven and Apple coordinates.
4. Track upstream sherpa-onnx releases and extend the pinned core range after the
   bridge suite passes against them.
