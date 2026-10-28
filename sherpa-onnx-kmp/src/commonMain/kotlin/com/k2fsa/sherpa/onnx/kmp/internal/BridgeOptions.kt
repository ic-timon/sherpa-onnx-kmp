// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Option mappers for every bridge resource kind.
 *
 * Each public typed config is reduced to the flat string map the bridge's
 * `ParseOptions` understands, so a new upstream option only needs an
 * `extraOptions` entry rather than a KMP release. Configuration logic lives
 * here, once, for every platform.
 *
 * Keys must match the option names registered by the corresponding sherpa-onnx
 * config class. Two upstream quirks are handled deliberately:
 *
 * - Online endpoint rules are registered as `rule1-*`, `rule2-*`, and `rule3-*`
 *   and are only consulted when `enable-endpoint` is true.
 * - `OfflineRecognizerConfig` maps `model-type` to its own enum, so the value
 *   written here is the enum spelling, not the model class name.
 */
internal object BridgeOptions {
    /** Shared by every kind: thread count, provider, and debug logging. */
    private fun MutableMap<String, String>.applyRuntimeOptions(
        options: RuntimeOptions,
    ) {
        put("num-threads", options.numThreads.toString())
        put("provider", options.provider)
        put("debug", options.debug.toString())
        putAll(options.extraOptions)
    }

    fun onlineAsr(
        config: OnlineRecognizerConfig,
        preparedModel: PreparedModel,
    ): JsonObject {
        val values = linkedMapOf<String, String>()
        values["sample-rate"] = config.featureConfig.sampleRate.toString()
        values["feat-dim"] = config.featureConfig.featureDim.toString()
        values["tokens"] = resolveModelPath(preparedModel, config.modelConfig.tokens)
        values.applyRuntimeOptions(config.modelConfig.options)

        values["decoding-method"] = config.decodingMethod
        values["max-active-paths"] = config.maxActivePaths.toString()
        values["hotwords-score"] = config.hotwordsScore.toString()

        // Endpoint rules are separate keys upstream, and `enable-endpoint` is
        // what actually turns the rules on.
        val endpoint = config.endpointConfig
        values["enable-endpoint"] = endpoint.enabled.toString()
        values["rule1-min-trailing-silence"] =
            endpoint.rule1MinTrailingSilenceSeconds.toString()
        values["rule2-min-trailing-silence"] =
            endpoint.rule2MinTrailingSilenceSeconds.toString()
        values["rule3-min-utterance-length"] =
            endpoint.rule3MinUtteranceLengthSeconds.toString()

        when (val model = config.modelConfig.model) {
            is OnlineModel.Transducer -> {
                values["encoder"] = resolveModelPath(preparedModel, model.encoder)
                values["decoder"] = resolveModelPath(preparedModel, model.decoder)
                values["joiner"] = resolveModelPath(preparedModel, model.joiner)
            }

            is OnlineModel.Paraformer -> {
                values["paraformer-encoder"] =
                    resolveModelPath(preparedModel, model.encoder)
                values["paraformer-decoder"] =
                    resolveModelPath(preparedModel, model.decoder)
            }

            is OnlineModel.Zipformer2Ctc -> {
                values["zipformer2-ctc-model"] =
                    resolveModelPath(preparedModel, model.model)
            }

            is OnlineModel.NemoCtc -> {
                values["nemo-ctc-model"] = resolveModelPath(preparedModel, model.model)
            }
        }

        config.hotwordsFile?.let {
            values["hotwords-file"] = resolveModelPath(preparedModel, it)
        }

        values.putAll(config.extraOptions)
        return values.toJsonObject()
    }

