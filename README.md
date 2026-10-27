# sherpa-onnx-kmp

Kotlin Multiplatform bindings and a portable native bridge for
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

> Status: v0.1.0 Foundation is released. Native inference transports are next.

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
