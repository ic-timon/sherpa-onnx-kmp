// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val bridgeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/**
 * Builds the shared runtime from a transport's capability report.
 *
 * Capabilities come from the bridge itself, so a build that omits a feature
 * (for example the current Emscripten TTS flag) reports it as absent and the
 * matching `create*` call fails with a typed exception instead of a native
 * load error later.
 */
internal suspend fun createPortableSherpaRuntime(
    transport: PortableTransport,
    modelStore: ModelStore,
): SherpaRuntime = try {
    val info = bridgeJson.parseToJsonElement(transport.infoJson()).jsonObject
    val resources = info.stringArray("resources")
    val operations = info.stringArray("operations")

    val features = buildSet {
        if ("offline-asr" in resources) add(SherpaFeature.OFFLINE_ASR)
        if ("online-asr" in resources) add(SherpaFeature.ONLINE_ASR)
        if ("vad" in resources) add(SherpaFeature.VAD)
        if ("tts" in resources) add(SherpaFeature.TTS)
        if ("kws" in resources) add(SherpaFeature.KEYWORD_SPOTTING)
        if ("punctuation" in resources) add(SherpaFeature.PUNCTUATION)
        if ("speaker-diarization" in resources) add(SherpaFeature.SPEAKER_DIARIZATION)
        if ("speech-denoiser" in resources) add(SherpaFeature.SPEECH_ENHANCEMENT)
    }
    val capabilities = SherpaCapabilities(
        features = features,
        providers = setOf("cpu"),
        supportsStreaming = "online-asr" in resources,
        // Cancellation is supported for suspended transport calls, but a native
        // decode already running inside the worker cannot be interrupted.
        supportsCancellation = "online-asr" in resources,
    )
    // A resource with no advertised operations would let `create*` succeed and
    // then fail on the first call with an opaque native status, so the mismatch
    // is reported as a build error instead.
    val emptyResources = resources.filter { kind ->
        operations.none { it == "$kind.health" || it.startsWith("$kind.") }
    }
    if (emptyResources.isNotEmpty()) {
        throw SherpaOnnxException(
            "Bridge advertises resources without operations: $emptyResources",
        )
    }
    val backendInfo = BackendInfo(
        bridgeAbiVersion =
            info["abiVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        coreVersion = info["coreVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        onnxRuntimeVersion =
            info["onnxruntimeVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
    PortableSherpaRuntime(
        transport = transport,
        modelStore = modelStore,
        capabilities = capabilities,
        backendInfo = backendInfo,
        supportedOperations = operations,
    )
} catch (t: Throwable) {
    transport.close()
    throw t
}

private fun JsonObject.stringArray(key: String): Set<String> =
    this[key]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.toSet()
        ?: emptySet()

internal class PortableSherpaRuntime(
    private val transport: PortableTransport,
    private val modelStore: ModelStore,
    override val capabilities: SherpaCapabilities,
    override val backendInfo: BackendInfo,
    private val supportedOperations: Set<String>,
) : SherpaRuntime {
    private val state = ResourceState()
    private val mutex = Mutex()
    private val children = linkedSetOf<SherpaResource>()

    @Throws(Throwable::class)
    override suspend fun createOfflineRecognizer(
        config: OfflineRecognizerConfig,
    ): OfflineRecognizer = mutex.withLock {
        state.requireOpen()
        requireFeature(SherpaFeature.OFFLINE_ASR)
        val preparedModel = modelStore.prepare(config.requiredModelFiles())
        val handle = transport.create(
            kind = "offline-asr",
            optionsJson = config.toBridgeOptionsJson(preparedModel),
        )
        PortableOfflineRecognizer(
            transport = transport,
            handle = handle,
            onClosed = { children.remove(it) },
        ).tracked()
    }

    @Throws(Throwable::class)
    override suspend fun createOnlineRecognizer(
        config: OnlineRecognizerConfig,
    ): OnlineRecognizer = mutex.withLock {
        state.requireOpen()
        requireFeature(SherpaFeature.ONLINE_ASR)
        val preparedModel = modelStore.prepare(config.requiredModelFiles())
        val handle = transport.create(
            kind = "online-asr",
            optionsJson = config.toBridgeOptionsJson(preparedModel),
        )
        PortableOnlineRecognizer(
            transport = transport,
            handle = handle,
            onClosed = { children.remove(it) },
        ).tracked()
    }

    @Throws(Throwable::class)
    override suspend fun createVoiceActivityDetector(
        config: VadConfig,
    ): VoiceActivityDetector = mutex.withLock {
        state.requireOpen()
        requireFeature(SherpaFeature.VAD)
        val preparedModel = modelStore.prepare(config.requiredModelFiles())
        val handle = transport.create(
            kind = "vad",
            optionsJson = config.toBridgeOptionsJson(preparedModel),
        )
        PortableVoiceActivityDetector(
            transport = transport,
            handle = handle,
            sampleRate = config.sampleRate,
            onClosed = { children.remove(it) },
        ).tracked()
    }

    @Throws(Throwable::class)
    override suspend fun createOfflineTts(
        config: OfflineTtsConfig,
    ): OfflineTts = mutex.withLock {
        state.requireOpen()
        requireFeature(SherpaFeature.TTS)
        val preparedModel = modelStore.prepare(config.requiredModelFiles())
        val handle = transport.create(
            kind = "tts",
            optionsJson = config.toBridgeOptionsJson(preparedModel),
        )
        // Sample rate and speaker count only exist after the model loads, so
        // they are read once here instead of on every generate call.
        val description = bridgeJson.parseToJsonElement(
            transport.call(handle, "tts.describe"),
        ).jsonObject
        PortableOfflineTts(
            transport = transport,
            handle = handle,
            sampleRate = description["sampleRate"]?.jsonPrimitive?.int ?: 0,
            numSpeakers = description["numSpeakers"]?.jsonPrimitive?.int ?: 0,
            onClosed = { children.remove(it) },
        ).tracked()
    }

    @Throws(Throwable::class)
    override suspend fun createKeywordSpotter(
        config: KeywordSpotterConfig,
    ): KeywordSpotter = mutex.withLock {
        state.requireOpen()
        requireFeature(SherpaFeature.KEYWORD_SPOTTING)
        val preparedModel = modelStore.prepare(config.requiredModelFiles())
        val handle = transport.create(
            kind = "kws",
            optionsJson = config.toBridgeOptionsJson(preparedModel),
        )
        PortableKeywordSpotter(
            transport = transport,
            handle = handle,
            onClosed = { children.remove(it) },
        ).tracked()
    }

    @Throws(Throwable::class)
    override suspend fun createPunctuation(
        config: PunctuationConfig,
    ): Punctuation = mutex.withLock {
        state.requireOpen()
        requireFeature(SherpaFeature.PUNCTUATION)
        val preparedModel = modelStore.prepare(config.requiredModelFiles())
        val handle = transport.create(
            kind = "punctuation",
            optionsJson = config.toBridgeOptionsJson(preparedModel),
        )
        PortablePunctuation(
            transport = transport,
            handle = handle,
            onClosed = { children.remove(it) },
        ).tracked()
    }

    @Throws(Throwable::class)
    override suspend fun createSpeakerDiarization(
        config: SpeakerDiarizationConfig,
    ): SpeakerDiarization = mutex.withLock {
        state.requireOpen()
        requireFeature(SherpaFeature.SPEAKER_DIARIZATION)
        val preparedModel = modelStore.prepare(config.requiredModelFiles())
        val handle = transport.create(
            kind = "speaker-diarization",
            optionsJson = config.toBridgeOptionsJson(preparedModel),
        )
        PortableSpeakerDiarization(
            transport = transport,
            handle = handle,
            onClosed = { children.remove(it) },
        ).tracked()
    }

    @Throws(Throwable::class)
    override suspend fun createSpeechEnhancer(
        config: SpeechEnhancementConfig,
    ): SpeechEnhancer = mutex.withLock {
        state.requireOpen()
        requireFeature(SherpaFeature.SPEECH_ENHANCEMENT)
        val preparedModel = modelStore.prepare(config.requiredModelFiles())
        val handle = transport.create(
            kind = "speech-denoiser",
            optionsJson = config.toBridgeOptionsJson(preparedModel),
        )
        PortableSpeechEnhancer(
            transport = transport,
            handle = handle,
            onClosed = { children.remove(it) },
        ).tracked()
    }

    @Throws(Throwable::class)
    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            children.toList().forEach { it.close() }
            children.clear()
            transport.close()
        }
    }

    /** Registers a freshly created child so [close] can cascade. */
    private fun <T : SherpaResource> T.tracked(): T = also { children += it }

    private fun requireFeature(feature: SherpaFeature) {
        if (feature !in capabilities.features) {
            throw SherpaUnsupportedException(
                "Feature is not supported by this runtime: $feature. " +
                    "Supported features: ${capabilities.features}",
            )
        }
    }
}

internal class PortableOfflineRecognizer(
    private val transport: PortableTransport,
    private val handle: BridgeHandle,
    private val onClosed: (PortableOfflineRecognizer) -> Unit,
) : OfflineRecognizer {
    private val state = ResourceState()
    private val mutex = Mutex()

    @Throws(Throwable::class)
    override suspend fun transcribe(audio: AudioData): RecognitionResult = mutex.withLock {
        state.requireOpen()
        transport.pushAudio(handle, "offline-asr.set-audio", audio)
        val resultJson = transport.call(handle, "offline-asr.transcribe")
        bridgeJson.decodeFromString(RecognitionResult.serializer(), resultJson)
    }

    @Throws(Throwable::class)
    override suspend fun transcribeBatch(audio: List<AudioData>): List<RecognitionResult> =
        audio.map { transcribe(it) }

    @Throws(Throwable::class)
    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            try {
                transport.destroy(handle)
            } finally {
                onClosed(this)
            }
        }
    }
}

