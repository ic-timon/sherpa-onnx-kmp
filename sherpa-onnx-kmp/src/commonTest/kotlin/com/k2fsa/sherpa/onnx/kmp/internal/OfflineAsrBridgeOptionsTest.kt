// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineAsrBridgeOptionsTest {
    @Test
    fun mapsParaformerConfigToFlatBridgeOptions() {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                model = OfflineModel.Paraformer(ModelPath("model.int8.onnx")),
                tokens = ModelPath("tokens.txt"),
                options = RuntimeOptions(
                    numThreads = 2,
                    provider = "cpu",
                    debug = false,
                ),
            ),
            decodingMethod = "greedy_search",
            extraOptions = mapOf("custom-option" to "value"),
        )

        val json = Json.parseToJsonElement(
            config.toBridgeOptionsJson(PreparedModel("/models/model-a")),
        ).jsonObject

        assertEquals("/models/model-a/model.int8.onnx", json.getValue("paraformer").jsonPrimitive.content)
        assertEquals("/models/model-a/tokens.txt", json.getValue("tokens").jsonPrimitive.content)
        assertEquals("2", json.getValue("num-threads").jsonPrimitive.content)
        assertEquals("paraformer", json.getValue("model-type").jsonPrimitive.content)
        assertEquals("value", json.getValue("custom-option").jsonPrimitive.content)
    }

    @Test
    fun mapsWhisperOptions() {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                model = OfflineModel.Whisper(
                    encoder = ModelPath("encoder.onnx"),
                    decoder = ModelPath("decoder.onnx"),
                    language = "zh",
                    task = "transcribe",
                    tailPaddings = 50,
                    enableTokenTimestamps = true,
                    enableSegmentTimestamps = false,
                ),
                tokens = ModelPath("tokens.txt"),
            ),
        )

        val json = Json.parseToJsonElement(
            config.toBridgeOptionsJson(PreparedModel("/m")),
        ).jsonObject

        assertEquals("/m/encoder.onnx", json.getValue("whisper-encoder").jsonPrimitive.content)
        assertEquals("zh", json.getValue("whisper-language").jsonPrimitive.content)
        assertEquals("50", json.getValue("whisper-tail-paddings").jsonPrimitive.content)
        assertEquals("true", json.getValue("whisper-enable-token-timestamps").jsonPrimitive.content)
    }

    @Test
    fun extraOptionsOverrideTypedValues() {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                model = OfflineModel.ZipformerCtc(ModelPath("model.onnx")),
                tokens = ModelPath("tokens.txt"),
                options = RuntimeOptions(extraOptions = mapOf("num-threads" to "4")),
            ),
            extraOptions = mapOf("num-threads" to "8"),
        )

        val json = Json.parseToJsonElement(
            config.toBridgeOptionsJson(PreparedModel("")),
        ).jsonObject

        assertEquals("8", json.getValue("num-threads").jsonPrimitive.content)
        assertEquals("model.onnx", json.getValue("zipformer-ctc-model").jsonPrimitive.content)
    }
}
