// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

public sealed interface OnlineModel {
    public data class Transducer(
        public val encoder: ModelPath,
        public val decoder: ModelPath,
        public val joiner: ModelPath,
    ) : OnlineModel

    public data class Paraformer(
        public val encoder: ModelPath,
        public val decoder: ModelPath,
    ) : OnlineModel

    public data class Zipformer2Ctc(
        public val model: ModelPath,
    ) : OnlineModel

    public data class NemoCtc(
        public val model: ModelPath,
    ) : OnlineModel
}

public data class OnlineModelConfig(
    public val model: OnlineModel,
    public val tokens: ModelPath,
    public val options: RuntimeOptions = RuntimeOptions(),
)

public data class EndpointConfig(
    public val enabled: Boolean = true,
    public val rule1MinTrailingSilenceSeconds: Float = 2.4f,
    public val rule2MinTrailingSilenceSeconds: Float = 1.2f,
    public val rule3MinUtteranceLengthSeconds: Float = 30.0f,
)

public data class OnlineRecognizerConfig(
    public val featureConfig: FeatureConfig = FeatureConfig(),
    public val modelConfig: OnlineModelConfig,
    public val decodingMethod: String = "greedy_search",
    public val maxActivePaths: Int = 4,
    public val endpointConfig: EndpointConfig = EndpointConfig(),
    public val hotwordsFile: ModelPath? = null,
    public val hotwordsScore: Float = 1.5f,
    public val extraOptions: Map<String, String> = emptyMap(),
) {
    init {
        require(decodingMethod.isNotBlank()) { "decodingMethod must not be blank" }
        require(maxActivePaths > 0) { "maxActivePaths must be positive" }
    }
}

public fun OnlineRecognizerConfig.requiredModelFiles(): Set<ModelPath> =
    buildSet {
        add(modelConfig.tokens)
        when (val model = modelConfig.model) {
            is OnlineModel.Transducer -> {
                add(model.encoder)
                add(model.decoder)
                add(model.joiner)
            }

            is OnlineModel.Paraformer -> {
                add(model.encoder)
                add(model.decoder)
            }

            is OnlineModel.Zipformer2Ctc -> add(model.model)
            is OnlineModel.NemoCtc -> add(model.model)
        }
        hotwordsFile?.let(::add)
    }
