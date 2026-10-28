// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

/**
 * Configuration for streaming keyword spotting.
 *
 * Keyword spotting runs on the same streaming transducer models as online ASR.
 * The keywords file contains one phrase per line with the phrase's tokens
 * separated by spaces; `extraOptions` can supply `keywords-buf` instead when the
 * caller keeps the phrases in memory.
 */
public data class KeywordSpotterConfig(
    public val featureConfig: FeatureConfig = FeatureConfig(),
    public val modelConfig: OnlineModelConfig,
    public val keywordsFile: ModelPath,
    public val maxActivePaths: Int = 4,
    public val numTrailingBlanks: Int = 1,
    public val keywordsScore: Float = 1.0f,
    public val keywordsThreshold: Float = 0.25f,
    public val extraOptions: Map<String, String> = emptyMap(),
) {
    init {
        require(maxActivePaths > 0) { "maxActivePaths must be positive" }
        require(numTrailingBlanks >= 0) { "numTrailingBlanks must not be negative" }
        require(keywordsThreshold in 0.0f..1.0f) {
            "keywordsThreshold must be in [0, 1], got $keywordsThreshold"
        }
    }
}

public fun KeywordSpotterConfig.requiredModelFiles(): Set<ModelPath> =
    buildSet {
        add(modelConfig.tokens)
        add(keywordsFile)
        // Keyword spotting only supports transducer models, so the model
        // hierarchy is narrowed here rather than repeating the check.
        val model = requireNotNull(modelConfig.model as? OnlineModel.Transducer) {
            "Keyword spotting requires a transducer model, got ${modelConfig.model}"
        }
        add(model.encoder)
        add(model.decoder)
        add(model.joiner)
    }