internal class PortableOnlineRecognizer(
    private val transport: PortableTransport,
    private val handle: BridgeHandle,
    private val onClosed: (PortableOnlineRecognizer) -> Unit,
) : OnlineRecognizer {
    private val state = ResourceState()
    private val mutex = Mutex()
    private val sessions = linkedSetOf<PortableOnlineSession>()

    @Throws(Throwable::class)
    override suspend fun openStream(hotwords: String): OnlineSession = mutex.withLock {
        state.requireOpen()
        val arguments = if (hotwords.isBlank()) {
            "{}"
        } else {
            JsonObject(mapOf("hotwords" to bridgeString(hotwords))).encodeOptions()
        }
        val reply = transport.call(handle, "online-asr.create-stream", arguments)
        val sessionHandle = BridgeHandle(
            bridgeJson.parseToJsonElement(reply).jsonObject
                .getValue("handle").jsonPrimitive.content.toLong(),
        )
        PortableOnlineSession(
            transport = transport,
            handle = sessionHandle,
            onClosed = { sessions.remove(it) },
        ).also { sessions += it }
    }

    @Throws(Throwable::class)
    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            // Child streams are destroyed by the bridge with their parent, but
            // closing them first releases each stream's memory immediately.
            sessions.toList().forEach { it.close() }
            sessions.clear()
            try {
                transport.destroy(handle)
            } finally {
                onClosed(this)
            }
        }
    }
}

