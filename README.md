# sherpa-onnx-kmp

Kotlin Multiplatform bindings and a portable native bridge for
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

> Status: v0.4.0 Apple integration is available as a preview.

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
- [Testing strategy](docs/testing.md)
- [Upstream changes](docs/upstream.md)
- [Current status](docs/status.md)
- [Architecture decision records](docs/adr/README.md)

## Non-goals

- Kotlin/Native as a public Android KMP target
- Kotlin/Wasm as a second wrapper runtime for the web
- `wasmWasi`
- Windows ARM64 through Kotlin/Native
- Mac Catalyst, watchOS, tvOS, or visionOS
- A complete one-to-one KMP mirror of every C API struct

## License

Apache License 2.0. See [LICENSE](LICENSE).
