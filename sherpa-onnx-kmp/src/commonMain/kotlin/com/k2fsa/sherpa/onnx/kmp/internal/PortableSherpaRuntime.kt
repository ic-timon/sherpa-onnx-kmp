// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val bridgeJson = Json {
    ignoreUnknownKeys = true
}

internal suspend fun createPortableSherpaRuntime(
    transport: PortableTransport,
    modelStore: ModelStore,
): SherpaRuntime = try {
    val info = bridgeJson.parseToJsonElement(transport.infoJson()).jsonObject
    val resources = info["resources"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.toSet()
        ?: emptySet()
    val operations = info["operations"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.toSet()
        ?: emptySet()

    val features = buildSet {
        if ("offline-asr" in resources) add(SherpaFeature.OFFLINE_ASR)
    }
    val capabilities = SherpaCapabilities(
        features = features,
        providers = setOf("cpu"),
        supportsStreaming = "online-asr" in resources,
        supportsCancellation = false,
    )
    val backendInfo = BackendInfo(
        bridgeAbiVersion = info["abiVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        coreVersion = info["coreVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        onnxRuntimeVersion = info["onnxruntimeVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
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
        val recognizer = PortableOfflineRecognizer(
            transport = transport,
            handle = handle,
            onClosed = { children.remove(it) },
        )
        children += recognizer
        recognizer
    }

    @Throws(Throwable::class)
    override suspend fun createOnlineRecognizer(
        config: OnlineRecognizerConfig,
    ): OnlineRecognizer = unsupported(SherpaFeature.ONLINE_ASR)

    @Throws(Throwable::class)
    override suspend fun createVoiceActivityDetector(
        config: VadConfig,
    ): VoiceActivityDetector = unsupported(SherpaFeature.VAD)

    @Throws(Throwable::class)
    override suspend fun createOfflineTts(
        config: OfflineTtsConfig,
    ): OfflineTts = unsupported(SherpaFeature.TTS)

    @Throws(Throwable::class)
    override suspend fun close() = mutex.withLock {
        if (state.markClosed()) {
            children.toList().forEach { it.close() }
            children.clear()
            transport.close()
        }
    }

    private fun requireFeature(feature: SherpaFeature) {
        if (feature !in capabilities.features) {
            throw SherpaUnsupportedException("Feature is not supported by this runtime: $feature")
        }
    }

    private fun <T> unsupported(feature: SherpaFeature): T {
        throw SherpaUnsupportedException("Feature is not supported by this runtime: $feature")
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
