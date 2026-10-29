// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Real-model coverage for every feature beyond offline ASR, on Kotlin/Native.
 *
 * Apple and the native desktops share one cinterop transport, so they share one
 * suite. Only two things differ per platform and both are declared as expecting
 * functions by the consuming source set:
 *
 * - [featureModelDirectory], the pinned feature-model root, or blank when the
 *   models have not been downloaded.
 * - [readMono16BitWav], because the JVM's `javax.sound` and the Apple
 *   `NSData`-based reader are not available on every target.
 *
 * Every test skips itself when its model is absent, so a machine without the
 * feature models still runs the full contract suite. Prepare the models with
 * `./scripts/prepare-feature-models.sh` and export
 * `SHERPA_ONNX_KMP_FEATURE_MODEL_DIR`.
 *
 * The assertions check behavior, not literal text: a VAD segment carries
 * samples, a keyword is detected or it is not, TTS reports a real sample rate
 * and a non-zero length, diarization resolves the known two-speaker clip into
 * two clusters. Pinning literal transcripts would make every model refresh a
 * test rewrite.
 */
internal abstract class NativeFeatureIntegrationTest {
    protected abstract fun featureModelDirectory(): String

    protected abstract fun readMono16BitWav(path: String): AudioData

    /** True when [relative] names an existing file under the feature root. */
    protected abstract fun featureFileExists(relative: String): Boolean

    /** True when [relative] names an existing directory under the feature root. */
    protected abstract fun featureDirectoryExists(relative: String): Boolean

    private fun root(): String = featureModelDirectory()

    /**
     * Fails rather than silently passing when a required model is missing.
     *
     * A skip is only correct when the feature directory itself is absent, which
     * means the host never asked for feature coverage. Once a directory is
     * configured, a missing model is a broken fixture and must be visible: a
     * silently skipped assertion is indistinguishable from a passing one in the
     * report.
     */
    private fun requireModel(vararg relativePaths: String) {
        for (relative in relativePaths) {
            check(featureFileExists(relative)) {
                "Missing feature fixture: ${root()}/$relative. " +
                    "Run ./scripts/prepare-feature-models.sh"
            }
        }
    }

    private fun requireDirectory(relative: String) {
        check(featureDirectoryExists(relative)) {
            "Missing feature model directory: ${root()}/$relative. " +
                "Run ./scripts/prepare-feature-models.sh"
        }
    }

    @Test
    fun vadDrainsSegmentsFromPinnedSpeech() = runTest {
        val root = root().ifBlank { return@runTest }
        requireModel("silero_vad.onnx", "test_silero_vad.wav")
        val wav = readMono16BitWav("$root/test_silero_vad.wav")
        createSherpaRuntime(FileModelStore(root)).useResource { runtime ->
            assertTrue(
                runtime.capabilities.features.contains(SherpaFeature.VAD),
                "Fixtures are present, so a missing SherpaFeature.VAD capability is a build regression",
            )
            val detector = runtime.createVoiceActivityDetector(
                VadConfig(model = VadModel.Silero(ModelPath("silero_vad.onnx"))),
            )
            detector.useResource {
                // 30 ms frames, the size a live microphone delivers.
                val frame = (wav.sampleRate * 30) / 1000
                val segments = mutableListOf<SpeechSegment>()
                var offset = 0
                while (offset < wav.samples.size) {
                    val end = minOf(offset + frame, wav.samples.size)
                    segments += it.acceptWaveform(
                        AudioData(
                            samples = wav.samples.copyOfRange(offset, end),
                            sampleRate = wav.sampleRate,
                        ),
                    )
                    offset = end
                }
                segments += it.flush()
                assertTrue(
                    segments.isNotEmpty(),
                    "VAD found no speech in the pinned sample",
                )
                assertTrue(
                    segments.all { segment -> segment.samples.isNotEmpty() },
                    "VAD returned an empty segment",
                )
            }
        }
    }

