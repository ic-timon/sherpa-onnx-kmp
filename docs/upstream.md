# Required Upstream Changes

No upstream issue has been opened yet.

## Additive changes

The following changes are proposed for `k2-fsa/sherpa-onnx`:

1. Add a Portable Bridge C ABI as a new CMake library target.
2. Add a non-fatal option parser path equivalent to `TrySetOption`.
3. Expose offline/online ASR, VAD, TTS, KWS, punctuation, diarization, and
   speech-enhancement bridge operations as capabilities allow.
4. Add a thin JNI shim for Android and JVM.
5. Export bridge entry points from the Emscripten web build.
6. Add packaging hooks for Android, Apple, Linux, and Windows artifacts.

## Constraints

- Existing C API must remain unchanged.
- Existing Java, Kotlin, Swift, and other bindings must remain unchanged.
- The bridge may reuse core configuration classes but must expose a versioned
  stable ABI and schema.
- Bridge features not available on a target must be reported through the
  capability protocol.

## Upstream coordination

The intended upstream surface is:

- One RFC/tracking issue linking to this repository's architecture
- Small implementation PRs for the bridge, parser, JNI, and Emscripten pieces
- No full KMP implementation in the upstream repository