    fun vad(config: VadConfig, preparedModel: PreparedModel): JsonObject {
        val values = linkedMapOf<String, String>()
        values["vad-sample-rate"] = config.sampleRate.toString()
        values["vad-num-threads"] = config.options.numThreads.toString()
        values["vad-provider"] = config.options.provider
        values["vad-debug"] = config.options.debug.toString()
        values.putAll(config.options.extraOptions)

        // The tuning keys belong to whichever VAD model was selected, and each
        // model registers them under its own prefix. Silero is upstream's
        // default, so its names are the fallback only when the model is Silero.
        val prefix = when (val model = config.model) {
            is VadModel.Silero -> {
                values["silero-vad-model"] = resolveModelPath(preparedModel, model.model)
                "silero-vad"
            }

            is VadModel.Ten -> {
                values["ten-vad-model"] = resolveModelPath(preparedModel, model.model)
                "ten-vad"
            }
        }
        values["$prefix-threshold"] = config.threshold.toString()
        values["$prefix-min-silence-duration"] =
            config.minSilenceDurationSeconds.toString()
        values["$prefix-min-speech-duration"] =
            config.minSpeechDurationSeconds.toString()
        values["$prefix-window-size"] = config.windowSize.toString()
        values["$prefix-max-speech-duration"] =
            config.maxSpeechDurationSeconds.toString()

        values.putAll(config.extraOptions)
        return values.toJsonObject()
    }

    fun tts(config: OfflineTtsConfig, preparedModel: PreparedModel): JsonObject {
        val values = linkedMapOf<String, String>()
        values.applyRuntimeOptions(config.options)
        values["tts-max-num-sentences"] = config.maxNumSentences.toString()
        values["tts-silence-scale"] = config.silenceScale.toString()

        when (val model = config.model) {
            is TtsModel.Vits -> {
                values["vits-model"] = resolveModelPath(preparedModel, model.model)
                values["vits-tokens"] = resolveModelPath(preparedModel, model.tokens)
                model.lexicon?.let {
                    values["vits-lexicon"] = resolveModelPath(preparedModel, it)
                }
            }

            is TtsModel.Matcha -> {
                values["matcha-acoustic-model"] =
                    resolveModelPath(preparedModel, model.acousticModel)
                values["matcha-vocoder"] =
                    resolveModelPath(preparedModel, model.vocoder)
                values["matcha-tokens"] = resolveModelPath(preparedModel, model.tokens)
                model.lexicon?.let {
                    values["matcha-lexicon"] = resolveModelPath(preparedModel, it)
                }
            }

            is TtsModel.Kokoro -> {
                values["kokoro-model"] = resolveModelPath(preparedModel, model.model)
                values["kokoro-voices"] = resolveModelPath(preparedModel, model.voices)
                values["kokoro-tokens"] = resolveModelPath(preparedModel, model.tokens)
                model.lexicon?.let {
                    values["kokoro-lexicon"] = resolveModelPath(preparedModel, it)
                }
            }
        }

        values.putAll(config.extraOptions)
        return values.toJsonObject()
    }

    fun keywordSpotter(
        config: KeywordSpotterConfig,
        preparedModel: PreparedModel,
    ): JsonObject {
        val values = linkedMapOf<String, String>()
        values["sample-rate"] = config.featureConfig.sampleRate.toString()
        values["feat-dim"] = config.featureConfig.featureDim.toString()
        values["tokens"] = resolveModelPath(preparedModel, config.modelConfig.tokens)
        values.applyRuntimeOptions(config.modelConfig.options)

        values["max-active-paths"] = config.maxActivePaths.toString()
        values["num-trailing-blanks"] = config.numTrailingBlanks.toString()
        values["keywords-score"] = config.keywordsScore.toString()
        values["keywords-threshold"] = config.keywordsThreshold.toString()
        values["keywords-file"] = resolveModelPath(preparedModel, config.keywordsFile)

        val model = config.modelConfig.model
        require(model is OnlineModel.Transducer) {
            "Keyword spotting requires a transducer model, got $model"
        }
        values["encoder"] = resolveModelPath(preparedModel, model.encoder)
        values["decoder"] = resolveModelPath(preparedModel, model.decoder)
        values["joiner"] = resolveModelPath(preparedModel, model.joiner)

        values.putAll(config.extraOptions)
        return values.toJsonObject()
    }

