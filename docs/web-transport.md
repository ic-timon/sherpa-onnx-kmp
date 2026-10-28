# Web Transport

Status: v1.0.0

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

The script builds the `sherpa-onnx-bridge-web` target with `emcmake` and links a
`MODULARIZE` module named `createSherpaOnnxBridgeModule`. The export list is
fixed, and the script fails if any bridge entry point is missing from the
generated JS glue, so capability discovery cannot silently drift from the actual
export set.

At `-O3` Emscripten renames the internal Wasm export table to single letters.
That is expected and harmless: the rename pass rewrites the generated glue in
lockstep, and the glue keeps publishing the stable `Module['_SherpaOnnxBridge*']`
names. Those names are the contract — they are what the worker calls. The
settings that control the rename (`MINIFY_WASM_IMPORTS_AND_EXPORTS`,
`MINIFY_WASM_EXPORT_NAMES`) are Emscripten-internal and rejected on the command
line, so the build verifies the glue surface instead of the raw export table.

## Worker deployment

`HttpModelStore` and the transport expect the worker at
`sherpa-onnx-worker.js` relative to the page. Override it when serving from a
versioned path:

```kotlin
configureSherpaWebWorker("/assets/sherpa-onnx-worker.js")
```

The worker script itself lives at `samples/web/sherpa-onnx-worker.js` and must
be deployed next to `sherpa-onnx-bridge.js` and `sherpa-onnx-bridge.wasm`.

### Sample bundle

`samples:web` is compiled as an ES module (`JsModuleKind.MODULE_ES`), so its
production bundle can be imported directly by the page's
`<script type="module">`:

```bash
./gradlew :samples:web:jsProductionLibraryDistribution
```

The distribution in `samples/web/build/dist/js/productionLibrary` writes both a
`.js` and a `.mjs` for every module; the `.mjs` variants are the ES modules. The
entry point is `sherpa-onnx-kmp-samples-web.mjs`, which exports `runSample`:

```javascript
import { runSample } from "./sherpa-onnx-kmp-samples-web.mjs";
```

Every `.mjs` file it imports must be served beside it. Copying only the entry
bundle yields a page that fails on its first `import`, which is why the CI
assembly step copies the whole `*.mjs` set instead of a single file.

## Capabilities

`SherpaOnnxBridgeInfoJson` is produced by the same bridge code as the native
targets. The Emscripten build enables TTS and speaker diarization, so the browser
reports the same ten resources and 35 operations as the native libraries.

That is asserted, not assumed: `scripts/build-web-emscripten.sh` runs
`scripts/check-web-capabilities.cjs`, which loads the built module in a
worker-shaped context, reads the capability JSON, and fails if any expected
resource or operation is missing. The upstream configure gates TTS and
diarization behind `SHERPA_ONNX_ENABLE_TTS` and
`SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION`, so a dropped `-D` flag would otherwise
produce a module that still builds and runs, just with a smaller feature set.

A feature absent from the Wasm build fails at creation time with
`SherpaUnsupportedException` instead of silently degrading.

## Limits

- `wasmWasi` is out of scope.
- Node.js can run the contract suite but cannot run the worker; the worker path
  requires a browser `Worker` and `importScripts`.
- Memory is capped at 2 GiB through `MAXIMUM_MEMORY`; large models may need a
  bigger initial memory setting.