    @Test
    fun streamingAsrTranscribesChunkedAudio() = runTest {
        val root = root().ifBlank { return@runTest }
        val streaming = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
        requireDirectory(streaming)
        requireModel(
            "$streaming/test_wavs/0.wav",
            "$streaming/encoder-epoch-99-avg-1.int8.onnx",
            "$streaming/decoder-epoch-99-avg-1.int8.onnx",
            "$streaming/joiner-epoch-99-avg-1.int8.onnx",
            "$streaming/tokens.txt",
        )
        val modelDir = "$root/$streaming"
        val audio = readMono16BitWav("$modelDir/test_wavs/0.wav")
        createSherpaRuntime(FileModelStore(root)).useResource { runtime ->
            assertTrue(
                runtime.capabilities.features.contains(SherpaFeature.ONLINE_ASR),
                "Fixtures are present, so a missing SherpaFeature.ONLINE_ASR capability is a build regression",
            )
            val recognizer = runtime.createOnlineRecognizer(
                OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        model = OnlineModel.Transducer(
                            encoder = ModelPath("$streaming/encoder-epoch-99-avg-1.int8.onnx"),
                            decoder = ModelPath("$streaming/decoder-epoch-99-avg-1.int8.onnx"),
                            joiner = ModelPath("$streaming/joiner-epoch-99-avg-1.int8.onnx"),
                        ),
                        tokens = ModelPath("$streaming/tokens.txt"),
                    ),
                ),
            )
            recognizer.useResource { asr ->
                val chunked = asr.openStream().useResource { session ->
                    val step = (audio.sampleRate / 10).coerceAtLeast(1)
                    var offset = 0
                    while (offset < audio.samples.size) {
                        val end = minOf(offset + step, audio.samples.size)
                        session.push(
                            AudioData(
                                samples = audio.samples.copyOfRange(offset, end),
                                sampleRate = audio.sampleRate,
                            ),
                        )
                        offset = end
                    }
                    session.finish().result.text
                }
                assertTrue(
                    chunked.isNotBlank(),
                    "Streaming ASR produced no text for the pinned sample",
                )

                // The whole utterance in one push must land on the same words,
                // so the batching policy is not silently changing the output.
                val whole = asr.openStream().useResource { session ->
                    session.push(audio)
                    session.finish().result.text
                }
                assertEquals(
                    whole.trim(),
                    chunked.trim(),
                    "Chunked and whole-utterance streaming disagree",
                )
            }
        }
    }

    @Test
    fun ttsGeneratesAudioFromLexiconModel() = runTest {
        val root = root().ifBlank { return@runTest }
        val modelDir = "vits-icefall-zh-aishell3"
        requireDirectory(modelDir)
        requireModel(
            "$modelDir/model.onnx",
            "$modelDir/tokens.txt",
            "$modelDir/lexicon.txt",
        )
        createSherpaRuntime(FileModelStore("$root/$modelDir")).useResource { runtime ->
            assertTrue(
                runtime.capabilities.features.contains(SherpaFeature.TTS),
                "Fixtures are present, so a missing SherpaFeature.TTS capability is a build regression",
            )
            val tts = runtime.createOfflineTts(
                OfflineTtsConfig(
                    model = TtsModel.Vits(
                        model = ModelPath("model.onnx"),
                        tokens = ModelPath("tokens.txt"),
                        lexicon = ModelPath("lexicon.txt"),
                    ),
                ),
            )
            tts.useResource {
                assertTrue(it.sampleRate > 0, "TTS reported no sample rate")
                val chunks = it.generate(TtsRequest(text = "你好世界")).toList()
                val total = chunks.sumOf { chunk -> chunk.samples.size }
                // "你好世界" is four syllables. Even at the model's 8 kHz rate a
                // real rendering is far longer than a tenth of a second, so a
                // near-empty result means the lexicon path silently produced
                // nothing instead of audio.
                val minimumSamples = it.sampleRate / 10
                assertTrue(
                    total > minimumSamples,
                    "TTS produced $total samples at ${it.sampleRate} Hz, " +
                        "which is too short to be speech",
                )
                assertEquals(1.0f, chunks.last().progress)
                assertTrue(chunks.last().isLast)
                assertTrue(
                    chunks.all { chunk -> chunk.sampleRate == it.sampleRate },
                    "Chunks disagree with the model's sample rate",
                )
            }
        }
    }

    @Test
    fun ttsGeneratesAudioFromPhonemeCodepoints() = runTest {
        val root = root().ifBlank { return@runTest }
        val modelDir = "vits-piper-en_US-amy-low"
        requireDirectory(modelDir)
        requireModel("$modelDir/en_US-amy-low.onnx", "$modelDir/tokens.txt")
        createSherpaRuntime(FileModelStore("$root/$modelDir")).useResource { runtime ->
            assertTrue(
                runtime.capabilities.features.contains(SherpaFeature.TTS),
                "Fixtures are present, so a missing SherpaFeature.TTS capability is a build regression",
            )
            val tts = runtime.createOfflineTts(
                OfflineTtsConfig(
                    model = TtsModel.Vits(
                        model = ModelPath("en_US-amy-low.onnx"),
                        tokens = ModelPath("tokens.txt"),
                    ),
                ),
            )
            tts.useResource {
                assertTrue(it.sampleRate > 0, "TTS reported no sample rate")

                // "hello world" in IPA, as piper_phonemize would emit it.
                val ipa = "hɛˈloʊ wɜːld"
                val chunks = it.generate(
                    TtsRequest(
                        text = "hello world",
                        phonemeCodepoints = listOf(ipa.map { ch -> ch.code }),
                    ),
                ).toList()
                assertTrue(chunks.isNotEmpty(), "TTS produced no chunks")
                val total = chunks.sumOf { chunk -> chunk.samples.size }
                assertTrue(total > 1000, "TTS produced $total samples")
                assertTrue(chunks.last().isLast)
            }

            // Without phonemes the same model must fail, not emit silence. The
            // distinction matters: silent audio would look like a working path
            // in any waveform-length assertion.
            val bare = runCatching {
                tts.generate(TtsRequest(text = "hello world")).toList()
            }
            assertTrue(
                bare.isFailure,
                "A Piper model with no phonemes and no lexicon must fail loudly",
            )
        }
    }

    @Test
    fun speechEnhancerDenoisesPinnedFixture() = runTest {
        val root = root().ifBlank { return@runTest }
        requireModel("gtcrn_simple.onnx")
        val wavRelative = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/test_wavs/0.wav"
        requireModel(wavRelative)
        val audio = readMono16BitWav("$root/$wavRelative")
        createSherpaRuntime(FileModelStore(root)).useResource { runtime ->
            assertTrue(
                runtime.capabilities.features.contains(SherpaFeature.SPEECH_ENHANCEMENT),
                "Fixtures are present, so a missing SherpaFeature.SPEECH_ENHANCEMENT capability is a build regression",
            )
            val enhancer = runtime.createSpeechEnhancer(
                SpeechEnhancementConfig(
                    model = DenoiserModel.Gtcrn(ModelPath("gtcrn_simple.onnx")),
                ),
            )
            enhancer.useResource {
                val denoised = it.denoise(audio)
                assertTrue(denoised.sampleRate > 0, "Enhancer reported no sample rate")
                assertTrue(
                    denoised.samples.isNotEmpty(),
                    "Enhancer returned no audio for the pinned fixture",
                )
                // A denoiser resamples to its own rate, so compare duration
                // rather than sample count: matching lengths would only be
                // true when the rates happened to agree.
                val expected = audio.samples.size.toDouble() / audio.sampleRate
                val actual = denoised.samples.size.toDouble() / denoised.sampleRate
                assertTrue(
                    actual > expected * 0.5 && actual < expected * 1.5,
                    "Enhancer duration ${actual}s is not close to the input ${expected}s",
                )
            }
        }
    }

    @Test
    fun speakerDiarizationSeparatesTwoSpeakers() = runTest {
        val root = root().ifBlank { return@runTest }
        requireDirectory("sherpa-onnx-pyannote-segmentation-3-0")
        requireModel(
            "sherpa-onnx-pyannote-segmentation-3-0/model.onnx",
            "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
            "2-two-speakers-en.wav",
        )
        val audio = readMono16BitWav("$root/2-two-speakers-en.wav")
        createSherpaRuntime(FileModelStore(root)).useResource { runtime ->
            assertTrue(
                runtime.capabilities.features.contains(SherpaFeature.SPEAKER_DIARIZATION),
                "Fixtures are present, so a missing SherpaFeature.SPEAKER_DIARIZATION capability is a build regression",
            )
            val diarization = runtime.createSpeakerDiarization(
                SpeakerDiarizationConfig(
                    segmentationModel = ModelPath(
                        "sherpa-onnx-pyannote-segmentation-3-0/model.onnx",
                    ),
                    embeddingModel = ModelPath(
                        "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
                    ),
                    numClusters = 2,
                ),
            )
            diarization.useResource {
                val result = it.process(audio)
                assertEquals(
                    2,
                    result.numSpeakers,
                    "The pinned two-speaker sample did not resolve to two clusters",
                )
                assertTrue(result.segments.isNotEmpty(), "Diarization returned no turns")
                var previousEnd = 0.0f
                for (segment in result.segments) {
                    assertTrue(segment.endTime > segment.startTime)
                    assertTrue(segment.speaker in 0 until result.numSpeakers)
                    // Adjacent turns overlap at a speaker change, because the
                    // segmentation window is longer than the boundary it
                    // reports. They must still be ordered within one window.
                    assertTrue(
                        segment.startTime >= previousEnd - 1.0f,
                        "Segments are not ordered: $segment after $previousEnd",
                    )
                    previousEnd = maxOf(previousEnd, segment.endTime)
                }
            }
        }
    }

    @Test
    fun punctuationAndKeywordSpottingUsePinnedFixtures() = runTest {
        val root = root().ifBlank { return@runTest }
        createSherpaRuntime(FileModelStore(root)).useResource { runtime ->
            if (runtime.capabilities.features.contains(SherpaFeature.PUNCTUATION)) {
                val punctuationRoot =
                    "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8"
                requireDirectory(punctuationRoot)
                requireModel("$punctuationRoot/model.int8.onnx")
                val punctuation = runtime.createPunctuation(
                    PunctuationConfig(
                        model = ModelPath("$punctuationRoot/model.int8.onnx"),
                    ),
                )
                punctuation.useResource {
                    val result = it.addPunctuation("hello world how are you today")
                    assertTrue(result.text.isNotBlank())
                }
            }

            if (runtime.capabilities.features.contains(SherpaFeature.KEYWORD_SPOTTING)) {
                val kwsRoot = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
                requireDirectory(kwsRoot)
                requireModel(
                    "$kwsRoot/test_wavs/0.wav",
                    "$kwsRoot/tokens.txt",
                    "$kwsRoot/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    "$kwsRoot/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    "$kwsRoot/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                )
                val audio = readMono16BitWav("$root/$kwsRoot/test_wavs/0.wav")
                val spotter = runtime.createKeywordSpotter(
                    KeywordSpotterConfig(
                        modelConfig = OnlineModelConfig(
                            model = OnlineModel.Transducer(
                                encoder = ModelPath(
                                    "$kwsRoot/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                                ),
                                decoder = ModelPath(
                                    "$kwsRoot/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                                ),
                                joiner = ModelPath(
                                    "$kwsRoot/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                                ),
                            ),
                            tokens = ModelPath("$kwsRoot/tokens.txt"),
                        ),
                        keywordsFile = ModelPath("$kwsRoot/test_wavs/test_keywords.txt"),
                    ),
                )
                spotter.useResource { resource ->
                    resource.openStream().useResource { session ->
                        val frame = 1600
                        var detected = false
                        var offset = 0
                        while (offset < audio.samples.size && !detected) {
                            val end = minOf(offset + frame, audio.samples.size)
                            val update = session.push(
                                AudioData(
                                    samples = audio.samples.copyOfRange(offset, end),
                                    sampleRate = audio.sampleRate,
                                ),
                            )
                            if (update.detected) detected = true
                            offset = end
                        }
                        val final = if (detected) null else session.result()
                        assertTrue(
                            detected || final?.detected == true,
                            "No keyword was detected in the pinned KWS sample",
                        )
                    }
                }
            }
        }
    }
}
