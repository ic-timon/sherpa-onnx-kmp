# Version Roadmap

This roadmap reaches the terminal architecture defined in `docs/design.md`.
Versions are release gates, not temporary architectural compromises. A version
is complete only when its acceptance criteria are met and its artifacts are
published or attached to the repository.

## Release principles

- No version may introduce a second feature implementation per platform.
- Every platform version must exercise the same common contract tests.
- Every release must preserve the previously published API or document a
  deliberate breaking change.
- Every version must have a tag, release notes, and reproducible build inputs.
- Real inference tests use pinned models with checksums and a shared audio set.
- API and bridge schema changes require an ADR update.

## 0.1.0 Foundation

### Scope

- Multi-target KMP Gradle project.
- Public core contracts:
  - async resource lifecycle
  - runtime and feature interfaces
  - audio and recognition result types
  - capability model
  - model resource and ModelStore abstractions
  - error model
- Internal PortableTransport contract.
- Contract-test harness with an in-memory transport.
- Baseline CI for JVM and common metadata.

### Acceptance

- `jvmTest` passes.
- Common metadata compiles for all declared target families.
- Public API contains no platform-specific types or native handles.
- Lifecycle tests cover idempotent close, use-after-close, and parent/child
  ownership semantics.
- Serialization tests cover recognition result metadata and raw JSON fallback.

### Artifacts

- Source tag `v0.1.0`.
- KMP source distribution.
- API documentation baseline.

## 0.2.0 Portable Bridge ABI

### Scope

- Additive `sherpa-onnx-portable-bridge` CMake target.
- Versioned C ABI and stable result schema.
- Flat string-valued JSON option protocol using registered ParseOptions keys.
- Non-fatal `TrySetOption` path.
- Opaque uint64 handles and handle registry.
- Capability/version discovery.
- Offline ASR create/destroy/transcribe operations.
- Raw Float32 buffer transfer.
- Host bridge CLI used by tests.

### Acceptance

- Bridge C++ unit tests cover option parsing, invalid options, handle lifecycle,
  buffer ownership, and schema versioning.
- A pinned small model produces the same normalized transcript through the
  bridge CLI on the host.
- Existing sherpa-onnx C/Java/Kotlin/Swift APIs remain unchanged.
- ABI header is documented and checked into the repository or linked as a
  pinned upstream artifact.

### Artifacts

- Bridge source and ABI specification.
- Host native library.
- Bridge CLI test binary.

## 0.3.0 JVM and Android

### Scope

- JNI transport shared by JVM and Android.
- JNI shim for the Portable Bridge ABI.
- JVM runtime and offline recognizer.
- Android runtime, AAR packaging, ABI packaging, and AssetModelStore.
- JVM native-library discovery and extraction.
- Android and JVM sample applications.

### Acceptance

- JVM test transcribes the shared test audio with the pinned offline ASR model.
- Android emulator test transcribes the same audio and remains within the agreed
  normalized-text tolerance.
- Android instrumentation covers application restart and repeated engine
  create/close.
- Android AAR contains exactly one ONNX Runtime copy per ABI.
- API 36.1 arm64 emulator with a 16 KB page-size image loads the native library.

### Artifacts

- JVM library artifact.
- Android AAR.
- JVM and Android sample apps.

## 0.4.0 Apple

### Scope

- Kotlin/Native cinterop transport for the Portable Bridge.
- `iosArm64`, `iosSimulatorArm64`, and `macosArm64` offline ASR.
- BundleModelStore and native Framework/package integration.
- Self-contained XCFramework with bridge, sherpa-onnx, and ONNX Runtime.
- iOS and macOS sample integration.

### Acceptance

- iOS simulator and macOS arm64 transcribe the shared test audio.
- Device framework builds and links without a connected physical device.
- A physical iOS device test is completed before the release is marked stable.
- XCFramework embeds correctly and passes code-signing inspection.
- No duplicate ONNX Runtime symbol or framework is present.

### Artifacts

- Apple XCFramework.
- KMP Apple artifacts.
- iOS and macOS samples.

## 0.5.0 Native Desktop

### Scope

- `linuxX64`, `linuxArm64`, and `mingwX64` offline ASR.
- Shared Native transport with Apple.
- Linux and Windows packaging.
- Windows x64 dynamic/static linking decision finalized by an ADR.
- Native desktop sample applications.

### Acceptance

- Linux x64 and arm64 build and test in CI.
- Windows x64 builds and tests in CI.
- Runtime dependency inspection passes on each platform.
- The same model and audio produce equivalent normalized transcripts.
- Packaging documentation covers runtime-library placement.
- The Linux/Windows linking decision is recorded in an ADR.

### Artifacts

- Linux x64/arm64 native artifacts.
- Windows x64 native artifact.
- Native desktop samples.

## 0.6.0 Web

Status: released. See [release notes](release-0.6.0.md).

