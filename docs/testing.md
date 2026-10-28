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
- Apple Silicon macOS
- macOS arm64
- Linux x64/arm64
- Windows x64
- JVM desktop
- Chrome and Firefox for web
- Node used for non-browser Wasm smoke tests

### Packaging

- Android AAR contents and duplicate-library detection
- Apple XCFramework slices, code signatures, symbol counts, and dependency audit
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

## Release evidence

These are not unit tests: they assert properties a behavioral test cannot see.
See [`releases.md`](releases.md) for the table of scripts.

### Capability parity

Each transport reads its resource list from the native build rather than from a
compiled-in constant. `scripts/test-bridge-abi.sh` checks the desktop library,
`scripts/check-web-capabilities.cjs` loads the built Emscripten module, and the
Android AAR check inspects the packaged `.so` for exported symbols. A build that
omits a feature fails a release gate instead of failing at a call site.

### Memory growth

`scripts/check-release-hardening.sh` runs a separate process per data point,
synthesizes a fixed number of times, and compares peak RSS at two warm points
past ONNX Runtime's arena plateau. Comparing a cold single iteration against a
long run would report the arena as a leak on every release.

### ABI agreement

The header's `SHERPA_ONNX_BRIDGE_ABI_VERSION` and the library's runtime answer
are compared, so a header edit that does not reach the built library fails
immediately.

## Current host notes

The current development host is an Apple Silicon Mac with:

- Xcode and iOS simulators
- Android SDK/NDK and arm64 emulators
- Node, Chrome, and Playwright
- The Emscripten SDK (4.0.11)

It can directly test the Android emulator, the iOS simulator, macOS arm64 native,
JVM, and web. It cannot run Windows or Linux native builds, and it has no physical
mobile device. The Apple device slice is compiled and linked, but physical iPhone
execution remains a documented release gate. Emscripten must be installed before
web integration builds.

Real feature-model suites need both model directories:

```bash
export SHERPA_ONNX_KMP_TEST_MODEL_DIR=~/.cache/sherpa-onnx-kmp/models/<pinned-asr>
export SHERPA_ONNX_KMP_FEATURE_MODEL_DIR=~/.cache/sherpa-onnx-kmp/feature-models
./scripts/prepare-feature-models.sh
```
