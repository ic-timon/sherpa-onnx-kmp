// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

public sealed interface VadModel {
    public data class Silero(
        public val model: ModelPath,
    ) : VadModel

    public data class Ten(
        public val model: ModelPath,
    ) : VadModel
}

public data class VadConfig(
    public val model: VadModel,
    public val sampleRate: Int = 16_000,
    public val threshold: Float = 0.5f,
    public val minSilenceDurationSeconds: Float = 0.25f,
    public val minSpeechDurationSeconds: Float = 0.25f,
    public val windowSize: Int = 512,
    public val maxSpeechDurationSeconds: Float = 5.0f,
    public val options: RuntimeOptions = RuntimeOptions(),
    public val extraOptions: Map<String, String> = emptyMap(),
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(threshold in 0.0f..1.0f) { "threshold must be in [0, 1]" }
        require(windowSize > 0) { "windowSize must be positive" }
    }
}

public fun VadConfig.requiredModelFiles(): Set<ModelPath> =
    setOf(
        when (val model = model) {
            is VadModel.Silero -> model.model
            is VadModel.Ten -> model.model
        },
    )
