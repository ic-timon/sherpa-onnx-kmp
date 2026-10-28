// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the common engine against a scripted bridge.
 *
 * These tests are the platform-independent half of the acceptance criteria:
 * streaming updates, endpoint propagation, VAD draining, TTS chunk flow,
 * capability gating, and close semantics are all decided in `commonMain`, so
 * every target inherits the same behavior once its transport passes the C ABI
 * probe.
 */
class PortableRuntimeTest {
    @Test
    fun reportsFeaturesFromBridgeCapabilities() = runTest {
        createRuntime(FakeBridge()).useResource { runtime ->
            assertEquals(
                setOf(
                    SherpaFeature.OFFLINE_ASR,
                    SherpaFeature.ONLINE_ASR,
                    SherpaFeature.VAD,
                    SherpaFeature.TTS,
                    SherpaFeature.KEYWORD_SPOTTING,
                    SherpaFeature.PUNCTUATION,
                    SherpaFeature.SPEAKER_DIARIZATION,
                    SherpaFeature.SPEECH_ENHANCEMENT,
                ),
                runtime.capabilities.features,
            )
            assertTrue(runtime.capabilities.supportsStreaming)
            assertEquals(2, runtime.backendInfo.bridgeAbiVersion)
            assertEquals("1.12.0", runtime.backendInfo.coreVersion)
        }
    }

    @Test
    fun missingFeatureIsRejectedBeforeTouchingTheTransport() = runTest {
        createRuntime(FakeBridge(resources = setOf("offline-asr"))).useResource { runtime ->
            val failure = assertFailsWith<SherpaUnsupportedException> {
                runtime.createOnlineRecognizer(
                    OnlineRecognizerConfig(
                        modelConfig = OnlineModelConfig(
                            model = OnlineModel.Zipformer2Ctc(ModelPath("model.onnx")),
                            tokens = ModelPath("tokens.txt"),
                        ),
                    ),
                )
            }
            assertTrue(failure.message!!.contains("ONLINE_ASR"))
        }
    }

