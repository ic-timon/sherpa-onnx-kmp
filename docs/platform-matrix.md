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
- Device suite: API 36.1 arm64 emulator.
- `armeabi-v7a`, `x86`, and `x86_64` link with emulated TLS. NDK `libc` at API
  21 does not export `__tls_get_addr` for those ABIs, so `thread_local` in the
  upstream core is lowered to `__emutls_get_address`; the bridge passes the
  flag through to the LTO plugin as well, because the link-time optimizer
  otherwise re-materializes the native model. `check-android-aar.sh` asserts the
  symbol shape per ABI so a regression fails the release instead of the device.

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

Real-model suites run on the JVM, macOS arm64, and the iOS Simulator from the
shared `nativeFeatureTest` source set. Android and the native desktops share the
same bridge, and the Android device suite plus the desktop CI jobs cover them.

| Feature | Pinned model | Assertion |
| --- | --- | --- |
| VAD | `silero_vad.onnx` | at least one speech segment in a speech clip |
| Keyword spotting | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` | the configured keyword fires |
| Punctuation | `sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8` | punctuation is inserted |
| Speech enhancement | `gtcrn_simple.onnx` | output length equals input length |
| Streaming ASR | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` | non-empty transcript, endpoint reached |
| Speaker diarization | pyannote segmentation + `3dspeaker` embedding | two speakers, ordered turns |
| TTS, lexicon | `vits-icefall-zh-aishell3` | audio with samples at the model's sample rate |
| TTS, phonemes | `vits-piper-en_US-amy-low` | audio when codepoints are supplied; error when not |

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
