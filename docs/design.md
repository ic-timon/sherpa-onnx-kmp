# Terminal Architecture

## 1. Objectives

The project provides one Kotlin Multiplatform API for local speech inference
with sherpa-onnx while avoiding duplicated feature implementations across
platforms.

The terminal design must support:

- Android
- iOS
- macOS arm64
- Linux x64 and arm64
- Windows x64
- JVM desktop
- Browser WebAssembly

The design optimizes for long-term source reuse and stable behavior, not for the
smallest initial implementation.

## 2. Key constraints

### Android is a JVM target

The normal KMP Android application/library target runs on ART and uses JNI.
Kotlin/Native Android targets produce shared libraries, not a standard Android
KMP app target. Making Android use Kotlin/Native would add a JNI boundary around
a Kotlin/Native runtime and then another boundary to sherpa-onnx.

Decision: Android uses `androidTarget` plus a JNI transport.

### Kotlin/Native desktop is the native desktop path

iOS, macOS arm64, Linux, and Windows x64 share one Kotlin/Native cinterop
transport. Platform-specific code is limited to linking and packaging.

JVM remains a separate target for Compose Desktop, Windows ARM64, Intel macOS,
Java interoperability, and server-side use. It shares the JNI transport with
Android.

### Web uses Emscripten, not cinterop

sherpa-onnx already builds to WebAssembly with Emscripten. Kotlin/JS is used for
the KMP web wrapper and communicates with an Emscripten module running in a Web
Worker.

Kotlin/Wasm is not used for the wrapper because that would add a second Wasm
runtime and a second Wasm memory while the inference engine is already Wasm.

## 3. Layering

```text
Public typed API
    |
Common engine
    |
PortableTransport
    |-- JNI transport
    |-- Native cinterop transport
    `-- Web Worker transport
    |
Portable Bridge C ABI
    |
sherpa-onnx core
```

### Public typed API

The public API is task-oriented:

- transcribe audio
- push streaming audio
- generate speech
- detect speech segments
- detect keywords
- diarize audio

It is not a one-to-one mirror of the C API.

### Common engine

`commonMain` owns:

- configuration validation
- public lifecycle state
- option-map generation
- result-map parsing
- stream/session semantics
- capability checks
- serialized command ordering
- retry policy
- error normalization

### PortableTransport

The transport is intentionally small and feature-independent. It creates and
destroys handles, sends control commands, and moves Float32 audio buffers.

The same common engine is used by every platform.

### Portable Bridge

The bridge is one additive C++ target that links against the sherpa-onnx core.
It is not a replacement for the existing C API.

## 4. Portable Bridge ABI

### Control plane

Configuration is represented as a flat JSON object whose values are strings.
Keys are the option names registered by each sherpa-onnx config class.

Example:

```json
{
  "paraformer": "/models/model.int8.onnx",
  "tokens": "/models/tokens.txt",
  "num-threads": "2",
  "provider": "cpu"
}
```

The bridge constructs the appropriate config, calls `Register(&po)`, and applies
each key through a non-fatal `TrySetOption` variant. It then calls `Validate()`.

This avoids reimplementing every config field in Android, Apple, JVM, and JS
bindings.

### Data plane

Audio is transferred as raw `float32` buffers, never as JSON.

Operations include:

- push audio
- pull generated audio
- pull speech segments
- pull embeddings

### Result plane

Metadata and results use versioned JSON. Recognizer results can reuse existing
JSON serialization where appropriate. Every typed result also keeps a `rawJson`
field as a forward-compatible escape hatch.

### Handles

The bridge exposes opaque `uint64_t` handles. Native pointers never cross into
Kotlin.

Bridge responsibilities:

- handle registry
- type/lifecycle validation
- thread-safe serialization
- error envelopes
- stable result schema
- buffer ownership and freeing

## 5. Transport implementations

### JNI

Used by Android and JVM desktop.

Responsibilities:

- load the correct native library
- expose bridge calls through a small JNI surface
- marshal Float32 arrays
- share the same bridge operations as the native targets

### Native cinterop

Used by iOS, macOS, Linux, and Windows x64.

Responsibilities:

- import the bridge header
- link the appropriate native library
- pin Float32 arrays for bridge calls
- convert bridge JSON and buffers into common types

No feature-specific native code belongs in this layer.

### Web Worker

Used by the Kotlin/JS target.

Responsibilities:

- initialize the Emscripten module
- persist models in the Emscripten virtual filesystem
- serialize bridge operations
- transfer Float32 buffers with transferable ArrayBuffers
- deliver TTS and VAD events
- terminate or recreate the worker on fatal errors

## 6. Public API

All operations that can block or cross the worker boundary are `suspend`.
Streaming output uses `Flow`.

```kotlin
public interface SherpaResource {
    public suspend fun close()
}

public interface SherpaRuntime : SherpaResource {
    public val capabilities: SherpaCapabilities

    public suspend fun createOfflineRecognizer(
        config: OfflineRecognizerConfig,
    ): OfflineRecognizer

    public suspend fun createOnlineRecognizer(
        config: OnlineRecognizerConfig,
    ): OnlineRecognizer

    public suspend fun createVad(config: VadConfig): VoiceActivityDetector

    public suspend fun createTts(config: OfflineTtsConfig): OfflineTts
}
```

### Lifecycle rules

- Handles are hidden.
- `close()` is idempotent.
- Parent resources close child resources.
- Operations after close fail explicitly.
- Native resources are not released by finalizers or GC.
- A runtime serializes commands so behavior is consistent across platforms.

### Extension points

Typed configs cover common options. Every config has:

```kotlin
val extraOptions: Map<String, String> = emptyMap()
```

This allows newly added sherpa-onnx options to be used without a KMP release.

Results contain:

```kotlin
val rawJson: String
```

This allows newly added result fields to be consumed before typed support lands.

## 7. Capabilities

Platforms differ in available providers and features. The runtime exposes a
capability set:

```kotlin
public data class SherpaCapabilities(
    val features: Set<SherpaFeature>,
    val providers: Set<String>,
    val supportsStreaming: Boolean,
    val supportsCancellation: Boolean,
)
```

Unsupported features or providers fail at creation time with a typed exception.
There is no silent fallback.

## 8. Dependency boundaries

`commonMain` must not contain:

- Android framework types
- Java IO or NIO
- Apple Foundation types
- native pointers
- Emscripten globals
- worker APIs

Audio capture, audio playback, permissions, and UI remain platform adapters.
The core library consumes and produces Float32 PCM.

## 9. Build and distribution

- Android: AAR containing the JNI transport and required native libraries.
- Apple: XCFramework with static bridge, sherpa-onnx, and ONNX Runtime.
- Linux native: one aggregate static archive per architecture.
- Windows native: `sherpa-onnx-portable-bridge.dll` with static ONNX Runtime.
- JVM: JNI transport plus platform native-library jars.
- Web: Kotlin/JS artifact plus Emscripten Wasm and worker assets.

Versioning must bind the KMP library, bridge ABI, and sherpa-onnx core version.
