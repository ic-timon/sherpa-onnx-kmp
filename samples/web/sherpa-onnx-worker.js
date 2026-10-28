/**
 * sherpa-onnx Web Worker.
 *
 * Owns the Emscripten module and serializes every bridge call so the page main
 * thread never blocks. Protocol (see docs/web-transport.md):
 *
 *   main -> worker   { id, type: "info" | "create" | "call" | "pushAudio" |
 *                      "pullAudio" | "destroy" | "models.prepare", ... }
 *   worker -> main   { id, type: "reply", ok: bool, result?: string,
 *                      message?: string }
 *   worker -> main   { type: "ready" } | { type: "fatal", message }
 *
 * Audio arrives as a transferable Float32Array buffer, not a cloned array.
 */

/* eslint-disable no-undef */

let modulePromise = null;
let moduleGlobal = null;

function createModule() {
  if (modulePromise) return modulePromise;
  modulePromise = new Promise((resolve, reject) => {
    importScripts("sherpa-onnx-bridge.js");
    if (typeof createSherpaOnnxBridgeModule !== "function") {
      reject(new Error("sherpa-onnx bridge factory was not registered"));
      return;
    }
    createSherpaOnnxBridgeModule().then(resolve, reject);
  });
  return modulePromise;
}

function reply(id, result) {
  postMessage({ id, type: "reply", ok: true, result });
}

/**
 * Replies with audio pulled from the bridge.
 *
 * The samples are copied out of the Wasm heap into a fresh `Float32Array` and
 * the backing buffer is transferred, so the page receives the waveform without
 * a structured clone. The bridge's buffer is freed before the transfer.
 */
function replyAudio(id, result, sampleRate, numSamples, samplesPtr) {
  const message = { id, type: "reply", ok: true, result };
  let buffer = null;
  if (samplesPtr && numSamples > 0) {
    const samples = new Float32Array(numSamples);
    samples.set(moduleGlobal.HEAPF32.subarray(
      samplesPtr >> 2,
      (samplesPtr >> 2) + numSamples,
    ));
    moduleGlobal._SherpaOnnxBridgeFreeAudio(samplesPtr);
    message.audio = { samples, sampleRate };
    buffer = samples.buffer;
  }
  if (buffer) {
    postMessage(message, [buffer]);
  } else {
    postMessage(message);
  }
}

function fail(id, message) {
  postMessage({ id, type: "reply", ok: false, message: String(message) });
}

function readAndFree(module, pointer) {
  if (!pointer) return "";
  const value = module.UTF8ToString(pointer);
  module._SherpaOnnxBridgeFree(pointer);
  return value;
}

/**
 * Copies a JS string into the Wasm heap as a NUL-terminated UTF-8 buffer.
 *
 * The exported `_SherpaOnnxBridge*` entry points take `const char *`, and the
 * Emscripten glue does not convert a JS string for a direct `_function` call
 * (only `ccall` does). Passing the string straight through hands the callee a
 * coerced number, which the bridge reports as a null argument. The buffer is
 * allocated before touching `HEAPU8` because `_malloc` may grow memory and
 * detach the previous view.
 */
function toCString(module, value) {
  const text = String(value ?? "");
  if (typeof module.stringToUTF8 === "function" &&
      typeof module.lengthBytesUTF8 === "function") {
    const size = module.lengthBytesUTF8(text) + 1;
    const pointer = module._malloc(size);
    module.stringToUTF8(text, pointer, size);
    return pointer;
  }
  const bytes = new TextEncoder().encode(text);
  const pointer = module._malloc(bytes.length + 1);
  // Only `HEAPF32` is exported to the module object, so the byte view is
  // derived from its buffer. `HEAPU8` is used when a future build exports it.
  const heap = module.HEAPU8 ?? new Uint8Array(module.HEAPF32.buffer);
  if (pointer < 0 || pointer + bytes.length + 1 > heap.length) {
    throw new Error(
      "Cannot write a C string at " +
        pointer +
        " (length " +
        (bytes.length + 1) +
        ", heap " +
        heap.length +
        ")",
    );
  }
  heap.set(bytes, pointer);
  heap[pointer + bytes.length] = 0;
  return pointer;
}

