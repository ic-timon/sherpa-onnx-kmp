// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

/**
 * Mono PCM audio normalized to the range [-1, 1].
 */
public data class AudioData(
    public val samples: FloatArray,
    public val sampleRate: Int,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
    }

    public val durationSeconds: Double
        get() = samples.size.toDouble() / sampleRate
}

public data class AudioChunk(
    public val samples: FloatArray,
    public val sampleRate: Int,
    public val progress: Float,
    public val isLast: Boolean,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
        require(progress in 0.0f..1.0f) { "progress must be in [0, 1], got $progress" }
    }
}

public data class SpeechSegment(
    public val startSample: Long,
    public val samples: FloatArray,
)
