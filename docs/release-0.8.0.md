# Release 0.8.0: TTS, KWS, and the Full Feature Surface

Status: complete. Shipped in v1.0.0. No 0.8.0 tag or release was cut; this
document records the scope as it was designed and delivered on `main`.

## Scope

The feature surface that sherpa-onnx actually exposes through the bridge is
eight features, not two. 0.7.0 covered offline and streaming ASR plus VAD; this
milestone added the remaining five and made an unsupported feature a typed
failure instead of a silent no-op.

## What shipped

- **Offline TTS** with `Flow<AudioChunk>` delivery, `sampleRate` and
  `numSpeakers` discovery, and per-chunk progress metadata.
- **`TtsRequest.phonemeCodepoints`** for Piper VITS models. sherpa-onnx v2.0
  dropped the bundled espeak-ng, so a Piper model without a lexicon needs
  Unicode codepoints from the caller. Lexicon models such as
  `vits-icefall-zh-aishell3` still run from plain text.
- **Keyword spotting** with `KeywordSpotter` and `KeywordSpotterStream`,
  including per-stream keyword overrides.
- **Punctuation** restoration through CT-Transformer.
- **Speaker diarization** over pyannote segmentation plus a speaker-embedding
  model.
- **Speech enhancement** through GTCRN.
- Typed configs plus shared option and result mappers for all five features in
  `commonMain`, so no platform repeats the mapping.

## Verification

Eight real-model suites run on the JVM, macOS arm64, and the iOS Simulator from
the shared `nativeFeatureTest` source set:

| Feature | Model |
| --- | --- |
| VAD | `silero_vad.onnx` |
| Keyword spotting | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` |
| Punctuation | `sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8` |
| Speech enhancement | `gtcrn_simple.onnx` |
| Streaming ASR | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` |
| Speaker diarization | pyannote segmentation 3.0 + `3dspeaker` embedding |
| TTS, lexicon path | `vits-icefall-zh-aishell3` |
| TTS, phoneme path | `vits-piper-en_US-amy-low` |

The bridge advertises 10 resources and 35 operations, which
`scripts/test-bridge-abi.sh` asserts from C.

## Fixed after the milestone landed

- Piper TTS produced silence from raw text. The bridge now forwards
  `phoneme_codepoints` and reports a clear error when a Piper model requires
  them.
- Enabling TTS pulled `openfst`'s `randgen.h` into the Windows bridge, where its
  `using namespace std;` collided with the `<windows.h>` `min`/`max` macros. The
  native build defines `NOMINMAX` on `WIN32`.
- Enabling TTS also introduced the upstream `thread_local` in
  `normal-data-generator.cc`, which broke 32-bit Android linking. See
  [release 1.0.0](release-1.0.0.md) and ADR 0008.
