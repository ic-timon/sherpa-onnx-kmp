// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun OfflineRecognizerConfig.toBridgeOptionsJson(
    preparedModel: PreparedModel,
): String = Json.encodeToString(JsonObject.serializer(), toBridgeOptions(preparedModel))

internal fun OfflineRecognizerConfig.toBridgeOptions(
    preparedModel: PreparedModel,
): JsonObject {
    val values = linkedMapOf<String, String>()

    values["sample-rate"] = featureConfig.sampleRate.toString()
    values["feat-dim"] = featureConfig.featureDim.toString()
    values["tokens"] = resolveModelPath(preparedModel, modelConfig.tokens)
    values["num-threads"] = modelConfig.options.numThreads.toString()
    values["provider"] = modelConfig.options.provider
    values["debug"] = modelConfig.options.debug.toString()
    values["decoding-method"] = decodingMethod
    values["max-active-paths"] = maxActivePaths.toString()
    values["hotwords-score"] = hotwordsScore.toString()

    when (val model = modelConfig.model) {
        is OfflineModel.Transducer -> {
            values["encoder"] = resolveModelPath(preparedModel, model.encoder)
            values["decoder"] = resolveModelPath(preparedModel, model.decoder)
            values["joiner"] = resolveModelPath(preparedModel, model.joiner)
            values["model-type"] = "transducer"
        }

        is OfflineModel.Paraformer -> {
            values["paraformer"] = resolveModelPath(preparedModel, model.model)
            values["model-type"] = "paraformer"
        }

        is OfflineModel.Whisper -> {
            values["whisper-encoder"] = resolveModelPath(preparedModel, model.encoder)
            values["whisper-decoder"] = resolveModelPath(preparedModel, model.decoder)
            values["whisper-language"] = model.language
            values["whisper-task"] = model.task
            values["whisper-tail-paddings"] = model.tailPaddings.toString()
            values["whisper-enable-token-timestamps"] =
                model.enableTokenTimestamps.toString()
            values["whisper-enable-segment-timestamps"] =
                model.enableSegmentTimestamps.toString()
            values["model-type"] = "whisper"
        }

        is OfflineModel.SenseVoice -> {
            values["sense-voice-model"] = resolveModelPath(preparedModel, model.model)
            values["sense-voice-language"] = model.language
            values["sense-voice-use-itn"] = model.useInverseTextNormalization.toString()
            values["model-type"] = "sense_voice"
        }

        is OfflineModel.ZipformerCtc -> {
            values["zipformer-ctc-model"] = resolveModelPath(preparedModel, model.model)
            values["model-type"] = "zipformer2_ctc"
        }
    }

    hotwordsFile?.let {
        values["hotwords-file"] = resolveModelPath(preparedModel, it)
    }

    values.putAll(modelConfig.options.extraOptions)
    values.putAll(extraOptions)
    return JsonObject(values.mapValues { JsonPrimitive(it.value) })
}

internal fun resolveModelPath(
    preparedModel: PreparedModel,
    path: ModelPath,
): String {
    val root = preparedModel.rootPath
    return when {
        root.isEmpty() -> path.value
        root.endsWith('/') || root.endsWith('\\') -> root + path.value
        else -> "$root/${path.value}"
    }
}
