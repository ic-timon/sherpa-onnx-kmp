# Platform Matrix

Status: 1.0.0

Two things are recorded here: which platforms are supported, and which features
each one actually ships. The second half is not aspirational — it is what the
library reports from `SherpaOnnxBridgeInfoJson()` at runtime.

## Supported target families

### Android

- KMP target: `androidTarget`
- Transport: JNI
- Minimum SDK: 21
- ABI artifacts: arm64-v8a, armeabi-v7a, x86_64, x86
- Primary tested ABI: arm64-v8a
- Android 16 KB page compatibility is verified by 0x4000 ELF segment alignment
  for the 64-bit native libraries.
- Device suite: API 36 x86_64 emulator. Every ABI is compiled and its exported
  symbols are inspected, but only x86_64 executes on the hosted runner.
- Real-inference evidence for the other seven features comes from the JVM
  suite, which drives the same JNI transport.
- Android below API 29 has no TLS helper in `libc`. The upstream core's only
  `thread_local` lives in `normal-data-generator.cc`, and enabling TTS is what
  first referenced it: with `SHERPA_ONNX_ENABLE_TTS` off, that archive member
  was dropped by `--gc-sections`, so nothing reached the link. Rather than carry
  an emulated-TLS flag through every Android build, `patches/0002` removes the
  TLS reference outright — the holder moves from a function-local
  `thread_local` to an instance member. This also makes the generator's state
  per-instance, which the shared `thread_local` did not.
- No Android bridge imports `__tls_get_addr` or `__emutls_get_address`.
  `scripts/check-android-aar.sh` asserts that for every ABI, so a future
  upstream bump that reintroduces a TLS reference fails the release instead of
  the device. The `libc++_shared.so` dependency is unchanged and still required,
  but for the C++ runtime rather than TLS.

### iOS

- KMP targets: `iosArm64`, `iosSimulatorArm64`
- Transport: Native cinterop
- Deployment target: iOS 15
- Distribution: self-contained `SherpaOnnxKmp.xcframework`
- Providers: CPU
- Tested: iOS Simulator arm64. The device slice builds, links, and signs; see
  the device gate under known limitations.

### macOS

- KMP target: `macosArm64`
- Transport: Native cinterop
- Deployment target: macOS 13
- Distribution: self-contained `SherpaOnnxKmp.xcframework`
- Providers: CPU
- Intel macOS: JVM target, not Kotlin/Native `macosX64`

### Linux

- KMP targets: `linuxX64`, `linuxArm64`
- Transport: Native cinterop
- Distribution: one shared bridge library per architecture
  (`libsherpa-onnx-kmp-native-desktop.so` and its `SONAME` copy
  `libsherpa-onnx-portable-bridge.so`, plus `libonnxruntime.so`)
- Providers: CPU
- `linuxArm64` cross-compiles on x86_64 and is tested under qemu

### Windows

- KMP target: `mingwX64`
- Transport: Native cinterop
- Distribution: `sherpa-onnx-portable-bridge.dll` with static ONNX Runtime
  embedded
- Windows ARM64: JVM target
- See [ADR 0007](adr/0007-native-desktop-linkage.md)

### JVM

- KMP target: `jvm`
- Transport: JNI, shared with Android
- Use cases: Compose Desktop, Java interoperability, Windows ARM64, Intel macOS,
  server-side tools

### Web

- KMP target: Kotlin/JS
- Inference: Emscripten WebAssembly inside a Web Worker
- Not a Kotlin/Wasm wrapper target
- Requires WebAssembly SIMD; `wasmWasi` is out of scope
- Module memory is capped at 2 GiB

## Feature coverage

All ten bridge resources are available on every supported target family. The
table records the transport detail that differs, not feature availability.

| Feature | Kotlin API | Bridge resource | Android | iOS | macOS | Linux | Windows | JVM | Web |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Offline ASR | `createOfflineRecognizer` | `offline-asr` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Streaming ASR | `createOnlineRecognizer` | `online-asr`, `online-session` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VAD | `createVoiceActivityDetector` | `vad` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TTS | `createOfflineTts` | `tts` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Keyword spotting | `createKeywordSpotter` | `kws`, `kws-stream` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Punctuation | `createPunctuation` | `punctuation` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Speaker diarization | `createSpeakerDiarization` | `speaker-diarization` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Speech enhancement | `createSpeechEnhancer` | `speech-denoiser` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

Capabilities are discovered, not assumed:

```kotlin
runtime.capabilities.features // e.g. {OFFLINE_ASR, TTS, VAD, ...}
```

A feature that the native build does not contain is absent from that set, and
creation fails with a typed exception naming the supported set.

### Verified feature evidence

Two suites assert real inference against pinned models, and both now cover all
eight features. The JVM suite drives the JNI transport; the shared
`nativeFeatureTest` source set drives the native cinterop transport on macOS
arm64, the iOS Simulator, Linux, and Windows. They are kept separate because
they exercise different transports, so a feature proven by one is not proven
for the other.

| Feature | Pinned model | Assertion | Real-model suite |
| --- | --- | --- | --- |
| VAD | `silero_vad.onnx` | at least one speech segment in a speech clip | JVM, native |
| Keyword spotting | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` | the configured keyword fires | JVM, native |
| Punctuation | `sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8` | punctuation is inserted | JVM, native |
| Speech enhancement | `gtcrn_simple.onnx` | non-empty output whose duration matches the input | JVM, native |
| Streaming ASR | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` | non-empty transcript, endpoint reached | JVM, native |
| Speaker diarization | pyannote segmentation + `3dspeaker` embedding | two speakers, ordered turns | JVM, native |
| TTS, lexicon | `vits-icefall-zh-aishell3` | audio with samples at the model's sample rate | JVM, native |
| TTS, phonemes | `vits-piper-en_US-amy-low` | audio when codepoints are supplied; error when not | JVM, native |

Android is the one target that does not run per-feature inference assertions
itself: the device suite transcribes one model and checks the packaged `.so`
advertises every feature. That is packaging and transport evidence, not
per-feature evidence. On Linux and Windows the shared native suite now runs
against the pinned models in CI, so it does assert them. Read the table as
"which suite proves this feature runs", not "which platforms ship it".

## Explicitly unsupported

- Kotlin/Native Android targets as public KMP targets
- `wasmWasi`
- Mac Catalyst
- watchOS
- tvOS
- visionOS
- Windows ARM64 Kotlin/Native
- Linux ARM32
- Kotlin/Native `macosX64` as a core release target
- Kotlin/Native `iosX64` as a core release target

## Provider policy

Provider availability is platform-specific. The common API exposes provider
names only as capability-checked strings. Platform-specific NPU configuration
must not leak into `commonMain`.

1.0.0 ships the CPU provider on every platform, including Apple. Core ML is not
enabled: it changes numerics and needs per-model conversion, so it is a future
opt-in rather than a default.

## Known platform limitations

- Physical iOS device execution is a release gate that needs hardware. The
  device slice builds, links, and signs, but the recorded runs are Simulator and
  macOS.
- Physical Android device runs are documented per release; CI uses an emulator.
- `linuxArm64` needs an aarch64 host or cross toolchain. The glibc floor is the
  build host's, because the C++ graph is compiled there even though ONNX Runtime
  is pinned to `glibc2_17`.
- The browser module requires SIMD and is capped at 2 GiB of linear memory.
- Web model loading goes through `HttpModelStore`/`MemoryModelStore` into
  Emscripten MEMFS; there is no partial on-demand paging of a model file.
