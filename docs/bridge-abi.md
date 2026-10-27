# Portable Bridge ABI

Status: v0.2.0

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
void SherpaOnnxBridgeDestroy(uint64_t handle);
void SherpaOnnxBridgeFree(char *ptr);
```

All returned strings and buffers belong to the caller. Returned strings are
released with `SherpaOnnxBridgeFree`.

## Resources and handles

The first resource is `offline-asr`.

Handles are opaque, monotonically allocated `uint64_t` values. Handles never
expose native pointers. Destroying an unknown or already destroyed handle is a
no-op.

Calls are serialized by the bridge registry and per-handle state. The KMP layer
may impose an additional serialized coroutine context.

## Flat options protocol

`options_json` is a flat JSON object. Values may be strings, booleans, or
numbers; the bridge converts all values to strings before applying them.

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

## Operations

For `offline-asr`:

### `offline-asr.set-audio`

Audio is sent through `SherpaOnnxBridgePushAudio`, not through JSON. Multiple
push calls may append samples. All chunks for one transcription must use the same
sample rate.

### `offline-asr.transcribe`

Creates a native offline stream, accepts the accumulated audio, decodes it, and
returns a result snapshot. The pending audio is cleared only after a successful
transcription.

The result uses the stable schema:

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
result for forward-compatible access.

## Error handling

The status codes are:

- `INVALID_ARGUMENT`
- `INVALID_HANDLE`
- `UNSUPPORTED`
- `INVALID_STATE`
- `RUNTIME_ERROR`

An error string is allocated only when `error` is non-null. It must be freed by
the caller.

## Upstream prerequisite

The bridge uses a non-fatal `ParseOptions::TrySetOption` addition. The patch is:

`patches/0001-parse-options-try-set-option.patch`

The upstream source is pinned to sherpa-onnx `v2.0.1` during the v0.2 cycle.