internal class PortableOnlineSession(
    private val transport: PortableTransport,
    private val handle: BridgeHandle,
    private val onClosed: (PortableOnlineSession) -> Unit,
) : OnlineSession {
    private val state = ResourceState()
    private val mutex = Mutex()

    override suspend fun push(audio: AudioData): OnlineUpdate = mutex.withLock {
        state.requireOpen()
        // Push and decode are one round trip: the bridge accepts the samples and
        // decodes every ready frame, then reports both the transcript and the
        // endpoint flag. That keeps the web transport from doing work per frame.
        transport.pushAudio(handle, "online-session.push-audio", audio)
        val json = transport.call(handle, "online-session.decode")
        parseOnlineUpdate(json)
    }

    override suspend fun finish(): OnlineUpdate = mutex.withLock {
        state.requireOpen()
        parseOnlineUpdate(transport.call(handle, "online-session.finish"))
    }

    override suspend fun result(): OnlineUpdate = mutex.withLock {
        state.requireOpen()
        parseOnlineUpdate(transport.call(handle, "online-session.result"))
    }

    override suspend fun reset() = mutex.withLock {
        state.requireOpen()
        transport.call(handle, "online-session.reset")
        Unit
    }

    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            try {
                transport.destroy(handle)
            } finally {
                onClosed(this)
            }
        }
    }

    private fun parseOnlineUpdate(json: String): OnlineUpdate {
        val objectValue = bridgeJson.parseToJsonElement(json).jsonObject
        val endpoint = objectValue["isEndpoint"]?.jsonPrimitive?.booleanOrNull
            ?: objectValue["isFinal"]?.jsonPrimitive?.booleanOrNull
            ?: false
        val final = objectValue["isFinal"]?.jsonPrimitive?.booleanOrNull ?: false
        val eof = objectValue["isEof"]?.jsonPrimitive?.booleanOrNull ?: false
        return OnlineUpdate(
            result = bridgeJson.decodeFromString(RecognitionResult.serializer(), json),
            isEndpoint = endpoint,
            isFinal = final || eof,
        )
    }
}