    @Test
    fun streamingSessionReportsEndpointAndFinal() = runTest {
        val bridge = FakeBridge()
        createRuntime(bridge).useResource { runtime ->
            val recognizer = runtime.createOnlineRecognizer(
                OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        model = OnlineModel.Zipformer2Ctc(ModelPath("model.onnx")),
                        tokens = ModelPath("tokens.txt"),
                    ),
                ),
            )
            recognizer.useResource {
                val session = it.openStream()
                session.useResource {
                    val first = session.push(AudioData(FloatArray(16), 16_000))
                    assertEquals("hello", first.result.text)
                    assertFalse(first.isEndpoint)
                    assertFalse(first.isFinal)

                    val second = session.push(AudioData(FloatArray(16), 16_000))
                    assertEquals("hello world", second.result.text)
                    assertTrue(second.isEndpoint)

                    val final = session.finish()
                    assertTrue(final.isFinal)
                }
            }
        }
        // The session handle is a child of the recognizer, so closing either one
        // must not leave an unclosed handle behind.
        assertEquals(bridge.destroyed, bridge.created)
    }

    @Test
    fun vadDrainsEveryCompletedSegmentInOneCall() = runTest {
        val bridge = FakeBridge()
        createRuntime(bridge).useResource { runtime ->
            val vad = runtime.createVoiceActivityDetector(
                VadConfig(model = VadModel.Silero(ModelPath("silero.onnx"))),
            )
            vad.useResource {
                val segments = vad.acceptWaveform(AudioData(FloatArray(160), 16_000))
                assertEquals(3, segments.size)
                assertEquals(listOf(0L, 480L, 960L), segments.map { it.startSample })
                assertEquals(160, segments.first().samples.size)

                assertTrue(vad.isSpeechDetected())
                // A drain that already emptied the queue returns nothing more.
                assertEquals(
                    emptyList(),
                    vad.flush(),
                )
            }
        }
    }

    @Test
    fun ttsStreamsChunksWithProgressUntilLast() = runTest {
        val bridge = FakeBridge()
        createRuntime(bridge).useResource { runtime ->
            val tts = runtime.createOfflineTts(
                OfflineTtsConfig(
                    model = TtsModel.Vits(
                        model = ModelPath("model.onnx"),
                        tokens = ModelPath("tokens.txt"),
                    ),
                ),
            )
            assertEquals(22_050, tts.sampleRate)
            assertEquals(1, tts.numSpeakers)
            tts.useResource {
                val chunks = it.generate(TtsRequest("hello")).toList()
                assertEquals(3, chunks.size)
                // Progress is reported per finished batch, so three equal chunks
                // land on thirds and the last one is exactly 1.0.
                assertEquals(1.0f, chunks.last().progress)
                assertEquals(chunks.size, chunks.map { c -> c.progress }.distinct().size)
                assertEquals(listOf(false, false, true), chunks.map { c -> c.isLast })
                assertEquals(64, chunks.first().samples.size)
            }
        }
    }

    @Test
    fun punctuationAndDiarizationRoundTripTypedResults() = runTest {
        createRuntime(FakeBridge()).useResource { runtime ->
            runtime.createPunctuation(
                PunctuationConfig(model = ModelPath("punct.onnx")),
            ).useResource { punctuation ->
                val result = punctuation.addPunctuation("你好世界")
                assertEquals("你好，世界。", result.text)
            }

            runtime.createSpeakerDiarization(
                SpeakerDiarizationConfig(
                    segmentationModel = ModelPath("pyannote.onnx"),
                    embeddingModel = ModelPath("embedding.onnx"),
                ),
            ).useResource { diarization ->
                val result = diarization.process(AudioData(FloatArray(320), 16_000))
                assertEquals(2, result.numSpeakers)
                assertEquals(listOf(0, 1), result.segments.map { it.speaker })
            }
        }
    }

    @Test
    fun speechEnhancerReturnsWaveformFromPullAudio() = runTest {
        createRuntime(FakeBridge()).useResource { runtime ->
            runtime.createSpeechEnhancer(
                SpeechEnhancementConfig(
                    model = DenoiserModel.Gtcrn(ModelPath("gtcrn.onnx")),
                ),
            ).useResource { enhancer ->
                val denoised = enhancer.denoise(AudioData(FloatArray(8) { 0.5f }, 16_000))
                assertEquals(16_000, denoised.sampleRate)
                assertEquals(8, denoised.samples.size)
            }
        }
    }

    @Test
    fun keywordSpotterStreamReportsDetection() = runTest {
        createRuntime(FakeBridge()).useResource { runtime ->
            runtime.createKeywordSpotter(
                KeywordSpotterConfig(
                    modelConfig = OnlineModelConfig(
                        model = OnlineModel.Transducer(
                            encoder = ModelPath("encoder.onnx"),
                            decoder = ModelPath("decoder.onnx"),
                            joiner = ModelPath("joiner.onnx"),
                        ),
                        tokens = ModelPath("tokens.txt"),
                    ),
                    keywordsFile = ModelPath("keywords.txt"),
                ),
            ).useResource { spotter ->
                spotter.openStream().useResource { stream ->
                    val result = stream.push(AudioData(FloatArray(16), 16_000))
                    assertTrue(result.detected)
                    assertEquals("hello world", result.keyword)
                }
            }
        }
    }

    @Test
    fun closingRuntimeClosesChildrenAndTransport() = runTest {
        val bridge = FakeBridge()
        val runtime = createRuntime(bridge)
        val recognizer = runtime.createOfflineRecognizer(defaultOfflineConfig())
        runtime.close()

        assertFailsWith<SherpaClosedException> { recognizer.transcribe(AudioData(FloatArray(4), 16_000)) }
        assertTrue(bridge.isClosed)
        assertEquals(bridge.created, bridge.destroyed)
    }

    @Test
    fun resourceWithoutOperationsIsRejectedAtStartup() = runTest {
        // A bridge that lists a resource but no operation for it would let a
        // `create*` call succeed and then fail on the first use with an opaque
        // native status. The runtime refuses to start instead.
        val bridge = FakeBridge(
            resources = setOf("offline-asr", "vad"),
            operations = setOf("health", "offline-asr.set-audio", "offline-asr.transcribe"),
        )
        val failure = assertFailsWith<SherpaOnnxException> {
            createRuntime(bridge)
        }
        assertTrue(failure.message!!.contains("vad"))
    }

    private fun defaultOfflineConfig() = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            model = OfflineModel.NemoCtc(ModelPath("model.onnx")),
            tokens = ModelPath("tokens.txt"),
        ),
    )

    private suspend fun createRuntime(bridge: FakeBridge): SherpaRuntime =
        createPortableSherpaRuntime(bridge, ModelStore { PreparedModel("/models") })
}

