// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

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

public data class SherpaCapabilities(
    public val features: Set<SherpaFeature>,
    public val providers: Set<String>,
    public val supportsStreaming: Boolean,
    public val supportsCancellation: Boolean,
)

public data class BackendInfo(
    public val bridgeAbiVersion: Int,
    public val coreVersion: String,
    public val onnxRuntimeVersion: String,
)
