# Migration Guide: 0.4.0 → 1.0.0

Status: 1.0.0

This guide covers every change a consumer of 0.3.0 or 0.4.0 has to make. It is
organized by the shape of the problem, not by version, because most of the 0.5
through 0.8 line was additive.

## Summary

| Change | Action |
| --- | --- |
| New feature interfaces on `SherpaRuntime` | None. They are additive. |
| `SherpaFeature` has new entries | Add branches to exhaustive `when` blocks. |
| Shared native transport replaced the Apple-only one | None, unless you imported an internal type. |
| Bridge ABI moved from 1 to 2 | Upgrade the native library with the Kotlin library. |
| Web target added | Opt in; see the web sample. |
| `TtsRequest.phonemeCodepoints` added | Only needed for Piper models. |

## 1. Exhaustive `when` over `SherpaFeature`

`SherpaFeature` gained five entries in 0.7.0 and 0.8.0:

```kotlin
public enum class SherpaFeature {
    OFFLINE_ASR,
    ONLINE_ASR,
    TTS,
    VAD,
    KEYWORD_SPOTTING,
    PUNCTUATION,
    SPEAKER_DIARIZATION,
    SPEECH_ENHANCEMENT,
}
```

A `when` over it without an `else` will not compile. This is deliberate: a
consumer that maps features to UI should decide what the new ones look like. Add
an `else` branch to opt out:

```kotlin
fun label(feature: SherpaFeature): String = when (feature) {
    SherpaFeature.OFFLINE_ASR -> "Transcribe file"
    SherpaFeature.ONLINE_ASR -> "Live transcription"
    SherpaFeature.VAD -> "Voice activity"
    else -> "Unsupported"
}
```

## 2. Runtime creation

The runtime interface grew. Old calls still compile:

```kotlin
val recognizer = runtime.createOfflineRecognizer(config)
```

New features use the same pattern:

```kotlin
val vad = runtime.createVoiceActivityDetector(vadConfig)
val tts = runtime.createOfflineTts(ttsConfig)
val kws = runtime.createKeywordSpotter(kwsConfig)
val punctuation = runtime.createPunctuation(punctuationConfig)
val diarization = runtime.createSpeakerDiarization(diarizationConfig)
val enhancer = runtime.createSpeechEnhancer(enhancementConfig)
```

`createVad` was never a public name; the method has always been
`createVoiceActivityDetector`. If you have local extension code using `createVad`,
rename it.

### Check capabilities before creating

Platforms ship different feature sets. A feature that is compiled out is absent
from `capabilities.features`, and creation fails with a typed exception rather
than a nil object:

```kotlin
if (SherpaFeature.TTS in runtime.capabilities.features) {
    val tts = runtime.createOfflineTts(config)
}
```

The web build historically omitted TTS and diarization. As of 1.0.0 it enables
both, so all ten bridge resources are reported on every target family.

## 3. Streaming

Offline-only code is unaffected. Streaming code uses the new session API:

```kotlin
val recognizer = runtime.createOnlineRecognizer(config)
recognizer.useResource {
    val session = it.openStream(hotwords = "sherpa")
    session.useResource {
        var update = it.push(AudioData(chunk, 16_000))
        while (!update.isEndpoint) {
            update = it.push(AudioData(nextChunk, 16_000))
        }
        val final = it.finish()
        println(final.result.text)
    }
}
```

Rules that apply to every transport:

- `push` decodes every ready frame before returning, so the text is current
  rather than one chunk behind.
- `finish` decodes the tail and keeps the session queryable through `result()`.
- Closing a session while work is in flight is safe.
- Closing the recognizer closes its sessions.

## 4. TTS

### Sample rate and chunk delivery

`OfflineTts.sampleRate` is authoritative. Do not assume 22 050 or 24 000; the
model decides. Chunks arrive through a cold flow, so each collection synthesizes
again:

```kotlin
val tts = runtime.createOfflineTts(config)
tts.generate(TtsRequest(text = "你好世界")).collect { chunk ->
    player.write(chunk.samples, chunk.sampleRate)
    progress.value = chunk.progress
}
```

### Piper models need phoneme codepoints

sherpa-onnx v2.0 removed the built-in espeak-ng. A Piper VITS model with no
`lexicon.txt` cannot convert text to phonemes on its own, so the caller supplies
Unicode codepoints:

```kotlin
val codepoints = phonemizer.phonemize("Hello world")   // your frontend
    .map { text -> text.map { it.code } }

tts.generate(
    TtsRequest(text = "Hello world", phonemeCodepoints = codepoints),
)
```

Two things to know:

- Each inner list is one phoneme, in IPA, as Unicode codepoints.
- Without codepoints the bridge reports a runtime error naming the missing
  phoneme path. It does not silently return silence.

Models that ship a lexicon, such as `vits-icefall-zh-aishell3`, need nothing
extra; plain text works.

## 5. Model files

`ModelStore` is unchanged, but TTS adds models that carry more than one file:

```kotlin
val config = OfflineTtsConfig(
    model = TtsModel.Vits(
        model = ModelPath("model.onnx"),
        tokens = ModelPath("tokens.txt"),
        lexicon = ModelPath("lexicon.txt"),
    ),
)

val prepared = modelStore.prepare(config.requiredModelFiles())
```

`requiredModelFiles()` is provided for every config so a store downloads exactly
what the config names. Prefer it over hand-listing paths: it is the single place
that knows which files a model needs.

Do not pass absolute paths outside `ModelStore` in public configuration. The
Apple, Android, and web stores each resolve the prepared root differently.

## 6. Result objects

Every typed result still carries `rawJson`. New fields were added with defaults,
so destructuring is unaffected:

- `RecognitionResult` gained nothing that changes existing fields.
- `KeywordSpotterResult` is new.
- `DiarizationSegment` and `DiarizationResult` are new.
- `PunctuationResult` is new.
- `AudioPayload` describes a pulled audio buffer.

Behavior change to be aware of: diarization segments are ordered by start time
but **may overlap** across a speaker change by up to one segmentation window.
Adjacent turns from different speakers are not strictly sequential. Do not
assert `segment.start >= previous.end`.

## 7. Web

Kotlin/JS consumers now have a transport. Two requirements:

- The Wasm module must be served with `application/wasm`.
- The worker script must be served from the same origin as the module, because
  the worker resolves the module URL relative to itself.

Streaming on the web batches operations inside the worker. Do not push
single frames and expect one round trip per frame; push blocks of audio.

## 8. Native library pairing

The bridge ABI is 2. A Kotlin library from 1.x must be paired with a native
library from 1.x. Mismatches fail at startup:

```text
SherpaException: Bridge ABI 1 is not supported; this build requires 2
```

For Apple, the XCFramework is self-contained and carries the matching bridge.
For JVM and Android, upgrade the native JAR and the AAR together. For Linux and
Windows, ship the library from the same release directory as the Kotlin artifact.

## 9. Removed and renamed

Nothing public was removed between 0.4.0 and 1.0.0. The only renames are
internal:

- `ApplePortableTransport` → `NativePortableTransport` (internal, `nativeMain`).
- The Apple-only cinterop source set was folded into `nativeMain`.

If you depended on an `internal` declaration, it was out of contract and may
change without a version bump.
