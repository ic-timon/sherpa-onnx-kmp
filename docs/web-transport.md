# Web Transport

Status: v0.6.0

## Targets

- `js` (Kotlin/JS), browser and Node.js

Inference runs in the Emscripten WebAssembly build, never on the page main
thread. See [ADR 0004](adr/0004-web-runtime.md).

## Architecture

```text
Kotlin common engine
  -> WebWorkerTransport            (jsMain)
  -> postMessage / structured JSON
     -> sherpa-onnx-worker.js      (dedicated Web Worker)
        -> Emscripten module       (sherpa-onnx-bridge.js + .wasm)
           -> Portable Bridge C ABI
              -> sherpa-onnx core compiled with Emscripten
```

The worker serializes every bridge call. Requests are matched to replies by
numeric id, so concurrent callers suspend independently.

## Worker protocol

Main thread to worker:

```text
{ id, type: "info" }
{ id, type: "create", kind, optionsJson }
{ id, type: "call", handle, operation, argumentsJson }
{ id, type: "pushAudio", handle, operation, sampleRate, numSamples, samples }
{ id, type: "destroy", handle }
{ id, type: "models.prepare", baseUrl, mountPath, files }
```

Worker to main thread:

```text
{ type: "ready" }
{ type: "fatal", message }
{ id, type: "reply", ok: true, result }
{ id, type: "reply", ok: false, message }
```

Handles cross the boundary as decimal strings because `uint64` cannot be carried
in JSON as a number without precision loss.

## Audio transfer

Audio uses a transferable `Float32Array` buffer, not a structured clone:

```kotlin
val buffer = samples.buffer
worker.postMessage(payload, arrayOf(buffer))
```

The buffer is moved to the worker, so the sending side's array becomes detached.
This avoids copying a full recording across the boundary.

## Model loading

```kotlin
public class HttpModelStore(baseUrl: String, mountPath: String = "/models")
public class MemoryModelStore(rootPath: String)
```

`HttpModelStore` downloads each requested file in the worker and writes it into
MEMFS, verifying the written size matches the downloaded byte count. A file
already present at the mount path is rewritten with `fetch(..., {cache:
"force-cache"})`, so repeated runtime creation does not refetch over the
network.

## Build

```bash
# Requires the Emscripten SDK on PATH.
./scripts/build-web-emscripten.sh
```

Outputs:

```text
build/web/sherpa-onnx-bridge.js
build/web/sherpa-onnx-bridge.wasm
```

The script compiles `sherpa-onnx-portable-bridge-static` with `emcmake` and then
links the archive into a `MODULARIZE` module named
`createSherpaOnnxBridgeModule`. The export list is fixed, and the script fails
if any bridge entry point is missing from the archive, so capability discovery
cannot silently drift from the actual export set.

## Worker deployment

`HttpModelStore` and the transport expect the worker at
`sherpa-onnx-worker.js` relative to the page. Override it when serving from a
versioned path:

```kotlin
configureSherpaWebWorker("/assets/sherpa-onnx-worker.js")
```

The worker script itself lives at `samples/web/sherpa-onnx-worker.js` and must
be deployed next to `sherpa-onnx-bridge.js` and `sherpa-onnx-bridge.wasm`.

## Capabilities

`SherpaOnnxBridgeInfoJson` is produced by the same bridge code as the native
targets, so the reported feature set reflects the Emscripten export list. A
feature absent from the Wasm build fails at creation time with
`SherpaUnsupportedException` instead of silently degrading.

## Limits

- `wasmWasi` is out of scope.
- Node.js can run the contract suite but cannot run the worker; the worker path
  requires a browser `Worker` and `importScripts`.
- Memory is capped at 2 GiB through `MAXIMUM_MEMORY`; large models may need a
  bigger initial memory setting.
