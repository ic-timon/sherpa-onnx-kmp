// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

/**
 * Denoising model families supported by sherpa-onnx.
 */
public sealed interface DenoiserModel {
    public data class Gtcrn(
        public val model: ModelPath,
    ) : DenoiserModel

    public data class DpdfNet(
        public val model: ModelPath,
    ) : DenoiserModel
}

/**
 * Configuration for offline speech enhancement (denoising).
 */
public data class SpeechEnhancementConfig(
    public val model: DenoiserModel,
    public val options: RuntimeOptions = RuntimeOptions(),
    public val extraOptions: Map<String, String> = emptyMap(),
)

public fun SpeechEnhancementConfig.requiredModelFiles(): Set<ModelPath> =
    setOf(
        when (val model = model) {
            is DenoiserModel.Gtcrn -> model.model
            is DenoiserModel.DpdfNet -> model.model
        },
    )
