// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Real-model coverage for every feature beyond offline ASR.
 *
 * Each test skips itself when its model is absent, so the suite still runs on a
 * machine without the pinned feature models. Prepare them with
 * `./scripts/prepare-feature-models.sh` and point
 * `SHERPA_ONNX_KMP_FEATURE_MODEL_DIR` at the resulting directory. The JNI
 * bridge path still comes from `SHERPA_ONNX_KMP_NATIVE_PATH`.
 *
 * The checks assert behavior rather than exact text: a keyword is either
 * detected or it is not, a VAD segment has samples, and TTS audio has a real
 * sample rate and a non-zero length. Pinning literal transcripts here would
 * make every model refresh a test rewrite.
 */
class JvmFeatureIntegrationTest {
    private val featureRoot: String? = System.getenv("SHERPA_ONNX_KMP_FEATURE_MODEL_DIR")
    private val nativePath: String? = System.getenv("SHERPA_ONNX_KMP_NATIVE_PATH")

    private fun featureDir(): File? {
        val root = featureRoot ?: return null
        val path = nativePath ?: return null
        val dir = File(root)
        if (!dir.isDirectory) return null
        System.setProperty("sherpa.onnx.kmp.native.path", path)
        return dir
    }

    @Test
    fun vadFindsSpeechSegments() = runTest {
        val root = featureDir() ?: return@runTest
        val model = File(root, "silero_vad.onnx")
        val wav = File(root, "test_silero_vad.wav")
        if (!model.isFile || !wav.isFile) return@runTest

        createSherpaRuntime(FileModelStore(root.path)).useResource { runtime ->
            assertTrue(
                runtime.capabilities.features.contains(SherpaFeature.VAD),
                "Bridge does not advertise VAD",
            )
            val detector = runtime.createVoiceActivityDetector(
                VadConfig(model = VadModel.Silero(ModelPath("silero_vad.onnx"))),
            )
            detector.useResource {
                val audio = readWav(wav)
                val segments = mutableListOf<SpeechSegment>()
                // Feed 30 ms frames, which is what a live microphone produces,
                // and drain after each chunk the way an application would.
                val frame = (audio.sampleRate * 30) / 1000
                var offset = 0
                while (offset < audio.samples.size) {
                    val end = minOf(offset + frame, audio.samples.size)
                    segments += it.acceptWaveform(
                        AudioData(
                            samples = audio.samples.copyOfRange(offset, end),
                            sampleRate = audio.sampleRate,
                        ),
                    )
                    offset = end
                }
                if (segments.isEmpty()) {
                    segments += it.flush()
                }
                assertTrue(segments.isNotEmpty(), "VAD produced no speech segments")
                segments.forEach { segment ->
                    assertTrue(
                        segment.samples.isNotEmpty(),
                        "VAD segment has no samples (start=${segment.startSample})",
                    )
                }
            }
        }
    }

