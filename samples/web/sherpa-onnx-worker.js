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

function fail(id, message) {
  postMessage({ id, type: "reply", ok: false, message: String(message) });
}

function readAndFree(module, pointer) {
  if (!pointer) return "";
  const value = module.UTF8ToString(pointer);
  module._SherpaOnnxBridgeFree(pointer);
  return value;
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
        try {
          const status = module._SherpaOnnxBridgeCreate(
            payload.kind,
            payload.optionsJson,
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
        try {
          const status = module._SherpaOnnxBridgeCall(
            BigInt(payload.handle),
            payload.operation,
            payload.argumentsJson,
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
        try {
          module.HEAPF32.set(samples, samplesPtr >> 2);
          const status = module._SherpaOnnxBridgePushAudio(
            BigInt(payload.handle),
            payload.operation,
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
          module._free(samplesPtr);
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
