#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// Asserts that the Emscripten bridge advertises the same resource surface as
// the native transports.
//
// The JS glue is built with `-sENVIRONMENT=worker`, so it cannot simply be
// `require`d from Node: it expects `self` to exist and resolves its `.wasm`
// relative to the script URL. Running it in a `vm` context with a minimal
// worker-shaped global loads the exact artifact the worker loads, without
// depending on a browser.
//
// Usage:
//   node scripts/check-web-capabilities.cjs [directory containing the module]

"use strict";

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const directory = path.resolve(process.argv[2] || "build/web");
const gluePath = path.join(directory, "sherpa-onnx-bridge.js");
const wasmPath = path.join(directory, "sherpa-onnx-bridge.wasm");

for (const file of [gluePath, wasmPath]) {
  if (!fs.existsSync(file)) {
    console.error(`missing Emscripten artifact: ${file}`);
    process.exit(1);
  }
}

// `-sENVIRONMENT=worker` makes the glue read `self.location.href` and skip its
// Node and browser branches. Supplying only the fields it reads is enough.
const baseUrl = "file://" + directory + "/";
const sandbox = {
  console,
  process,
  Buffer,
  WebAssembly,
  TextDecoder,
  TextEncoder,
  performance,
  setTimeout,
  clearTimeout,
  setInterval,
  clearInterval,
  URL,
  location: { href: baseUrl },
  self: { location: { href: baseUrl } },
};
sandbox.globalThis = sandbox;

const glue = fs.readFileSync(gluePath, "utf8");
const factory = vm.runInNewContext(
  glue + "\n;createSherpaOnnxBridgeModule",
  sandbox,
  { filename: gluePath },
);

// Every kind the native builds report. A web build that omits one is a support
// gap: capability discovery would then differ per platform for the same release.
const expectedResources = [
  "kws",
  "kws-stream",
  "offline-asr",
  "online-asr",
  "online-session",
  "punctuation",
  "speaker-diarization",
  "speech-denoiser",
  "tts",
  "vad",
];

const expectedOperations = [
  "health",
  "offline-asr.transcribe",
  "online-asr.create-stream",
  "online-session.push-audio",
  "online-session.finish",
  "vad.accept-waveform",
  "tts.synthesize",
  "tts.take-chunk",
  "kws.create-stream",
  "kws-stream.push-audio",
  "punctuation.add-punctuation",
  "speaker-diarization.process",
  "speech-denoiser.denoise",
];

(async () => {
  const module = await factory({
    wasmBinary: fs.readFileSync(wasmPath),
    locateFile: (name) => path.join(directory, name),
  });

  const infoPointer = module._SherpaOnnxBridgeInfoJson();
  if (!infoPointer) {
    console.error("SherpaOnnxBridgeInfoJson returned null");
    process.exit(1);
  }
  const info = JSON.parse(module.UTF8ToString(infoPointer));
  module._SherpaOnnxBridgeFree(infoPointer);

  const problems = [];
  if (info.abiVersion !== 2) {
    problems.push(`ABI version is ${info.abiVersion}, expected 2`);
  }
  if (!info.coreVersion) {
    problems.push("capability report has no coreVersion");
  }
  for (const resource of expectedResources) {
    if (!info.resources.includes(resource)) {
      problems.push(`resource is missing: ${resource}`);
    }
  }
  for (const operation of expectedOperations) {
    if (!info.operations.includes(operation)) {
      problems.push(`operation is missing: ${operation}`);
    }
  }

  if (problems.length !== 0) {
    for (const problem of problems) {
      console.error(problem);
    }
    console.error("advertised resources: " + info.resources.join(", "));
    process.exit(1);
  }

  console.log(
    `web capability surface: ${info.resources.length} resources, ` +
      `${info.operations.length} operations, core ${info.coreVersion}`,
  );
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