internal class PortableVoiceActivityDetector(
    private val transport: PortableTransport,
    private val handle: BridgeHandle,
    private val sampleRate: Int,
    private val onClosed: (PortableVoiceActivityDetector) -> Unit,
) : VoiceActivityDetector {
    private val state = ResourceState()
    private val mutex = Mutex()

    override suspend fun acceptWaveform(audio: AudioData): List<SpeechSegment> =
        mutex.withLock {
            state.requireOpen()
            transport.pushAudio(handle, "vad.accept-waveform", audio)
            drainSegments()
        }

    override suspend fun flush(): List<SpeechSegment> = mutex.withLock {
        state.requireOpen()
        transport.call(handle, "vad.flush")
        drainSegments()
    }

    override suspend fun reset() = mutex.withLock {
        state.requireOpen()
        transport.call(handle, "vad.reset")
        Unit
    }

    override suspend fun isSpeechDetected(): Boolean = mutex.withLock {
        state.requireOpen()
        val json = transport.call(handle, "vad.is-speech-detected")
        bridgeJson.parseToJsonElement(json).jsonObject["isSpeechDetected"]
            ?.jsonPrimitive?.booleanOrNull
            ?: false
    }

    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            try {
                transport.destroy(handle)
            } finally {
                onClosed(this)
            }
        }
    }

    /**
     * Pops every completed segment, taking its samples in the same round trip.
     *
     * The loop stops on the first reply that carries no audio, which the bridge
     * reports with `empty: true`. A bounded iteration count guards against a
     * bridge that reports audio without consuming the queue.
     */
    private suspend fun drainSegments(): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        repeat(MaxSegmentsPerDrain) {
            val reply = transport.pullAudio(handle, "vad.take-audio")
            val chunk = reply.audio
            val objectValue = bridgeJson.parseToJsonElement(reply.json).jsonObject
            if (chunk == null || chunk.samples.isEmpty()) {
                return segments
            }
            val startSample = objectValue["startSample"]?.jsonPrimitive
                ?.contentOrNull?.toLongOrNull() ?: 0L
            segments += SpeechSegment(
                startSample = startSample,
                samples = chunk.samples,
            )
            val remaining = objectValue["remaining"]?.jsonPrimitive?.booleanOrNull
            if (remaining == false) {
                return segments
            }
        }
        return segments
    }

    private companion object {
        const val MaxSegmentsPerDrain = 512
    }
}

