# Release 1.0.0: Stable

Status: released

## Scope

1.0.0 freezes the public Kotlin API, the Portable Bridge C ABI, and the result
schema under semantic versioning. Every feature sherpa-onnx exposes through the
bridge is available on every supported target family, and the platform
differences are recorded rather than implied.

The compatibility contract lives in [`docs/versioning.md`](versioning.md). The
short version:

```text
sherpa-onnx-kmp  1.0.0   Kotlin semver
bridge ABI       2       runtime-checked integer
sherpa-onnx      2.0.1   pinned upstream core
```

## Artifacts

- `sherpa-onnx-kmp-android-1.0.0.aar` — arm64-v8a, armeabi-v7a, x86, x86_64.
- `sherpa-onnx-kmp-jvm-1.0.0.jar` — JNI transport.
- `sherpa-onnx-kmp-jvm-native-<platform>-1.0.0.jar` — JNI plus bridge for the
  build host.
- `SherpaOnnxKmp.xcframework.zip` — iOS device, iOS Simulator, and macOS arm64,
  with the bridge, sherpa-onnx, and ONNX Runtime embedded.
- `sherpa-onnx-kmp-linux-x64-1.0.0.zip` and
  `sherpa-onnx-kmp-linux-arm64-1.0.0.zip` — `libsherpa-onnx-portable-bridge.so`
  with its SONAME, the `libsherpa-onnx-kmp-native-desktop.so` alias,
  `libonnxruntime.so`, and the public C header.
- `sherpa-onnx-kmp-windows-x64-1.0.0.zip` — `sherpa-onnx-portable-bridge.dll`
  with static ONNX Runtime, its import library, and the header.
- `sherpa-onnx-kmp-web-1.0.0.zip` — `sherpa-onnx-bridge.js`,
  `sherpa-onnx-bridge.wasm`, `sherpa-onnx-worker.js`, and the Kotlin/JS bundle.

Each archive carries a `BUILD-INFO.txt` recording the platform, build host,
commit, and bridge ABI version, and every artifact is covered by one
`SHA256SUMS`. The published v1.0.0 release carries eleven artifacts plus the
manifest:

| Artifact | Size |
| --- | --- |
| `sherpa-onnx-kmp-android-1.0.0.aar` | 115 MiB |
| `sherpa-onnx-kmp-jvm-1.0.0.jar` | 309 KiB |
| `sherpa-onnx-kmp-jvm-native-linux-x64-1.0.0.jar` | 9.9 MiB |
| `sherpa-onnx-kmp-jvm-native-linux-aarch64-1.0.0.jar` | 12 MiB |
| `sherpa-onnx-kmp-jvm-native-osx-aarch64-1.0.0.jar` | 9.2 MiB |
| `sherpa-onnx-kmp-jvm-native-windows-x64-1.0.0.jar` | 7.5 MiB |
| `SherpaOnnxKmp.xcframework.zip` | 33 MiB |
| `sherpa-onnx-kmp-linux-x64-1.0.0.zip` | 12 MiB |
| `sherpa-onnx-kmp-linux-arm64-1.0.0.zip` | 14 MiB |
| `sherpa-onnx-kmp-windows-x64-1.0.0.zip` | 7.4 MiB |
| `sherpa-onnx-kmp-web-1.0.0.zip` | 4.3 MiB |

The Linux libraries resolve ONNX Runtime through a `$ORIGIN` runpath, so the
pair in the archive works wherever it is unpacked. A library that carried a
build-host path would have been a broken artifact, and
`package-release-desktop.sh` rejects one.

[`release.yml`](../.github/workflows/release.yml) builds each slice on its own
runner — the AAR needs the NDK and all four ABIs, the XCFramework needs Xcode,
the Linux libraries need the glibc they were built against, and the Windows DLL
needs MSVC — then a `publish` job merges them, refuses to write a manifest when a
platform is missing, and creates the release. A single host can still package its
own slice with `scripts/package-release.sh`, which passes `--allow-partial`
because the other platforms cannot exist there.

## Capability surface

Every target family advertises the same resource set. This was verified by
loading the actual libraries and reading `SherpaOnnxBridgeInfoJson()`:

```text
resources  (10): kws, kws-stream, offline-asr, online-asr, online-session,
                 punctuation, speaker-diarization, speech-denoiser, tts, vad
operations (35): health, kws.create-stream, kws-stream.push-audio, ...,
                 offline-asr.transcribe, online-asr.create-stream,
                 online-session.push-audio, online-session.finish,
                 punctuation.add-punctuation, speaker-diarization.process,
                 speech-denoiser.denoise, tts.synthesize, tts.take-chunk,
                 vad.accept-waveform, vad.flush, ...
```

The web build previously linked with `SHERPA_ONNX_ENABLE_TTS=OFF` and
`SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF`, which made the browser report a
narrower capability set than the native builds for the same release. Both are
enabled now, and `scripts/check-web-capabilities.cjs` loads the built module and
fails the build if any of the ten resources is missing.

## Verification

### Unit and contract suites

| Target | Tests |
| --- | --- |
| `jvmTest` | 46 |
| `jsNodeTest` | 37 |
| `jsBrowserTest` | 37 |
| `macosArm64Test` | 45 |
| `iosSimulatorArm64Test` | 45 |
| Android device (API 36 x86_64 emulator) | 2 |

### Real-model feature suites