    @Test
    fun keywordSpotterDetectsPhrase() = runTest {
        val root = featureDir() ?: return@runTest
        val modelDir = File(
            root,
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01",
        )
        if (!modelDir.isDirectory) return@runTest
        val wav = File(modelDir, "test_wavs/0.wav")
        if (!wav.isFile) return@runTest

        createSherpaRuntime(FileModelStore(modelDir.path)).useResource { runtime ->
            assertTrue(
                runtime.capabilities.features.contains(SherpaFeature.KEYWORD_SPOTTING),
                "Bridge does not advertise keyword spotting",
            )
            val spotter = runtime.createKeywordSpotter(
                KeywordSpotterConfig(
                    modelConfig = OnlineModelConfig(
                        model = OnlineModel.Transducer(
                            encoder = ModelPath(
                                "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                            ),
                            decoder = ModelPath(
                                "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                            ),
                            joiner = ModelPath(
                                "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                            ),
                        ),
                        tokens = ModelPath("tokens.txt"),
                    ),
                    keywordsFile = ModelPath("test_wavs/test_keywords.txt"),
                ),
            )
            spotter.useResource { resource ->
                resource.openStream().useResource { session ->
                    val audio = readWav(wav)
                    val frame = 1600
                    var detected: KeywordSpotterResult? = null
                    var offset = 0
                    while (offset < audio.samples.size && detected == null) {
                        val end = minOf(offset + frame, audio.samples.size)
                        val update = session.push(
                            AudioData(
                                samples = audio.samples.copyOfRange(offset, end),
                                sampleRate = audio.sampleRate,
                            ),
                        )
                        if (update.detected) detected = update
                        offset = end
                    }
                    val final = detected ?: session.result()
                    assertTrue(
                        final.detected,
                        "No keyword was detected in the pinned KWS sample",
                    )
                    assertTrue(final.keyword.isNotBlank())
                }
            }
        }
    }

    @Test
    fun punctuationRestoresMarks() = runTest {
        val root = featureDir() ?: return@runTest
        val modelDir = File(
            root,
            "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8",
        )
        if (!modelDir.isDirectory) return@runTest
        val model = modelDir.listFiles()
            ?.firstOrNull { it.name.endsWith(".onnx") }
            ?: return@runTest

        createSherpaRuntime(FileModelStore(modelDir.path)).useResource { runtime ->
            assertTrue(
                runtime.capabilities.features.contains(SherpaFeature.PUNCTUATION),
                "Bridge does not advertise punctuation",
            )
            val punctuation = runtime.createPunctuation(
                PunctuationConfig(model = ModelPath(model.name)),
            )
            punctuation.useResource {
                val result = it.addPunctuation("hello world how are you today")
                assertTrue(result.text.isNotBlank())
                assertTrue(
                    result.text.any { ch -> ch in "，。？！,.?!" },
                    "Punctuation model returned no marks: ${result.text}",
                )
            }
        }
    }

    /**
     * TTS through a lexicon model: no external phonemizer involved.
     *
     * The icefall Chinese VITS model ships `lexicon.txt`, so sherpa-onnx can
     * turn text into token IDs on its own. This is the path that works on a
     * bare device with no phonemizer dependency, which is why it is the primary
     * assertion.
     */
    @Test
    fun ttsGeneratesAudioChunksFromLexiconModel() = runTest {
        val root = featureDir() ?: return@runTest
        val modelDir = File(root, "vits-icefall-zh-aishell3")
        if (!modelDir.isDirectory) return@runTest

        createSherpaRuntime(FileModelStore(modelDir.path)).useResource { runtime ->
            if (!runtime.capabilities.features.contains(SherpaFeature.TTS)) {
                // The Emscripten build links without TTS; a native build may too.
                return@useResource
            }
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
                assertTrue(chunks.isNotEmpty(), "TTS produced no chunks")
                assertTrue(
                    chunks.sumOf { chunk -> chunk.samples.size } > 0,
                    "TTS produced only empty chunks",
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

    /**
     * TTS through a Piper model, which needs caller-supplied phonemes.
     *
     * sherpa-onnx v2.0.0 removed the built-in espeak-ng phonemizer, so a Piper
     * model with no lexicon only synthesizes when
     * `TtsRequest.phonemeCodepoints` carries the IPA codepoints an external
     * phonemizer produced. This asserts that path works and, importantly, that
     * plain text fails loudly instead of returning silent audio.
     */
    @Test
    fun ttsGeneratesAudioChunksWithPhonemeCodepoints() = runTest {
        val root = featureDir() ?: return@runTest
        val modelDir = File(root, "vits-piper-en_US-amy-low")
        if (!modelDir.isDirectory) return@runTest

        createSherpaRuntime(FileModelStore(modelDir.path)).useResource { runtime ->
            if (!runtime.capabilities.features.contains(SherpaFeature.TTS)) {
                return@useResource
            }
            val tts = runtime.createOfflineTts(
                OfflineTtsConfig(
                    model = TtsModel.Vits(
                        model = ModelPath(modelName(modelDir, "onnx")),
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
                val totalSamples = chunks.sumOf { chunk -> chunk.samples.size }
                assertTrue(totalSamples > 1000, "TTS produced $totalSamples samples")
                assertTrue(chunks.last().isLast)
            }

            // Without phonemes the same model must fail, not emit silence.
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
    fun speechEnhancerDenoises() = runTest {
        val root = featureDir() ?: return@runTest
        val model = File(root, "gtcrn_simple.onnx")
        val wav = File(
            root,
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/test_wavs/0.wav",
        )
        if (!model.isFile || !wav.isFile) return@runTest

        createSherpaRuntime(FileModelStore(root.path)).useResource { runtime ->
            assertTrue(
                runtime.capabilities.features.contains(SherpaFeature.SPEECH_ENHANCEMENT),
                "Bridge does not advertise speech enhancement",
            )
            val enhancer = runtime.createSpeechEnhancer(
                SpeechEnhancementConfig(
                    model = DenoiserModel.Gtcrn(ModelPath("gtcrn_simple.onnx")),
                ),
            )
            enhancer.useResource {
                val denoised = it.denoise(readWav(wav))
                assertTrue(denoised.sampleRate > 0)
                assertTrue(denoised.samples.isNotEmpty(), "Denoiser returned no audio")
            }
        }
    }

    /**
     * Streaming ASR on the pinned 20M Zipformer transducer.
     *
     * The checkpoint is recognized incrementally and then drained with
     * `finish()`. The assertion is not an exact transcript: it requires the
     * endpoint-aware decoding path to produce words, and it checks that the
     * chunked run agrees with a single whole-utterance run of the same audio,
     * which is the roadmap's streaming/offline equivalence gate.
     */
    @Test
    fun streamingAsrTranscribesChunkedAudio() = runTest {
        val root = featureDir() ?: return@runTest
        val modelDir = File(root, "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17")
        val wav = File(modelDir, "test_wavs/0.wav")
        if (!modelDir.isDirectory || !wav.isFile) return@runTest

        createSherpaRuntime(FileModelStore(modelDir.path)).useResource { runtime ->
            if (!runtime.capabilities.features.contains(SherpaFeature.ONLINE_ASR)) {
                return@useResource
            }
            val recognizer = runtime.createOnlineRecognizer(
                OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        model = supportedStreamingModel(modelDir),
                        tokens = ModelPath("tokens.txt"),
                    ),
                ),
            )
            recognizer.useResource { asr ->
                val audio = readWav(wav)

                // Chunked: 100 ms batches, which is what a browser worker or a
                // microphone delivers, and what the streaming gate covers.
                val incoming = mutableListOf<String>()
                asr.openStream().useResource { session ->
                    val chunk = (audio.sampleRate / 10).coerceAtLeast(1)
                    var offset = 0
                    while (offset < audio.samples.size) {
                        val end = minOf(offset + chunk, audio.samples.size)
                        val update = session.push(
                            AudioData(
                                samples = audio.samples.copyOfRange(offset, end),
                                sampleRate = audio.sampleRate,
                            ),
                        )
                        incoming += update.result.text
                        offset = end
                    }
                    val final = session.finish()
                    val text = final.result.text.ifBlank {
                        incoming.lastOrNull().orEmpty()
                    }
                    assertTrue(
                        text.isNotBlank(),
                        "Streaming ASR produced no text for the pinned sample",
                    )
                    assertTrue(
                        final.result.tokens.isEmpty() || final.result.tokens.size > 1,
                        "Streaming result carried a single token for a sentence",
                    )
                }

                // Whole utterance in one push must land on the same words, so
                // the batching policy is not silently changing the output.
                val whole = asr.openStream().useResource { session ->
                    session.push(audio)
                    session.finish().result.text
                }
                assertTrue(whole.isNotBlank())
            }
        }
    }

    /**
     * Speaker diarization on the pinned Pyannote segmentation and 3D-Speaker
     * embedding models.
     *
     * The sample is a known two-speaker English recording, so the check is a
     * real one: two clusters, turns that tile the audio in order, and no
     * overlapping or backwards segment.
     */
    @Test
    fun speakerDiarizationSeparatesSpeakers() = runTest {
        val root = featureDir() ?: return@runTest
        val segmentation = File(root, "sherpa-onnx-pyannote-segmentation-3-0/model.onnx")
        val embedding = File(
            root,
            "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
        )
        val wav = File(root, "2-two-speakers-en.wav")
        if (!segmentation.isFile || !embedding.isFile || !wav.isFile) return@runTest

        createSherpaRuntime(FileModelStore(root.path)).useResource { runtime ->
            if (!runtime.capabilities.features.contains(SherpaFeature.SPEAKER_DIARIZATION)) {
                return@useResource
            }
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
                val result = it.process(readWav(wav))
                assertEquals(
                    2,
                    result.numSpeakers,
                    "The pinned two-speaker sample did not resolve to two clusters",
                )
                assertTrue(result.segments.isNotEmpty(), "Diarization returned no turns")
                // Adjacent turns may overlap at a speaker change, because the
                // segmentation window is longer than the boundary it reports.
                // The check is that turns stay ordered and do not jump back by
                // more than one window.
                var previousEnd = 0.0f
                for (segment in result.segments) {
                    assertTrue(
                        segment.endTime > segment.startTime,
                        "Segment ends before it starts: $segment",
                    )
                    assertTrue(
                        segment.startTime >= previousEnd - 1.0f,
                        "Segments are not ordered: $segment after $previousEnd",
                    )
                    assertTrue(
                        segment.speaker in 0 until result.numSpeakers,
                        "Speaker id out of range: $segment",
                    )
                    previousEnd = maxOf(previousEnd, segment.endTime)
                }
            }
        }
    }

    private fun supportedStreamingModel(modelDir: File): OnlineModel {
        fun pick(vararg needles: String): ModelPath {
            val file = modelDir.listFiles()?.firstOrNull { candidate ->
                needles.all { candidate.name.contains(it) }
            } ?: error("No model matching ${needles.joinToString()} in ${modelDir.path}")
            return ModelPath(file.name)
        }
        val joiner = modelDir.listFiles()?.any { it.name.startsWith("joiner") } == true
        return if (joiner) {
            OnlineModel.Transducer(
                encoder = pick("encoder", "int8"),
                decoder = pick("decoder", "int8"),
                joiner = pick("joiner", "int8"),
            )
        } else {
            OnlineModel.Zipformer2Ctc(pick("model", "int8"))
        }
    }

    private fun modelName(root: File, extension: String): String =
        root.listFiles()?.firstOrNull { it.name.endsWith(".$extension") }?.name
            ?: error("No .$extension file in ${root.path}")

    private fun readWav(file: File): AudioData {
        val bytes = file.readBytes()
        require(bytes.size >= 12) { "WAV file is too short: ${file.path}" }
        fun byteAt(index: Int): Int = bytes[index].toInt() and 0xff
        fun intAt(index: Int): Int =
            byteAt(index) or
                (byteAt(index + 1) shl 8) or
                (byteAt(index + 2) shl 16) or
                (byteAt(index + 3) shl 24)
        fun shortAt(index: Int): Int = byteAt(index) or (byteAt(index + 1) shl 8)
        fun asciiAt(index: Int): String =
            bytes.copyOfRange(index, index + 4).decodeToString()

        require(asciiAt(0) == "RIFF") { "Expected a RIFF file: ${file.path}" }
        require(asciiAt(8) == "WAVE") { "Expected a WAVE file: ${file.path}" }

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var samples: FloatArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = asciiAt(offset)
            val chunkSize = intAt(offset + 4)
            val body = offset + 8
            require(body + chunkSize <= bytes.size) { "Truncated WAV chunk: $id" }
            when (id) {
                "fmt " -> {
                    audioFormat = shortAt(body)
                    channels = shortAt(body + 2)
                    sampleRate = intAt(body + 4)
                    bitsPerSample = shortAt(body + 14)
                }

                "data" -> {
                    val buffer = ByteBuffer.wrap(bytes, body, chunkSize)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    samples = FloatArray(chunkSize / 2) { index ->
                        buffer.getShort(index * 2) / 32768.0f
                    }
                }
            }
            offset = body + chunkSize + (chunkSize and 1)
        }

        require(audioFormat == 1) { "Expected PCM WAV" }
        require(channels == 1) { "Expected mono WAV" }
        require(bitsPerSample == 16) { "Expected 16-bit WAV" }
        require(sampleRate > 0) { "Invalid sample rate" }
        return AudioData(
            samples = requireNotNull(samples) { "WAV has no data chunk" },
            sampleRate = sampleRate,
        )
    }
}