internal class PortableOfflineTts(
    private val transport: PortableTransport,
    private val handle: BridgeHandle,
    override val sampleRate: Int,
    override val numSpeakers: Int,
    private val onClosed: (PortableOfflineTts) -> Unit,
) : OfflineTts {
    private val state = ResourceState()
    private val mutex = Mutex()

    /**
     * Serializes whole syntheses.
     *
     * The bridge queues chunks per handle, so two concurrent syntheses would
     * interleave their audio. This lock is separate from [mutex] because it is
     * held across `emit`, and a slow collector must not block [close].
     */
    private val synthesizeMutex = Mutex()

    override fun generate(request: TtsRequest): Flow<AudioChunk> = flow {
        synthesizeMutex.withLock {
            mutex.withLock {
                state.requireOpen()
                val arguments = JsonObject(
                    buildMap {
                        put("text", bridgeString(request.text))
                        put("speakerId", bridgeInt(request.speakerId))
                        put("speed", bridgeDouble(request.speed.toDouble()))
                        if (request.phonemeCodepoints.isNotEmpty()) {
                            put(
                                "phonemeCodepoints",
                                JsonArray(
                                    request.phonemeCodepoints.map { sentence ->
                                        JsonArray(sentence.map(::bridgeInt))
                                    },
                                ),
                            )
                        }
                        request.extraOptions.forEach { (key, value) ->
                            put(key, bridgeString(value))
                        }
                    },
                ).encodeOptions()
                transport.call(handle, "tts.synthesize", arguments)
            }

            while (true) {
                // Each pull takes its own lock so `close()` can still interleave
                // between chunks, and the emission happens outside every lock.
                val chunk = mutex.withLock {
                    if (state.isClosed) return@flow
                    pullChunk()
                } ?: return@flow
                emit(chunk)
                if (chunk.isLast) return@flow
            }
        }
    }

    private suspend fun pullChunk(): AudioChunk? {
        val reply = transport.pullAudio(handle, "tts.take-chunk")
        val objectValue = bridgeJson.parseToJsonElement(reply.json).jsonObject
        val progress = objectValue["progress"]?.jsonPrimitive?.float ?: 1.0f
        val isLast = objectValue["isLast"]?.jsonPrimitive?.booleanOrNull ?: true
        val chunk = reply.audio ?: return null
        if (chunk.samples.isEmpty()) return null
        return AudioChunk(
            samples = chunk.samples,
            sampleRate = chunk.sampleRate,
            progress = progress.coerceIn(0.0f, 1.0f),
            isLast = isLast,
        )
    }

    @Throws(Throwable::class)
    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            try {
                transport.destroy(handle)
            } finally {
                onClosed(this)
            }
        }
    }
}

internal class PortableKeywordSpotter(
    private val transport: PortableTransport,
    private val handle: BridgeHandle,
    private val onClosed: (PortableKeywordSpotter) -> Unit,
) : KeywordSpotter {
    private val state = ResourceState()
    private val mutex = Mutex()
    private val streams = linkedSetOf<PortableKeywordSpotterStream>()

    override suspend fun openStream(keywords: String): KeywordSpotterStream = mutex.withLock {
        state.requireOpen()
        val arguments = if (keywords.isBlank()) {
            "{}"
        } else {
            JsonObject(mapOf("keywords" to bridgeString(keywords))).encodeOptions()
        }
        val reply = transport.call(handle, "kws.create-stream", arguments)
        val sessionHandle = BridgeHandle(
            bridgeJson.parseToJsonElement(reply).jsonObject
                .getValue("handle").jsonPrimitive.content.toLong(),
        )
        PortableKeywordSpotterStream(
            transport = transport,
            handle = sessionHandle,
            onClosed = { streams.remove(it) },
        ).also { streams += it }
    }

    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            streams.toList().forEach { it.close() }
            streams.clear()
            try {
                transport.destroy(handle)
            } finally {
                onClosed(this)
            }
        }
    }
}

