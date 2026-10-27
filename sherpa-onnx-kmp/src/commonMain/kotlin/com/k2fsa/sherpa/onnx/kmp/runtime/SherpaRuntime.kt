// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.coroutines.flow.Flow

public interface SherpaRuntime : SherpaResource {
    public val capabilities: SherpaCapabilities
    public val backendInfo: BackendInfo

    @Throws(Throwable::class)
    public suspend fun createOfflineRecognizer(
        config: OfflineRecognizerConfig,
    ): OfflineRecognizer

    @Throws(Throwable::class)
    public suspend fun createOnlineRecognizer(
        config: OnlineRecognizerConfig,
    ): OnlineRecognizer

    @Throws(Throwable::class)
    public suspend fun createVoiceActivityDetector(
        config: VadConfig,
    ): VoiceActivityDetector

    @Throws(Throwable::class)
    public suspend fun createOfflineTts(
        config: OfflineTtsConfig,
    ): OfflineTts
}

public interface OfflineRecognizer : SherpaResource {
    @Throws(Throwable::class)
    public suspend fun transcribe(audio: AudioData): RecognitionResult
    @Throws(Throwable::class)
    public suspend fun transcribeBatch(audio: List<AudioData>): List<RecognitionResult>
}

public interface OnlineRecognizer : SherpaResource {
    @Throws(Throwable::class)
    public suspend fun openStream(hotwords: String = ""): OnlineSession
}

public interface OnlineSession : SherpaResource {
    @Throws(Throwable::class)
    public suspend fun push(audio: AudioData): OnlineUpdate
    @Throws(Throwable::class)
    public suspend fun finish(): OnlineUpdate
    @Throws(Throwable::class)
    public suspend fun reset()
}

public interface VoiceActivityDetector : SherpaResource {
    @Throws(Throwable::class)
    public suspend fun acceptWaveform(audio: AudioData): List<SpeechSegment>
    @Throws(Throwable::class)
    public suspend fun flush(): List<SpeechSegment>
    @Throws(Throwable::class)
    public suspend fun reset()
}

public interface OfflineTts : SherpaResource {
    public val sampleRate: Int
    public val numSpeakers: Int

    public fun generate(request: TtsRequest): Flow<AudioChunk>
}