/** Ensures the MEMFS directory exists, creating parents as needed. */
function ensureDirectory(module, path) {
  const parts = path.split("/").filter(Boolean);
  let current = "";
  for (const part of parts) {
    current += "/" + part;
    try {
      module.FS.mkdir(current);
    } catch (error) {
      if (!error || error.errno !== 20) throw error; // 20 == EEXIST
    }
  }
}

async function fetchModel(module, baseUrl, mountPath, name) {
  const target = mountPath + "/" + name;
  const parent = target.substring(0, target.lastIndexOf("/"));
  ensureDirectory(module, parent);

  const response = await fetch(baseUrl + "/" + name, { cache: "force-cache" });
  if (!response.ok) {
    throw new Error(
      "Model download failed (" + response.status + ") for " + name,
    );
  }
  const bytes = new Uint8Array(await response.arrayBuffer());
  module.FS.writeFile(target, bytes);

  const verify = module.FS.stat(target);
  if (verify.size !== bytes.length) {
    throw new Error(
      "Model write verification failed for " +
        name +
        ": expected " +
        bytes.length +
        " bytes, found " +
        verify.size,
    );
  }
  return target;
}

async function handleModelsPrepare(module, payload) {
  const mountPath = payload.mountPath;
  if (!payload.baseUrl || !mountPath) {
    throw new Error("models.prepare requires baseUrl and mountPath");
  }
  ensureDirectory(module, mountPath);
  for (const name of payload.files || []) {
    if (name.includes("..")) {
      throw new Error("Model path must not contain '..': " + name);
    }
    await fetchModel(module, payload.baseUrl, mountPath, name);
  }
  return mountPath;
}

function requireModule() {
  if (!moduleGlobal) {
    throw new Error("Emscripten module is not initialized");
  }
  return moduleGlobal;
}