/**
 * A scripted bridge that answers every operation the engine uses.
 *
 * The responses match the schemas in `docs/bridge-abi.md`, so a change to the
 * engine's parsing breaks this test rather than a device run.
 */
internal class FakeBridge(
    private val resources: Set<String> = setOf(
        "offline-asr",
        "online-asr",
        "online-session",
        "vad",
        "tts",
        "kws",
        "kws-stream",
        "punctuation",
        "speaker-diarization",
        "speech-denoiser",
    ),
    val operations: Set<String> = setOf(
        "health",
        "offline-asr.set-audio",
        "offline-asr.transcribe",
        "online-asr.create-stream",
        "online-session.push-audio",
        "online-session.decode",
        "online-session.finish",
        "online-session.result",
        "online-session.reset",
        "vad.accept-waveform",
        "vad.flush",
        "vad.take-audio",
        "vad.reset",
        "vad.is-speech-detected",
        "tts.describe",
        "tts.synthesize",
        "tts.take-chunk",
        "kws.create-stream",
        "kws-stream.push-audio",
        "kws-stream.decode",
        "kws-stream.result",
        "kws-stream.reset",
        "punctuation.add-punctuation",
        "speaker-diarization.set-audio",
        "speaker-diarization.process",
        "speech-denoiser.set-audio",
        "speech-denoiser.denoise",
        "speech-denoiser.take-audio",
    ),
) : PortableTransport {
    private val state = ResourceState()
    private var nextHandle = 1L
    private val live = mutableSetOf<BridgeHandle>()

    var created = 0
        private set
    var destroyed = 0
        private set
    val isClosed: Boolean get() = state.isClosed

    private var vadSegments = mutableListOf<Pair<Long, FloatArray>>()
    private var ttsChunks = mutableListOf<FloatArray>()
    private var ttsIndex = 0
    private var pushCount = 0

    override suspend fun infoJson(): String = """
        {
          "abiVersion": 2,
          "schemaVersion": 1,
          "resources": ${resources.toJsonArray()},
          "operations": ${operations.toJsonArray()},
          "coreVersion": "1.12.0",
          "onnxruntimeVersion": "1.22.0"
        }
    """.trimIndent()

    override suspend fun create(kind: String, optionsJson: String): BridgeHandle {
        state.requireOpen()
        require(kind in resources) { "Unsupported kind: $kind" }
        created += 1
        val handle = BridgeHandle(nextHandle++)
        live += handle
        return handle
    }

    override suspend fun call(
        handle: BridgeHandle,
        operation: String,
        argumentsJson: String,
    ): String {
        state.requireOpen()
        require(handle in live) { "Unknown handle: ${handle.value}" }
        return when (operation) {
            "offline-asr.transcribe" -> """{"text":"ok","rawJson":"{}"}"""
            "online-asr.create-stream" -> {
                created += 1
                val child = BridgeHandle(nextHandle++)
                live += child
                """{"handle":${child.value}}"""
            }

            "online-session.decode" -> {
                pushCount += 1
                if (pushCount == 1) {
                    """{"text":"hello","isFinal":false,"isEndpoint":false,"rawJson":"{}"}"""
                } else {
                    """{"text":"hello world","isFinal":true,"isEndpoint":true,"rawJson":"{}"}"""
                }
            }

            "online-session.finish" ->
                """{"text":"hello world","isFinal":true,"isEof":true,"isEndpoint":false,"rawJson":"{}"}"""

            "online-session.result" ->
                """{"text":"hello world","isFinal":false,"isEndpoint":false,"rawJson":"{}"}"""

            "online-session.reset", "vad.reset", "vad.flush", "vad.clear",
            "kws-stream.reset",
            -> """{"ok":true}"""

            "vad.is-speech-detected" -> """{"isSpeechDetected":true}"""
            "vad.empty" -> """{"empty":${vadSegments.isEmpty()}}"""
            "tts.describe" -> """{"sampleRate":22050,"numSpeakers":1,"language":"en"}"""
            "tts.synthesize" -> {
                val request = Json.parseToJsonElement(argumentsJson).jsonObject
                require("text" in request) { "tts.synthesize requires text" }
                ttsChunks = mutableListOf(
                    FloatArray(64) { 0.1f },
                    FloatArray(64) { 0.2f },
                    FloatArray(64) { 0.3f },
                )
                ttsIndex = 0
                """{"sampleRate":22050,"numSpeakers":1,"numChunks":3}"""
            }

            "kws.create-stream" -> {
                created += 1
                val child = BridgeHandle(nextHandle++)
                live += child
                """{"handle":${child.value}}"""
            }

            "kws-stream.decode",
            "kws-stream.result",
            -> """{"keyword":"hello world","detected":true,"startTime":0.5,"rawJson":"{}"}"""

            "punctuation.add-punctuation" ->
                """{"text":"你好，世界。","rawJson":"{}"}"""

            "speaker-diarization.process" -> """
                {"numSpeakers":2,"segments":[
                  {"startTime":0.0,"endTime":1.0,"speaker":0},
                  {"startTime":1.0,"endTime":2.0,"speaker":1}
                ],"rawJson":"{}"}
            """.trimIndent()

            "speech-denoiser.denoise" -> """{"sampleRate":16000,"numSamples":8}"""
            else -> error("Unexpected operation: $operation")
        }
    }

    override suspend fun pushAudio(
        handle: BridgeHandle,
        operation: String,
        audio: AudioData,
    ): String {
        state.requireOpen()
        require(handle in live) { "Unknown handle: ${handle.value}" }
        if (operation == "vad.accept-waveform") {
            vadSegments = mutableListOf(
                0L to FloatArray(160),
                480L to FloatArray(160),
                960L to FloatArray(160),
            )
        }
        return "{}"
    }

    override suspend fun pullAudio(
        handle: BridgeHandle,
        operation: String,
        argumentsJson: String,
    ): BridgeReply {
        state.requireOpen()
        require(handle in live) { "Unknown handle: ${handle.value}" }
        return when (operation) {
            "vad.take-audio" -> {
                val next = vadSegments.removeFirstOrNull() ?: return BridgeReply(
                    json = """{"empty":true,"startSample":-1,"numSamples":0}""",
                )
                BridgeReply(
                    json = """{"empty":false,"startSample":${next.first},""" +
                        """"numSamples":${next.second.size},"remaining":${vadSegments.isNotEmpty()}}""",
                    audio = AudioChunk(next.second, 16_000, progress = 1.0f, isLast = true),
                )
            }

            "tts.take-chunk" -> {
                val samples = ttsChunks.getOrNull(ttsIndex) ?: return BridgeReply(
                    json = """{"sampleRate":0,"numSamples":0,"progress":1,"isLast":true}""",
                )
                val isLast = ttsIndex == ttsChunks.lastIndex
                val progress = (ttsIndex + 1).toFloat() / ttsChunks.size
                ttsIndex += 1
                BridgeReply(
                    json = """{"sampleRate":22050,"numSamples":${samples.size},""" +
                        """"progress":$progress,"isLast":$isLast}""",
                    audio = AudioChunk(samples, 22_050, progress, isLast),
                )
            }

            "speech-denoiser.take-audio" -> BridgeReply(
                json = """{"sampleRate":16000,"numSamples":8}""",
                audio = AudioChunk(FloatArray(8), 16_000, progress = 1.0f, isLast = true),
            )

            else -> error("Unexpected audio operation: $operation")
        }
    }

    override suspend fun destroy(handle: BridgeHandle) {
        live -= handle
        destroyed += 1
    }

    override suspend fun close() {
        state.markClosed()
        live.clear()
    }

    private fun Set<String>.toJsonArray(): String =
        joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
}

private fun List<Float>.asProgress() = this
