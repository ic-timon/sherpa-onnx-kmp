# Release 0.7.0: Streaming ASR and VAD

Status: complete. Shipped in v1.0.0. No 0.7.0 tag or release was cut; this
document records the scope as it was designed and delivered on `main`.

## Scope

Sessions became the unit of work. Offline transcription is a one-shot call, but
streaming ASR, VAD, and generated audio all produce results over time, and a
per-frame round trip across the transport would make each of them unusably slow
on the web.

The answer is bridge ABI v2: two new entry points, `SherpaOnnxBridgePushAudio`
for input that arrives in chunks and `SherpaOnnxBridgePullAudio` for output that
is generated in chunks. Everything above them stays the same call/option/handle
model, so a transport that already spoke ABI 1 needs only those two functions.

## What shipped

- `OnlineRecognizer`, `OnlineSession` with `push`, `finish`, `reset`, and
  `result`, plus endpoint detection and per-stream hotwords.
- VAD: `acceptWaveform` returns every completed segment, `flush` drains the
  tail, and `isSpeechDetected` exposes detector state.
- Flow-based event delivery for chunked output.
- Bridge ABI 2, runtime-checked against the header's
  `SHERPA_ONNX_BRIDGE_ABI_VERSION`.
- The web worker batches streaming operations instead of issuing one round trip
  per audio frame.

## Verification

- Chunked and non-chunked input produce equivalent transcripts.
- Every transport runs the streaming contract: Android, iOS Simulator, macOS
  arm64, JVM, and web.
- VAD boundaries match the golden sample within tolerance.
- Closing a session while work is in flight neither leaks nor crashes.

## Compatibility

Bridge ABI 2 is not backward compatible with ABI 1: a library that predates the
push/pull entry points fails the version check rather than misreading a struct.
The Kotlin API is additive; nothing from 0.6.0 was removed.
