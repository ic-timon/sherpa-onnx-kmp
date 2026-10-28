# sherpa-onnx-kmp

Kotlin Multiplatform bindings and a portable native bridge for
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

> Status: v1.0.0 stable. The public API, the Portable Bridge ABI, and the result
> schema are frozen under semantic versioning. See
> [docs/versioning.md](docs/versioning.md) for the compatibility contract and
> [docs/platform-matrix.md](docs/platform-matrix.md) for what each target ships.

## Goal

Provide one typed Kotlin API for local speech inference across:

- Android
- iOS
- Native desktop: macOS arm64, Linux x64/arm64, Windows x64
- JVM desktop
- Web: Kotlin/JS with sherpa-onnx compiled to WebAssembly

The design is intentionally not a one-to-one mirror of the C API. The public API
is task-oriented and async-first so mobile, desktop, and browser implementations
share the same contract.

## Architecture

```text
Typed KMP public API
        |
Common engine: lifecycle, options, results, capabilities
        |
PortableTransport
  |-- JNI: Android and JVM
  |-- Native cinterop: iOS, macOS, Linux, Windows
  `-- Web Worker: Kotlin/JS and Emscripten
        |
Portable Bridge C ABI
        |
sherpa-onnx core
```

The portable bridge is added as a new, additive native target. Existing C,
Java, Kotlin, and Swift APIs remain unchanged.

## Development

```bash
./gradlew :sherpa-onnx-kmp:jvmTest
./gradlew :sherpa-onnx-kmp:jsNodeTest
```

See [Releases](https://github.com/ic-timon/sherpa-onnx-kmp/releases) for published
versions and [docs/roadmap.md](docs/roadmap.md) for the release gates.

## Features

Every feature is available on every supported target family, through one typed
API and one native bridge. Capabilities are discovered at runtime, not assumed.

| Feature | Entry point |
| --- | --- |
| Offline ASR | `createOfflineRecognizer` |
| Streaming ASR | `createOnlineRecognizer` → `openStream`, `push`, `finish` |
| Voice activity detection | `createVoiceActivityDetector` |
| Text to speech | `createOfflineTts` → `Flow<AudioChunk>` |
| Keyword spotting | `createKeywordSpotter` |
| Punctuation restoration | `createPunctuation` |
| Speaker diarization | `createSpeakerDiarization` |
| Speech enhancement | `createSpeechEnhancer` |

```kotlin
val runtime = createSherpaRuntime(modelStore)
val recognizer = runtime.createOfflineRecognizer(config)
val result = recognizer.transcribe(AudioData(samples, 16_000))
println(result.text)
```

```text
runtime.capabilities.features
  -> {OFFLINE_ASR, ONLINE_ASR, VAD, TTS, KEYWORD_SPOTTING,
      PUNCTUATION, SPEAKER_DIARIZATION, SPEECH_ENHANCEMENT}
```

## Apple

- Targets: `iosArm64`, `iosSimulatorArm64`, and `macosArm64`.
- Transport: Kotlin/Native cinterop over the Portable Bridge.
- Distribution: one self-contained XCFramework containing the bridge,
  sherpa-onnx, and ONNX Runtime.
- Minimum deployment targets: iOS 15 and macOS 13.
- Samples: [samples/apple/README.md](samples/apple/README.md)
- Apple details: [docs/apple-transport.md](docs/apple-transport.md)
- Release inventory: [docs/release-0.4.0.md](docs/release-0.4.0.md)

## JVM and Android

- JVM: use the JVM artifact plus the matching native JAR, or set
  `SHERPA_ONNX_KMP_NATIVE_PATH`.
- Android: use the AAR; it contains all four Android ABI native payloads.
- Samples: [samples/README.md](samples/README.md)
- JNI details: [docs/jni-transport.md](docs/jni-transport.md)
- Release inventory: [docs/release-0.3.0.md](docs/release-0.3.0.md)

## Documentation

- [Design](docs/design.md)
- [Version roadmap](docs/roadmap.md)
- [Platform matrix](docs/platform-matrix.md)
- [Versioning and compatibility](docs/versioning.md)
- [Portable Bridge ABI](docs/bridge-abi.md)
- [Migration guide 0.4.0 → 1.0.0](docs/migration-1.0.md)
- [Testing strategy](docs/testing.md)
- [Upstream changes](docs/upstream.md)
- [Current status](docs/status.md)
- [Architecture decision records](docs/adr/README.md)
- [Release 1.0.0 notes](docs/release-1.0.0.md)

## Non-goals

- Kotlin/Native as a public Android KMP target
- Kotlin/Wasm as a second wrapper runtime for the web
- `wasmWasi`
- Windows ARM64 through Kotlin/Native
- Mac Catalyst, watchOS, tvOS, or visionOS
- A complete one-to-one KMP mirror of every C API struct

## License

Apache License 2.0. See [LICENSE](LICENSE).