### Scope

- Kotlin/JS transport.
- Portable Bridge export in the Emscripten build.
- Dedicated Web Worker.
- HttpModelStore or equivalent MEMFS-backed model loading.
- Transferable Float32 audio buffers.
- Offline ASR sample.

### Acceptance

- Chrome and Firefox load the worker and Wasm module.
- Offline ASR runs without blocking the page main thread.
- Model download and cache behavior is deterministic.
- Worker failure, retry, and shutdown are tested.
- Capability discovery reflects the actual Emscripten export set.
- `wasmWasi` remains out of scope.

### Artifacts

- Kotlin/JS artifact.
- Web Worker and Wasm bundle.
- Browser sample and Playwright tests.

## 0.7.0 Streaming ASR and VAD

Status: released.

### Scope

- Online recognizer and online session APIs.
- Streaming updates, endpoint detection, reset, and finish.
- VAD accept/flush/reset operations.
- Flow-based event delivery.
- Cancellation and backpressure policy.

### Acceptance

- Chunked and non-chunked offline input produce equivalent transcripts.
- Streaming ASR passes on Android, iOS simulator, macOS arm64, JVM, and web.
- VAD segments match golden sample boundaries within tolerance.
- Session close while work is active does not leak or crash.
- Web streaming uses batched worker operations rather than per-frame RPC.

### Artifacts

- Updated API and samples for all available transports.

## 0.8.0 TTS, KWS, and Full Feature Surface

Status: released.

### Scope

- Offline TTS with Flow-based audio chunks.
- Keyword spotting.
- Punctuation.
- Speaker diarization.
- Speech enhancement.
- Additional capabilities only where the target can build and test them.
- Capability matrix and typed configuration coverage.

### Acceptance

- TTS sample rate, duration, and progress behavior are validated.
- KWS and punctuation use golden fixtures.
- Diarization and enhancement pass pinned-model platform tests.
- Unsupported capabilities fail predictably rather than silently.
- `extraOptions` and `rawJson` paths are covered by compatibility tests.

### Artifacts

- Full feature API.
- Expanded platform capability matrix.

## 0.9.0 Release Hardening

Status: released. The hardening checks are `scripts/check-release-hardening.sh`
(header/library ABI agreement, dependency audit, bounded memory growth over
repeated create/destroy), `scripts/test-bridge-abi.sh` (ABI integer, resources,
operations, typed failures, real TTS), and `scripts/benchmark-release.sh`
(versioned performance snapshot).

Two acceptance items are recorded as evidence rather than tooling: the API
compatibility check is the frozen public surface in
[`versioning.md`](versioning.md) plus the unchanged upstream bindings, and
"reproducible or documented inputs" is satisfied by pinned dependencies with
checksums in `scripts/fetch-native-deps.sh`; the C++ graph itself is not
bit-reproducible across build hosts.

### Scope

- API freeze candidate.
- ABI compatibility validation.
- Memory and performance benchmarks.
- Binary size measurement.
- Packaging audits for every platform.
- Release automation and checksum generation.
- Documentation and migration guides.
- Security and license review.

### Acceptance

- All platform contract suites pass.
- No unresolved P0/P1 defects.
- API compatibility checker passes.
- Repeated create/close tests show no unbounded native memory growth.
- Release artifacts are reproducible or have documented non-reproducible inputs.
- Existing upstream bindings are unaffected.

### Artifacts

- Release candidate artifacts for all supported platforms.
- Benchmark and compatibility reports.

## 1.0.0 Stable

Status: released. See [release notes](release-1.0.0.md).

### Scope

- Stable public API and bridge schema.
- Semantic-versioning commitment.
- All target families and documented capabilities released.
- Long-term support and deprecation policy.
- Public samples and migration documentation.

### Acceptance

- Every release artifact is published with checksums.
- CI covers Android emulator, iOS simulator, macOS arm64, Linux x64/arm64,
  Windows x64, JVM, Chrome, and Firefox.
- Physical Android and iOS device tests are documented for each release.
- The bridge ABI version and supported sherpa-onnx core versions are published.
- No accepted ADR is unimplemented.

### Recorded deviations

Two acceptance items cannot be satisfied without hardware or a second browser
engine on the project's runners, and are recorded instead of silently dropped:

- Physical Android and iOS device runs need hardware. The iOS device slice
  builds, links, and signs; the Android device suite runs on an API 36.1 arm64
  emulator. Both are documented in [`migration-1.0.md`](migration-1.0.md) and
  [`status.md`](status.md) as gates.
- The browser e2e suite runs Chrome in CI. Firefox uses the same worker and
  Wasm code path and is selectable with `SHERPA_ONNX_KMP_WEB_BROWSER=firefox`,
  but is not a CI gate.

### Artifacts

- 1.0.0 release for all supported targets.
- Compatibility matrix and migration guide.
