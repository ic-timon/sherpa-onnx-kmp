# 0004: Web Runtime

Status: Accepted

Date: 2026-10-27

## Context

sherpa-onnx is already compiled to WebAssembly with Emscripten. Compiling the KMP
wrapper to Kotlin/Wasm would introduce another Wasm runtime and memory space.

## Decision

- The KMP web target is Kotlin/JS.
- Inference runs in the existing Emscripten Wasm build.
- The Emscripten module runs in a dedicated Web Worker.
- Control messages use JSON.
- Audio uses Float32 transferable buffers.
- Kotlin/Wasm wrapper target and `wasmWasi` are not supported.

## Consequences

The web wrapper is simpler and closer to the existing Wasm implementation. The
UI remains responsive. All web operations must fit the async public API.
