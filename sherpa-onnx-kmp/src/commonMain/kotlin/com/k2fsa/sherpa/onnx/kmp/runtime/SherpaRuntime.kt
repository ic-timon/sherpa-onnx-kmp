// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.coroutines.flow.Flow

public interface SherpaRuntime : SherpaResource {
    public val capabilities: SherpaCapabilities
    public val backendInfo: BackendInfo

    public suspend fun createOfflineRecognizer(
        config: OfflineRecognizerConfig,
    ): OfflineRecognizer

    public suspend fun createOnlineRecognizer(
        config: OnlineRecognizerConfig,
    ): OnlineRecognizer

    public suspend fun createVoiceActivityDetector(
        config: VadConfig,
    ): VoiceActivityDetector

    public suspend fun createOfflineTts(
        config: OfflineTtsConfig,
    ): OfflineTts
}

public interface OfflineRecognizer : SherpaResource {
    public suspend fun transcribe(audio: AudioData): RecognitionResult
    public suspend fun transcribeBatch(audio: List<AudioData>): List<RecognitionResult>
}

public interface OnlineRecognizer : SherpaResource {
    public suspend fun openStream(hotwords: String = ""): OnlineSession
}

public interface OnlineSession : SherpaResource {
    public suspend fun push(audio: AudioData): OnlineUpdate
    public suspend fun finish(): OnlineUpdate
    public suspend fun reset()
}

public interface VoiceActivityDetector : SherpaResource {
    public suspend fun acceptWaveform(audio: AudioData): List<SpeechSegment>
    public suspend fun flush(): List<SpeechSegment>
    public suspend fun reset()
}

public interface OfflineTts : SherpaResource {
    public val sampleRate: Int
    public val numSpeakers: Int

    public fun generate(request: TtsRequest): Flow<AudioChunk>
}
