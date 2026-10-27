// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

public sealed interface TtsModel {
    public data class Vits(
        public val model: ModelPath,
        public val tokens: ModelPath,
        public val lexicon: ModelPath? = null,
    ) : TtsModel

    public data class Matcha(
        public val acousticModel: ModelPath,
        public val vocoder: ModelPath,
        public val tokens: ModelPath,
        public val lexicon: ModelPath? = null,
    ) : TtsModel

    public data class Kokoro(
        public val model: ModelPath,
        public val voices: ModelPath,
        public val tokens: ModelPath,
        public val lexicon: ModelPath? = null,
    ) : TtsModel
}

public data class OfflineTtsConfig(
    public val model: TtsModel,
    public val options: RuntimeOptions = RuntimeOptions(),
    public val maxNumSentences: Int = 2,
    public val silenceScale: Float = 0.2f,
    public val extraOptions: Map<String, String> = emptyMap(),
) {
    init {
        require(maxNumSentences != 0) { "maxNumSentences must not be zero" }
    }
}

public fun OfflineTtsConfig.requiredModelFiles(): Set<ModelPath> =
    buildSet {
        when (val model = model) {
            is TtsModel.Vits -> {
                add(model.model)
                add(model.tokens)
                model.lexicon?.let(::add)
            }

            is TtsModel.Matcha -> {
                add(model.acousticModel)
                add(model.vocoder)
                add(model.tokens)
                model.lexicon?.let(::add)
            }

            is TtsModel.Kokoro -> {
                add(model.model)
                add(model.voices)
                add(model.tokens)
                model.lexicon?.let(::add)
            }
        }
    }

public data class TtsRequest(
    public val text: String,
    public val speakerId: Int = 0,
    public val speed: Float = 1.0f,
    public val extraOptions: Map<String, String> = emptyMap(),
) {
    init {
        require(text.isNotBlank()) { "text must not be blank" }
        require(speed > 0.0f) { "speed must be positive" }
    }
}
