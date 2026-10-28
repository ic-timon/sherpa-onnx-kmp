# Portable Bridge ABI

Status: v1.0.0 (ABI version 2, schema version 1)

## Purpose

The Portable Bridge is the single native boundary used by KMP transports. It is
additive: existing sherpa-onnx C, Java, Kotlin, Swift, and other APIs remain
unchanged.

The bridge owns the native handle registry and converts a stable, typed Kotlin
configuration into sherpa-onnx configs.

## ABI header

The public header is:

```text
native/portable-bridge/include/sherpa-onnx-portable-bridge.h
```

It defines:

```c
int32_t SherpaOnnxBridgeAbiVersion(void);
char *SherpaOnnxBridgeInfoJson(void);
int32_t SherpaOnnxBridgeCreate(
    const char *kind, const char *options_json, uint64_t *handle,
    char **error);
int32_t SherpaOnnxBridgeCall(
    uint64_t handle, const char *operation, const char *arguments_json,
    char **result_json, char **error);
int32_t SherpaOnnxBridgePushAudio(
    uint64_t handle, const char *operation, int32_t sample_rate,
    const float *samples, int32_t num_samples, char **error);
int32_t SherpaOnnxBridgePullAudio(
    uint64_t handle, const char *operation, const char *arguments_json,
    int32_t *sample_rate, float **samples, int32_t *num_samples,
    char **result_json, char **error);
void SherpaOnnxBridgeDestroy(uint64_t handle);
void SherpaOnnxBridgeFree(char *ptr);
void SherpaOnnxBridgeFreeAudio(float *ptr);
```

All returned strings and buffers belong to the caller. Returned strings are
released with `SherpaOnnxBridgeFree`, returned audio buffers with
`SherpaOnnxBridgeFreeAudio`.

### Version constants

```text
SHERPA_ONNX_BRIDGE_ABI_VERSION   2
result schemaVersion             1
```

The ABI version is an integer a transport must compare at startup.
`SherpaOnnxBridgeAbiVersion()` returns it, and `SherpaOnnxBridgeInfoJson()`
reports it as `abiVersion`, so a mismatch is detected before any other call.

Version 2 added `SherpaOnnxBridgePushAudio` and `SherpaOnnxBridgePullAudio`,
streaming ASR, and VAD. Version 1 had no audio pull path: audio in was per-call,
and no operation produced samples.

## Resources and handles

| Kind | Created by | Purpose |
| --- | --- | --- |
| `offline-asr` | `SherpaOnnxBridgeCreate` | one-shot transcription |
| `online-asr` | `SherpaOnnxBridgeCreate` | streaming recognizer; owns sessions |
| `online-session` | `online-asr.create-stream` | one decoding stream |
| `vad` | `SherpaOnnxBridgeCreate` | voice activity detection |
| `tts` | `SherpaOnnxBridgeCreate` | text to speech |
| `kws` | `SherpaOnnxBridgeCreate` | keyword spotter; owns streams |
| `kws-stream` | `kws.create-stream` | one spotting stream |
| `punctuation` | `SherpaOnnxBridgeCreate` | punctuation restoration |
| `speaker-diarization` | `SherpaOnnxBridgeCreate` | speaker turns |
| `speech-denoiser` | `SherpaOnnxBridgeCreate` | speech enhancement |

Handles are opaque, monotonically allocated `uint64_t` values. Handles never
expose native pointers. Destroying an unknown or already destroyed handle is a
no-op.

Destroying a parent destroys its children: closing an `online-asr` closes its
`online-session` handles, and closing a `kws` closes its `kws-stream` handles.

Calls are serialized by the bridge registry and per-handle state. The KMP layer
may impose an additional serialized coroutine context.

## Capability discovery

`SherpaOnnxBridgeInfoJson()` returns:

```json
{
  "abiVersion": 2,
  "schemaVersion": 1,
  "resources": ["kws", "kws-stream", "offline-asr", "..."],
  "operations": ["health", "offline-asr.transcribe", "..."],
  "coreVersion": "2.0.1",
  "onnxruntimeVersion": "1.28.2"
}
```

`resources` and `operations` are sorted, so two builds can be compared
structurally. A factory whose feature was compiled out is not registered, which
makes the report truthful for a reduced build rather than aspirational. The
1.0.0 native and web builds all report 10 resources and 35 operations.

`coreVersion` and `onnxruntimeVersion` are omitted when the bridge is built
without sherpa-onnx, as in the registry-only unit-test target.

## Flat options protocol

`options_json` is a flat JSON object. Values may be strings, booleans, numbers,
or arrays of numbers; the bridge converts scalars to strings before applying
them.

Keys are the option names registered by the corresponding sherpa-onnx config:

```json
{
  "nemo-ctc-model": "/models/model.int8.onnx",
  "tokens": "/models/tokens.txt",
  "num-threads": "2",
  "provider": "cpu",
  "debug": "false"
}
```

The bridge:

1. Constructs the config.
2. Calls `config.Register(&ParseOptions)`.
3. Applies each key through `TrySetOption`.
4. Calls `config.Validate()`.
5. Creates the native resource.

`extraOptions` from the KMP layer uses the same key space. This allows new
upstream options to be used without a new KMP wrapper release.

An unknown key is rejected with `INVALID_ARGUMENT`, not ignored: a typo in a
model path should not silently load a default.

## Operations

### `offline-asr`

#### `offline-asr.set-audio`

Audio is sent through `SherpaOnnxBridgePushAudio`, not through JSON. Multiple
push calls may append samples. All chunks for one transcription must use the same
sample rate.

#### `offline-asr.transcribe`

Creates a native offline stream, accepts the accumulated audio, decodes it, and
returns a result snapshot. The pending audio is cleared only after a successful
transcription.