internal class PortableKeywordSpotterStream(
    private val transport: PortableTransport,
    private val handle: BridgeHandle,
    private val onClosed: (PortableKeywordSpotterStream) -> Unit,
) : KeywordSpotterStream {
    private val state = ResourceState()
    private val mutex = Mutex()

    override suspend fun push(audio: AudioData): KeywordSpotterResult = mutex.withLock {
        state.requireOpen()
        transport.pushAudio(handle, "kws-stream.push-audio", audio)
        parse(transport.call(handle, "kws-stream.decode"))
    }

    override suspend fun result(): KeywordSpotterResult = mutex.withLock {
        state.requireOpen()
        parse(transport.call(handle, "kws-stream.result"))
    }

    override suspend fun reset() = mutex.withLock {
        state.requireOpen()
        transport.call(handle, "kws-stream.reset")
        Unit
    }

    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            try {
                transport.destroy(handle)
            } finally {
                onClosed(this)
            }
        }
    }

    private fun parse(json: String): KeywordSpotterResult =
        bridgeJson.decodeFromString(KeywordSpotterResult.serializer(), json)
}

internal class PortablePunctuation(
    private val transport: PortableTransport,
    private val handle: BridgeHandle,
    private val onClosed: (PortablePunctuation) -> Unit,
) : Punctuation {
    private val state = ResourceState()
    private val mutex = Mutex()

    override suspend fun addPunctuation(text: String): PunctuationResult = mutex.withLock {
        state.requireOpen()
        val arguments = JsonObject(mapOf("text" to bridgeString(text))).encodeOptions()
        val json = transport.call(handle, "punctuation.add-punctuation", arguments)
        bridgeJson.decodeFromString(PunctuationResult.serializer(), json)
    }

    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            try {
                transport.destroy(handle)
            } finally {
                onClosed(this)
            }
        }
    }
}

internal class PortableSpeakerDiarization(
    private val transport: PortableTransport,
    private val handle: BridgeHandle,
    private val onClosed: (PortableSpeakerDiarization) -> Unit,
) : SpeakerDiarization {
    private val state = ResourceState()
    private val mutex = Mutex()

    override suspend fun process(audio: AudioData): DiarizationResult = mutex.withLock {
        state.requireOpen()
        transport.pushAudio(handle, "speaker-diarization.set-audio", audio)
        val json = transport.call(handle, "speaker-diarization.process")
        bridgeJson.decodeFromString(DiarizationResult.serializer(), json)
    }

    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            try {
                transport.destroy(handle)
            } finally {
                onClosed(this)
            }
        }
    }
}

internal class PortableSpeechEnhancer(
    private val transport: PortableTransport,
    private val handle: BridgeHandle,
    private val onClosed: (PortableSpeechEnhancer) -> Unit,
) : SpeechEnhancer {
    private val state = ResourceState()
    private val mutex = Mutex()

    override suspend fun denoise(audio: AudioData): AudioData = mutex.withLock {
        state.requireOpen()
        transport.pushAudio(handle, "speech-denoiser.set-audio", audio)
        transport.call(handle, "speech-denoiser.denoise")
        val reply = transport.pullAudio(handle, "speech-denoiser.take-audio")
        val chunk = reply.audio ?: return@withLock AudioData(
            samples = FloatArray(0),
            sampleRate = audio.sampleRate,
        )
        AudioData(samples = chunk.samples, sampleRate = chunk.sampleRate)
    }

    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            try {
                transport.destroy(handle)
            } finally {
                onClosed(this)
            }
        }
    }
}

private fun bridgeString(value: String) =
    kotlinx.serialization.json.JsonPrimitive(value)

private fun bridgeInt(value: Int) =
    kotlinx.serialization.json.JsonPrimitive(value)

private fun bridgeDouble(value: Double) =
    kotlinx.serialization.json.JsonPrimitive(value)
