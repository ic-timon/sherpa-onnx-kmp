# Release 0.6.0: Web

Status: complete. Shipped in v1.0.0. No 0.6.0 tag or release was cut; this
document records the scope as it was designed and delivered on `main`.

## Scope

The web target runs the same Portable Bridge inside an Emscripten WebAssembly
module owned by a dedicated Web Worker. Kotlin/JS never touches the Wasm heap
from the page main thread, so inference cannot stall the UI.

## Artifacts

- `sherpa-onnx-bridge.js` — Emscripten glue exposing the `SherpaOnnxBridge*`
  C ABI plus the runtime helpers the worker needs.
- `sherpa-onnx-bridge.wasm` — sherpa-onnx core and ONNX Runtime compiled to
  WebAssembly, with SIMD enabled.
- `sherpa-onnx-worker.js` — worker entry point implementing the message protocol
  in [web transport](../docs/web-transport.md).
- Kotlin/JS production bundle (`*.mjs`) for the sample page.

## What the browser e2e proves

`scripts/test-web-browser.sh` serves the assembled sample, drives a headless
browser with Playwright, and fails unless the pinned model returns a non-empty
transcript. The transcript is not asserted verbatim — the same bar the native
and Android suites use.

Recorded on 2026-10-28 with Chrome (Playwright 1.56.1, `channel: "chrome"`):

```text
transcript: اما بزرگ بابا بزرگ دیگه هیچ وقت حااجه میاد کاری حرف زد و این
            بللبشون شی خیلییاتیه که مرا چ هیچ وقت خشک کنار نیومده
browser end-to-end: OK
```

The pinned model is the same Persian NeMo CTC int8 archive the JVM, Android,
and Apple suites use, so the transcript is directly comparable.

## Defects found and fixed by running it

The browser run was the first time the worker exercised a real inference, and
it exposed three defects that unit tests could not reach. All three are fixed
and are now covered by build-time assertions.

1. **JS strings were passed straight to `const char *` parameters.**
   A direct `Module["_SherpaOnnxBridgeCreate"](...)` call does not convert a JS
   string the way `ccall` does; the callee received a coerced number and the
   bridge answered `kind and handle must not be null`. The worker now writes a
   NUL-terminated UTF-8 buffer with `stringToUTF8`/`lengthBytesUTF8` and frees it
   in a `finally` block.
2. **The Emscripten build did not export those helpers.** `HEAPU8`,
   `stringToUTF8`, and `lengthBytesUTF8` were absent from
   `EXPORTED_RUNTIME_METHODS`, so the worker had no portable way to write into
   the heap. They are exported now, and `scripts/build-web-emscripten.sh` fails
   if any of them disappears from the glue.
3. **The module used Emscripten's default stack and a split 64-bit ABI.**
   Real inference walked past the 64 KiB stack and aborted with
   `memory access out of bounds`. The bridge now links with
   `-sSTACK_SIZE=10485760` and `-sWASM_BIGINT=1`, matching upstream's own
   WebAssembly targets. The bridge handle is a `uint64_t`, so the BigInt ABI is
   also what keeps the handle intact across the JS boundary.

## Verification

- `:sherpa-onnx-kmp:jsNodeTest` and `:sherpa-onnx-kmp:jsBrowserTest` pass.
- `scripts/build-web-emscripten.sh` inspects the glue for all nine bridge entry
  points and the seven runtime helpers.
- The same Emscripten module transcribes the pinned model under Node with a
  hand-written driver, which isolates Wasm faults from worker protocol faults.
- Chrome transcribes the pinned model through the full worker path.
- Firefox is supported by the same code path but was not recorded on this host;
  the Playwright driver accepts `SHERPA_ONNX_KMP_WEB_BROWSER=firefox`.

## Reproduction

```bash
# Requires the Emscripten SDK (4.0.11 verified) on PATH.
source /path/to/emsdk/emsdk_env.sh
./scripts/build-web-emscripten.sh
./gradlew :samples:web:jsBrowserProductionLibraryDistribution

dist=samples/web/dist
lib=samples/web/build/dist/js/productionLibrary
mkdir -p "$dist"
cp -f build/web/sherpa-onnx-bridge.js build/web/sherpa-onnx-bridge.wasm "$dist/"
cp -f samples/web/sherpa-onnx-worker.js samples/web/index.html "$dist/"
cp -f "$lib"/*.mjs "$dist/"

# Stage the pinned model, then drive a browser.
./scripts/test-web-browser.sh "$dist"
```

Set `SHERPA_ONNX_KMP_WEB_CHANNEL=chrome` to reuse a system Chrome instead of
downloading Playwright's bundled Chromium, and
`SHERPA_ONNX_KMP_WEB_EXPECTED_TEXT` to assert a recorded transcript verbatim.

## Known limitations

- `wasmWasi` remains out of scope.
- The Wasm module is capped at 2 GiB (`MAXIMUM_MEMORY`); larger models need the
  limit raised at build time.
- Node.js can run the module but not the worker, so the worker path is
  browser-only.
- SIMD is required. Browsers without WebAssembly SIMD are not supported.
