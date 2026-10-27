// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

public sealed interface OfflineModel {
    public data class Transducer(
        public val encoder: ModelPath,
        public val decoder: ModelPath,
        public val joiner: ModelPath,
    ) : OfflineModel

    public data class Paraformer(
        public val model: ModelPath,
    ) : OfflineModel

    public data class Whisper(
        public val encoder: ModelPath,
        public val decoder: ModelPath,
        public val language: String = "en",
        public val task: String = "transcribe",
        public val tailPaddings: Int = 1_000,
        public val enableTokenTimestamps: Boolean = false,
        public val enableSegmentTimestamps: Boolean = false,
    ) : OfflineModel

    public data class SenseVoice(
        public val model: ModelPath,
        public val language: String = "",
        public val useInverseTextNormalization: Boolean = true,
    ) : OfflineModel

    public data class ZipformerCtc(
        public val model: ModelPath,
    ) : OfflineModel
}

public data class OfflineModelConfig(
    public val model: OfflineModel,
    public val tokens: ModelPath,
    public val options: RuntimeOptions = RuntimeOptions(),
)

public data class OfflineRecognizerConfig(
    public val featureConfig: FeatureConfig = FeatureConfig(),
    public val modelConfig: OfflineModelConfig,
    public val decodingMethod: String = "greedy_search",
    public val maxActivePaths: Int = 4,
    public val hotwordsFile: ModelPath? = null,
    public val hotwordsScore: Float = 1.5f,
    public val extraOptions: Map<String, String> = emptyMap(),
) {
    init {
        require(decodingMethod.isNotBlank()) { "decodingMethod must not be blank" }
        require(maxActivePaths > 0) { "maxActivePaths must be positive" }
    }
}

public fun OfflineRecognizerConfig.requiredModelFiles(): Set<ModelPath> =
    buildSet {
        add(modelConfig.tokens)
        when (val model = modelConfig.model) {
            is OfflineModel.Transducer -> {
                add(model.encoder)
                add(model.decoder)
                add(model.joiner)
            }

            is OfflineModel.Paraformer -> add(model.model)
            is OfflineModel.Whisper -> {
                add(model.encoder)
                add(model.decoder)
            }

            is OfflineModel.SenseVoice -> add(model.model)
            is OfflineModel.ZipformerCtc -> add(model.model)
        }
        hotwordsFile?.let(::add)
    }