### `online-asr` and `online-session`

`online-asr.create-stream` takes an optional `{"hotwords": "..."}` argument in
the on-the-fly format (tokens separated by spaces, phrases by `/`) and returns a
child handle. `online-session.push-audio` feeds samples, `decode` advances the
decoder, `result` returns the current text, `is-endpoint` reports endpoint
detection, `reset` clears the stream, and `finish` marks end of input and decodes
the tail.

`push-audio` decodes every ready frame before returning, so `result` is current
rather than one chunk behind.

### `vad`

`vad.accept-waveform` feeds audio; `vad.flush` ends the utterance; `vad.pop`
removes and returns the front segment; `vad.front` peeks; `vad.front-audio`,
`vad.current-segment`, and `vad.current-segment-audio` expose segment data;
`vad.take-audio` pulls the latest segment's samples; `vad.is-speech-detected`,
`vad.empty`, `vad.clear`, and `vad.reset` are state operations.

### `tts`

- `tts.describe` reports `sampleRate` and `numSpeakers`.
- `tts.synthesize` accepts `{"text": "...", "speakerId": 0, "speed": 1.0}` and,
  for Piper models without a lexicon, `{"phonemeCodepoints": [[104, 101], ...]}`.
  Each inner array is one phoneme in IPA as Unicode codepoints.
- `tts.take-chunk` pulls generated audio through `SherpaOnnxBridgePullAudio` and
  returns `{"isLast": true, "progress": 1.0, "numSamples": 12480}` as metadata.

sherpa-onnx v2.0 removed the built-in espeak-ng. A Piper VITS model without
`lexicon.txt` therefore cannot convert text on its own; the bridge reports a
runtime error naming the missing phoneme path rather than returning silence.
Models that ship a lexicon, such as `vits-icefall-zh-aishell3`, need no
codepoints.

### `kws` and `kws-stream`

`kws.create-stream` takes an optional `{"keywords": "..."}` override.
`kws-stream.push-audio`, `kws-stream.decode`, `kws-stream.result`, and
`kws-stream.reset` mirror the online session shape.

### `punctuation`

`punctuation.add-punctuation` takes `{"text": "..."}` and returns
`{"text": "with, punctuation."}`.

### `speaker-diarization`

`speaker-diarization.set-audio` accepts audio at the model's own sample rate;
a mismatched rate is `INVALID_ARGUMENT`, not a resample.
`speaker-diarization.process` returns
`{"numSpeakers": 2, "segments": [{"startTime": 2.03, "endTime": 7.81, "speaker": 0}]}`.

Segments are ordered by start time but **may overlap** across a speaker change by
up to one segmentation window. They are not strictly sequential.

### `speech-denoiser`

`speech-denoiser.set-audio` accepts samples, `denoise` processes them, and
`take-audio` pulls the enhanced waveform.

## Audio plane

- `SherpaOnnxBridgePushAudio` copies mono Float32 PCM into a resource. The
  operation names what the samples mean, for example `offline-asr.set-audio` or
  `online-session.push-audio`.
- `SherpaOnnxBridgePullAudio` writes a newly allocated buffer and its sample
  rate. When an operation produces no audio, `*samples` is null and
  `*num_samples` is 0.
- Samples never travel through JSON. Base64 or number arrays would inflate the
  payload and force a copy at every boundary.

## Result schema

The offline and online result uses:

```json
{
  "schemaVersion": 1,
  "text": "recognized text",
  "tokens": [
    {
      "text": "token",
      "startTime": 0.0,
      "duration": 0.0,
      "logProbability": 0.0
    }
  ],
  "segments": [
    {
      "text": "segment",
      "startTime": 0.0,
      "duration": 0.0
    }
  ],
  "language": "en",
  "emotion": "neutral",
  "event": "speech",
  "rawJson": "{...original result...}"
}
```

Optional fields are omitted when unavailable. `rawJson` preserves the native
result for forward-compatible access. Consumers must ignore unknown fields;
`schemaVersion` increments only when a field changes meaning, not when one is
added.

## Error handling

The status codes are:

- `INVALID_ARGUMENT` (1)
- `INVALID_HANDLE` (2)
- `UNSUPPORTED` (3)
- `INVALID_STATE` (4)
- `RUNTIME_ERROR` (5)

An error string is allocated only when `error` is non-null. It must be freed by
the caller.

`UNSUPPORTED` means the kind or operation is not compiled into this build, which
is why capability discovery is the authority rather than a hard-coded list.
`INVALID_STATE` means the call does not fit the resource's current state, such as
pulling a TTS chunk before synthesizing.

## Threading

- A handle is safe to call from multiple threads; calls are serialized per
  handle.
- `SherpaOnnxBridgeInfoJson` and `SherpaOnnxBridgeCreate` install the factory
  registry through a function-local static, which C++11 guarantees is a single
  thread-safe initialization.
- `SherpaOnnxBridgeDestroy` is a no-op for an unknown handle, so a double destroy
  is safe. It still should not race a call on the same handle.

## Upstream prerequisites

Two patches are applied by `scripts/bootstrap-sherpa-source.sh`:

- `patches/0001-parse-options-try-set-option.patch` — a non-fatal
  `ParseOptions::TrySetOption` addition. The bridge needs it to reject an
  unknown option with a typed error instead of aborting.
- `patches/0002-normal-data-generator-avoid-thread-local.patch` — moves the
  RNG holder in `normal-data-generator.cc` from a function-local `thread_local`
  to an instance member. Android below API 29 exports no TLS helper from
  `libc`, so the original code cannot link once TTS references it. The change is
  also a portability fix in its own right and is intended for upstream.

The upstream source is pinned to sherpa-onnx `v2.0.1`. The supported range and the
upgrade rules are in [`versioning.md`](versioning.md).