    fun punctuation(
        config: PunctuationConfig,
        preparedModel: PreparedModel,
    ): JsonObject {
        val values = linkedMapOf<String, String>()
        values["ct-transformer"] = resolveModelPath(preparedModel, config.model)
        values.applyRuntimeOptions(config.options)
        values.putAll(config.extraOptions)
        return values.toJsonObject()
    }

    fun speakerDiarization(
        config: SpeakerDiarizationConfig,
        preparedModel: PreparedModel,
    ): JsonObject {
        val values = linkedMapOf<String, String>()
        // Diarization registers its sub-configs through prefixed parsers, so
        // every model key carries the `segmentation.`, `embedding.`, or
        // `clustering.` prefix. Unprefixed keys would be rejected.
        values["segmentation.pyannote-model"] =
            resolveModelPath(preparedModel, config.segmentationModel)
        values["embedding.model"] = resolveModelPath(preparedModel, config.embeddingModel)

        val options = config.options
        for (prefix in listOf("segmentation.", "embedding.")) {
            values["${prefix}num-threads"] = options.numThreads.toString()
            values["${prefix}provider"] = options.provider
            values["${prefix}debug"] = options.debug.toString()
        }

        // `num-clusters <= 0` means "cluster by distance threshold", which is
        // how the typed config expresses the default.
        if (config.numClusters > 0) {
            values["clustering.num-clusters"] = config.numClusters.toString()
        }
        values["clustering.cluster-threshold"] = config.clusterThreshold.toString()
        values["min-duration-on"] = config.minDurationOnSeconds.toString()
        values["min-duration-off"] = config.minDurationOffSeconds.toString()

        values.putAll(options.extraOptions)
        values.putAll(config.extraOptions)
        return values.toJsonObject()
    }

    fun speechDenoiser(
        config: SpeechEnhancementConfig,
        preparedModel: PreparedModel,
    ): JsonObject {
        val values = linkedMapOf<String, String>()
        values.applyRuntimeOptions(config.options)
        values.putAll(config.options.extraOptions)

        when (val model = config.model) {
            is DenoiserModel.Gtcrn -> {
                values["speech-denoiser-gtcrn-model"] =
                    resolveModelPath(preparedModel, model.model)
            }

            is DenoiserModel.DpdfNet -> {
                values["speech-denoiser-dpdfnet-model"] =
                    resolveModelPath(preparedModel, model.model)
            }
        }

        values.putAll(config.extraOptions)
        return values.toJsonObject()
    }

    private fun Map<String, String>.toJsonObject(): JsonObject =
        JsonObject(mapValues { JsonPrimitive(it.value) })
}

/** Serializes a flat option object for `SherpaOnnxBridgeCreate`. */
internal fun JsonObject.encodeOptions(): String =
    Json.encodeToString(JsonObject.serializer(), this)

internal fun OnlineRecognizerConfig.toBridgeOptionsJson(
    preparedModel: PreparedModel,
): String = BridgeOptions.onlineAsr(this, preparedModel).encodeOptions()

internal fun VadConfig.toBridgeOptionsJson(preparedModel: PreparedModel): String =
    BridgeOptions.vad(this, preparedModel).encodeOptions()

internal fun OfflineTtsConfig.toBridgeOptionsJson(preparedModel: PreparedModel): String =
    BridgeOptions.tts(this, preparedModel).encodeOptions()

internal fun KeywordSpotterConfig.toBridgeOptionsJson(
    preparedModel: PreparedModel,
): String = BridgeOptions.keywordSpotter(this, preparedModel).encodeOptions()

internal fun PunctuationConfig.toBridgeOptionsJson(preparedModel: PreparedModel): String =
    BridgeOptions.punctuation(this, preparedModel).encodeOptions()

internal fun SpeakerDiarizationConfig.toBridgeOptionsJson(
    preparedModel: PreparedModel,
): String = BridgeOptions.speakerDiarization(this, preparedModel).encodeOptions()

internal fun SpeechEnhancementConfig.toBridgeOptionsJson(
    preparedModel: PreparedModel,
): String = BridgeOptions.speechDenoiser(this, preparedModel).encodeOptions()
