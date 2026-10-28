#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end browser check for the web target.
#
# The unit suite proves the transport is wired, but it cannot prove that a real
# browser can start the worker, instantiate the Emscripten module, load a model
# through `HttpModelStore`, and produce a transcript. This script serves the
# built sample, drives a headless Chromium with Playwright, and fails unless the
# expected text comes back.
set -euo pipefail

source "$(dirname "$0")/lib.sh"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

dist="${1:-samples/web/dist}"
port="${SHERPA_ONNX_KMP_WEB_PORT:-8123}"
# Empty by default: the pinned model's transcript is not asserted verbatim
# anywhere else in this repository (the native and Android integration tests
# require only non-blank text and tokens), so this check keeps the same bar.
# Set it when you have a recorded transcript and want a strict comparison.
expected_text="${SHERPA_ONNX_KMP_WEB_EXPECTED_TEXT:-}"

if [[ ! -d "$dist" ]]; then
  echo "Sample distribution is missing: $dist" >&2
  echo "Run scripts/build-web-emscripten.sh and assemble the sample first." >&2
  exit 1
fi

# The model is pinned and shared with the Android device suite, so the same
# checksum gates every platform's real-inference evidence.
model_cache="$(cache_root)/models"
archive_name=sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26.tar.bz2
archive="$model_cache/$archive_name"
model_root="$model_cache/sherpa-onnx-nemo-ctc-fa-shenava-rizeh-pizeh-v1.0-non-streaming-int8-2026-06-26"

if [[ ! -f "$archive" ]]; then
  download \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive_name" \
    "$archive" \
    e5369d24aeee14afac8277937e43ebaed20444a09e34de452387916293b07b9d
fi
if [[ "$(sha256 "$archive")" != "e5369d24aeee14afac8277937e43ebaed20444a09e34de452387916293b07b9d" ]]; then
  echo "Unexpected test model checksum" >&2
  exit 1
fi
if [[ ! -d "$model_root" ]]; then
  tar -xjf "$archive" -C "$model_cache"
fi

model_dest="$dist/models/nemo-ctc"
mkdir -p "$model_dest/test_wavs"
cp -f "$model_root/model.int8.onnx" "$model_dest/model.int8.onnx"
cp -f "$model_root/tokens.txt" "$model_dest/tokens.txt"
cp -f "$model_root/test_wavs/0.wav" "$model_dest/test_wavs/0.wav"

if ! command -v node >/dev/null 2>&1; then
  echo "node is required to drive Playwright" >&2
  exit 1
fi

work="$(mktemp -d)"
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT

# A plain static server. COOP/COEP are not required: the worker is a classic
# worker and the Wasm module uses no shared memory.
python3 -m http.server "$port" --directory "$dist" >"$work/server.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:$port/" >/dev/null 2>&1; then break; fi
  sleep 0.25
done
curl -fsS "http://127.0.0.1:$port/" >/dev/null

cd "$work"
if [[ ! -d node_modules/playwright ]]; then
  npm init -y >/dev/null 2>&1
  npm install --no-audit --no-fund playwright@1.56.1 >/dev/null
  npx playwright install --with-deps chromium
fi

cat > drive.mjs <<'SCRIPT'
import { chromium } from "playwright";

const url = process.argv[2];
const expected = process.argv[3] ?? "";

const browser = await chromium.launch();
const page = await browser.newPage();
const consoleLines = [];
page.on("console", (message) => consoleLines.push(`${message.type()}: ${message.text()}`));
page.on("pageerror", (error) => consoleLines.push(`pageerror: ${error.message}`));

try {
  await page.goto(url, { waitUntil: "load" });

  // The page disables the button until the module is ready, then enables it.
  await page.waitForFunction(
    () => {
      const button = document.getElementById("run");
      return button !== null && !button.disabled;
    },
    { timeout: 120_000 },
  );
  // The page enables the button only after its module graph has loaded, so a
  // click here cannot race the bundle import.
  await page.waitForFunction(
    () => (document.getElementById("status")?.textContent ?? "").includes("Ready"),
    { timeout: 120_000 },
  );

  await page.click("#run");
  await page.waitForFunction(
    () => {
      const status = document.getElementById("status");
      return status !== null && /Done|Failed/.test(status.textContent ?? "");
    },
    { timeout: 600_000 },
  );

  const status = (await page.textContent("#status"))?.trim() ?? "";
  const output = (await page.textContent("#output"))?.trim() ?? "";
  if (!status.startsWith("Done")) {
    throw new Error(`Sample reported failure: ${status}\n${output}\n${consoleLines.join("\n")}`);
  }
  // Same bar as the native and Android integration tests: real text, not a
  // fixed string. A blank transcript means the model produced nothing.
  if (output.length === 0) {
    throw new Error(`Sample produced an empty transcript\n${consoleLines.join("\n")}`);
  }
  if (expected && !output.includes(expected)) {
    throw new Error(
      `Transcript did not contain the expected text.\n` +
        `expected substring: ${expected}\n` +
        `actual: ${output}\n` +
        consoleLines.join("\n"),
    );
  }
  console.log(`transcript: ${output}`);
  console.log("browser end-to-end: OK");
} finally {
  await browser.close();
}
SCRIPT

node drive.mjs "http://127.0.0.1:$port/index.html" "$expected_text"