self.onmessage = async (event) => {
  const payload = event.data || {};
  const id = payload.id;
  const type = payload.type;

  try {
    await createModule();
    const module = requireModule();

    switch (type) {
      case "info": {
        reply(id, readAndFree(module, module._SherpaOnnxBridgeInfoJson()));
        break;
      }
      case "create": {
        const handlePtr = module._malloc(8);
        const errorPtr = module._malloc(4);
        module.setValue(errorPtr, 0, "i32");
        const kindPtr = toCString(module, payload.kind);
        const optionsPtr = toCString(module, payload.optionsJson ?? "{}");
        try {
          const status = module._SherpaOnnxBridgeCreate(
            kindPtr,
            optionsPtr,
            handlePtr,
            errorPtr,
          );
          const errorValue = module.getValue(errorPtr, "i32");
          if (status !== 0) {
            throw new Error(
              errorValue
                ? readAndFree(module, errorValue)
                : "Bridge create failed with status " + status,
            );
          }
          if (errorValue) readAndFree(module, errorValue);
          reply(id, String(module.getValue(handlePtr, "i64")));
        } finally {
          module._free(kindPtr);
          module._free(optionsPtr);
          module._free(handlePtr);
          module._free(errorPtr);
        }
        break;
      }
      case "call": {
        const resultPtr = module._malloc(4);
        const errorPtr = module._malloc(4);
        module.setValue(resultPtr, 0, "i32");
        module.setValue(errorPtr, 0, "i32");
        const operationPtr = toCString(module, payload.operation);
        const argumentsPtr = toCString(module, payload.argumentsJson ?? "{}");
        try {
          const status = module._SherpaOnnxBridgeCall(
            BigInt(payload.handle),
            operationPtr,
            argumentsPtr,
            resultPtr,
            errorPtr,
          );
          const errorValue = module.getValue(errorPtr, "i32");
          if (status !== 0) {
            throw new Error(
              errorValue
                ? readAndFree(module, errorValue)
                : "Bridge call failed with status " + status,
            );
          }
          if (errorValue) readAndFree(module, errorValue);
          reply(id, readAndFree(module, module.getValue(resultPtr, "i32")));
        } finally {
          module._free(operationPtr);
          module._free(argumentsPtr);
          module._free(resultPtr);
          module._free(errorPtr);
        }
        break;
      }
      case "pushAudio": {
        const samples = payload.samples;
        if (!(samples instanceof Float32Array)) {
          throw new Error("pushAudio requires a Float32Array");
        }
        const byteLength = Math.max(samples.length * 4, 4);
        const samplesPtr = module._malloc(byteLength);
        const errorPtr = module._malloc(4);
        module.setValue(errorPtr, 0, "i32");
        const operationPtr = toCString(module, payload.operation);
        try {
          module.HEAPF32.set(samples, samplesPtr >> 2);
          const status = module._SherpaOnnxBridgePushAudio(
            BigInt(payload.handle),
            operationPtr,
            payload.sampleRate,
            samplesPtr,
            payload.numSamples,
            errorPtr,
          );
          const errorValue = module.getValue(errorPtr, "i32");
          if (status !== 0) {
            throw new Error(
              errorValue
                ? readAndFree(module, errorValue)
                : "Bridge pushAudio failed with status " + status,
            );
          }
          if (errorValue) readAndFree(module, errorValue);
          reply(id, "{}");
        } finally {
          module._free(operationPtr);
          module._free(samplesPtr);
          module._free(errorPtr);
        }
        break;
      }
      case "pullAudio": {
        // [handle, operation, argumentsJson, sampleRate*, samples*, numSamples*,
        //  resultJson*, error*]
        const sampleRatePtr = module._malloc(4);
        const samplesPtrPtr = module._malloc(4);
        const numSamplesPtr = module._malloc(4);
        const resultPtr = module._malloc(4);
        const errorPtr = module._malloc(4);
        module.setValue(sampleRatePtr, 0, "i32");
        module.setValue(samplesPtrPtr, 0, "i32");
        module.setValue(numSamplesPtr, 0, "i32");
        module.setValue(resultPtr, 0, "i32");
        module.setValue(errorPtr, 0, "i32");
        const operationPtr = toCString(module, payload.operation);
        const argumentsPtr = toCString(module, payload.argumentsJson ?? "{}");
        try {
          const status = module._SherpaOnnxBridgePullAudio(
            BigInt(payload.handle),
            operationPtr,
            argumentsPtr,
            sampleRatePtr,
            samplesPtrPtr,
            numSamplesPtr,
            resultPtr,
            errorPtr,
          );
          const errorValue = module.getValue(errorPtr, "i32");
          if (status !== 0) {
            throw new Error(
              errorValue
                ? readAndFree(module, errorValue)
                : "Bridge pullAudio failed with status " + status,
            );
          }
          if (errorValue) readAndFree(module, errorValue);
          const json = readAndFree(module, module.getValue(resultPtr, "i32"));
          replyAudio(
            id,
            json,
            module.getValue(sampleRatePtr, "i32"),
            module.getValue(numSamplesPtr, "i32"),
            module.getValue(samplesPtrPtr, "i32"),
          );
        } finally {
          module._free(operationPtr);
          module._free(argumentsPtr);
          module._free(sampleRatePtr);
          module._free(samplesPtrPtr);
          module._free(numSamplesPtr);
          module._free(resultPtr);
          module._free(errorPtr);
        }
        break;
      }
      case "destroy": {
        module._SherpaOnnxBridgeDestroy(BigInt(payload.handle));
        reply(id, "{}");
        break;
      }
      case "models.prepare": {
        reply(id, await handleModelsPrepare(module, payload));
        break;
      }
      default:
        throw new Error("Unknown worker message type: " + type);
    }
  } catch (error) {
    fail(id, error && error.message ? error.message : error);
    console.error(
      "worker error",
      type,
      error && error.stack ? error.stack : error,
    );
  }
};

createModule().then(
  (module) => {
    moduleGlobal = module;
    postMessage({ type: "ready" });
  },
  (error) =>
    postMessage({
      type: "fatal",
      message: error && error.message ? error.message : String(error),
    }),
);