Both real-model suites assert all eight features against pinned models on the
hosts where they execute. The JVM suite runs over the JNI transport; the shared
`nativeFeatureTest` set runs over the native cinterop transport. They stay
separate because the transports differ: a feature proven by one is not proven
for the other.

Native execution is not uniform across platforms. macOS arm64, iOS Simulator,
Linux x64, and Windows x64 run the suite natively. `linuxArm64` has no native
route — Kotlin/Native does not support linux-aarch64 as a build host, and an
Android arm64 emulator cannot boot on a hosted runner — so it runs under qemu,
where streaming ASR, keyword spotting, and speaker diarization currently fail.
The checks below are evidence for the native hosts; for `linuxArm64` they
describe intent.

| Feature | Model | JVM | Native |
| --- | --- | --- | --- |
| VAD | `silero_vad.onnx` | ✓ | ✓ |
| Keyword spotting | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` | ✓ | ✓ (not linuxArm64) |
| Punctuation | `sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8` | ✓ | ✓ |
| Speech enhancement | `gtcrn_simple.onnx` | ✓ | ✓ |
| Streaming ASR | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` | ✓ | ✓ (not linuxArm64) |
| Speaker diarization | `sherpa-onnx-pyannote-segmentation-3-0` + `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` | ✓ | ✓ (not linuxArm64) |
| TTS, lexicon path | `vits-icefall-zh-aishell3` | ✓ | ✓ |
| TTS, phoneme path | `vits-piper-en_US-amy-low` | ✓ | ✓ |

### Bridge C ABI

`scripts/test-bridge-abi.sh` compiles a C probe (not C++ — the header must be a
valid C interface), checks the ABI integer, the advertised resources and
operations, typed failures for an unknown kind and an unknown option, a no-op
destroy of an unknown handle, and then synthesizes and pulls real audio through
the CLI.

### Release hardening

`scripts/check-release-hardening.sh` verifies three things:

1. The header's `SHERPA_ONNX_BRIDGE_ABI_VERSION` matches what the library
   reports at runtime.
2. No release library carries a dependency path from the build machine.
3. Repeated create/synthesize/destroy does not grow native memory without bound.
   The measurement compares two warm peaks rather than 1-vs-N, because the first
   syntheses raise ONNX Runtime's own arena and that rise would otherwise be
   reported as per-iteration growth. On the development host the peaks were
   288576 KiB at 384 iterations and 301344 KiB at 768, and 336528 KiB at 1536
   versus 350208 KiB at 3072. The total delta stays near 13 MB while the
   iteration count grows eightfold, so the curve is an arena saturating, not a
   leak. The budgets in the script are 512 KiB per iteration and 98304 KiB
   total.

### Browser

The pinned Persian NeMo CTC int8 model transcribes through the real worker path
in Chrome, recorded in [`docs/release-0.6.0.md`](release-0.6.0.md). The
transcript is asserted to be non-empty, not verbatim, matching the bar the
native and Android suites use.

### Performance snapshot

`scripts/benchmark-release.sh` records a cold start on the development host:

```json
{
  "coreVersion": "2.0.1",
  "onnxRuntimeVersion": "1.28.2",
  "host": "macOS-27.2-arm64-arm-64bit-Mach-O",
  "measurements": { "offlineAsrColdStartMs": 532 },
  "audio": { "md5": "73786dda5a1bdc963eb277bbc5f13ad3" }
}
```

532 ms covers process start, model load, and one transcription, because the
bridge CLI has no decode-only timing point. It is a regression tripwire on one
machine, not a hardware-independent guarantee.

## Support policy

- The newest minor receives fixes.
- The previous minor receives critical fixes for 90 days.
- Older minors receive security fixes only.
- A deprecated public declaration works for the whole 1.x line and is removed no
  earlier than 2.0.0.
- A feature absent on a platform is reported through `capabilities` and fails
  creation with a typed exception; it is never silent.

## Known limitations

- Physical iOS device execution is a documented gate, not an automated one. The
  device slice builds, links, and signs, but the recorded runs are Simulator and
  macOS.
- `linuxArm64` requires an aarch64 host or a cross toolchain; real-model
  inference runs in CI on native runners.
- The web module is capped at 2 GiB of linear memory. Larger models need
  `MAXIMUM_MEMORY` raised at build time.
- WebAssembly SIMD is required. Browsers without SIMD are not supported.
- `wasmWasi`, Mac Catalyst, watchOS, tvOS, and visionOS are out of scope.
- Piper TTS requires caller-supplied phoneme codepoints; the library does not
  bundle a phonemizer.

## Reproducting the verification

```bash
# Unit and contract suites
./gradlew :sherpa-onnx-kmp:jvmTest :sherpa-onnx-kmp:jsNodeTest

# Apple native
./scripts/build-apple-native.sh
SHERPA_ONNX_KMP_TEST_MODEL_DIR=... \
SHERPA_ONNX_KMP_FEATURE_MODEL_DIR=... \
  ./gradlew :sherpa-onnx-kmp:macosArm64Test

# Native bridge, ABI, hardening, benchmark
./scripts/prepare-feature-models.sh
./scripts/test-bridge-abi.sh build/native-test
./scripts/check-release-hardening.sh build/native-test
./scripts/benchmark-release.sh build/release/benchmarks.json

# Web
./scripts/build-web-emscripten.sh
./scripts/test-web-browser.sh samples/web/dist
```
