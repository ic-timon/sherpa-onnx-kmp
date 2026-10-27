# Testing Strategy

## Contract-first testing

All transports must satisfy the same common contract tests. The test suite is
organized by behavior, not by platform.

Core scenarios:

1. Version and capability discovery
2. Offline ASR result mapping
3. Online ASR streaming updates and endpointing
4. VAD segment extraction
5. TTS generation and sample rate
6. Lifecycle idempotence
7. Parent/child resource ownership
8. ModelStore resolution
9. Invalid options and provider capability failures
10. Raw JSON fallback fields

## Test layers

### Common unit tests

Run without native inference:

- option-map generation
- result parsing
- lifecycle state
- capability checks
- fake transport behavior

### Bridge tests

C++ tests for:

- flat option parsing
- `TrySetOption` error handling
- handle type checking
- handle lifecycle
- buffer ownership
- stable result schema
- ABI version negotiation

### Platform integration

- Android emulator and real device
- iOS simulator and real device
- macOS arm64
- Linux x64/arm64
- Windows x64
- JVM desktop
- Chrome and Firefox for web
- Node used for non-browser Wasm smoke tests

### Packaging

- Android AAR contents and duplicate-library detection
- Apple XCFramework embedding and code signing
- Linux/Windows runtime dependency inspection
- JVM native-library extraction
- Web MIME types, worker loading, and Wasm asset integrity

## Model assets

Models are not committed. Test scripts download pinned model archives and verify
checksums. Cache location:

```text
~/.cache/sherpa-onnx-kmp/models/
```

## Current host notes

The current development host is an Apple Silicon Mac with:

- Xcode and iOS simulators
- Android SDK/NDK and arm64 emulators
- Node, Chrome, Firefox, and Playwright

It can directly test Android emulator, iOS simulator, macOS arm64 native, JVM,
and web. It cannot run Windows, Linux native, or physical mobile devices.
Emscripten must be installed before web integration builds.
