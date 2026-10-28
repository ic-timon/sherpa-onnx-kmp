// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that every typed config maps onto the option names upstream actually
 * registers. A misspelled key is rejected by `TrySetOption` at creation time, so
 * these assertions are what keep the mapper honest without a model.
 */
class FeatureBridgeOptionsTest {
    private fun options(json: String) =
        Json.parseToJsonElement(json).jsonObject

    @Test
    fun mapsOnlineRecognizerEndpointRules() {
        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                model = OnlineModel.Transducer(
                    encoder = ModelPath("encoder.onnx"),
                    decoder = ModelPath("decoder.onnx"),
                    joiner = ModelPath("joiner.onnx"),
                ),
                tokens = ModelPath("tokens.txt"),
            ),
            endpointConfig = EndpointConfig(
                enabled = true,
                rule1MinTrailingSilenceSeconds = 2.5f,
                rule2MinTrailingSilenceSeconds = 1.5f,
                rule3MinUtteranceLengthSeconds = 20.0f,
            ),
        )

        val json = options(config.toBridgeOptionsJson(PreparedModel("/m")))

        assertEquals("true", json.getValue("enable-endpoint").jsonPrimitive.content)
        // Float-to-string formatting differs between Kotlin/JVM and Kotlin/JS
        // (JS prints `20` for 20.0f), so the numeric values are compared as
        // numbers rather than as text.
        assertEquals(
            2.5f,
            json.getValue("rule1-min-trailing-silence").jsonPrimitive.content.toFloat(),
        )
        assertEquals(
            1.5f,
            json.getValue("rule2-min-trailing-silence").jsonPrimitive.content.toFloat(),
        )
        assertEquals(
            20.0f,
            json.getValue("rule3-min-utterance-length").jsonPrimitive.content.toFloat(),
        )
        assertEquals("/m/encoder.onnx", json.getValue("encoder").jsonPrimitive.content)
        assertEquals("/m/joiner.onnx", json.getValue("joiner").jsonPrimitive.content)
    }

    @Test
    fun mapsOnlineZipformer2CtcModel() {
        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                model = OnlineModel.Zipformer2Ctc(ModelPath("model.onnx")),
                tokens = ModelPath("tokens.txt"),
            ),
        )

        val json = options(config.toBridgeOptionsJson(PreparedModel("")))

        assertEquals(
            "model.onnx",
            json.getValue("zipformer2-ctc-model").jsonPrimitive.content,
        )
    }

    @Test
    fun mapsSileroVadTuningToSileroPrefixedKeys() {
        val config = VadConfig(
            model = VadModel.Silero(ModelPath("silero.onnx")),
            threshold = 0.4f,
            windowSize = 512,
        )

        val json = options(config.toBridgeOptionsJson(PreparedModel("/m")))

        assertEquals("/m/silero.onnx", json.getValue("silero-vad-model").jsonPrimitive.content)
        assertEquals("0.4", json.getValue("silero-vad-threshold").jsonPrimitive.content)
        assertEquals("512", json.getValue("silero-vad-window-size").jsonPrimitive.content)
        assertFalse("ten-vad-model" in json)
    }

    @Test
    fun mapsTenVadTuningToTenPrefixedKeys() {
        val config = VadConfig(
            model = VadModel.Ten(ModelPath("ten.onnx")),
            threshold = 0.6f,
            windowSize = 256,
        )

        val json = options(config.toBridgeOptionsJson(PreparedModel("/m")))

        assertEquals("/m/ten.onnx", json.getValue("ten-vad-model").jsonPrimitive.content)
        assertEquals("0.6", json.getValue("ten-vad-threshold").jsonPrimitive.content)
        assertEquals("256", json.getValue("ten-vad-window-size").jsonPrimitive.content)
        assertFalse("silero-vad-model" in json)
    }

    @Test
    fun mapsTtsModelFiles() {
        val config = OfflineTtsConfig(
            model = TtsModel.Kokoro(
                model = ModelPath("model.onnx"),
                voices = ModelPath("voices.bin"),
                tokens = ModelPath("tokens.txt"),
            ),
            maxNumSentences = 3,
            silenceScale = 0.1f,
        )

        val json = options(config.toBridgeOptionsJson(PreparedModel("/m")))

        assertEquals("/m/model.onnx", json.getValue("kokoro-model").jsonPrimitive.content)
        assertEquals("/m/voices.bin", json.getValue("kokoro-voices").jsonPrimitive.content)
        assertEquals("3", json.getValue("tts-max-num-sentences").jsonPrimitive.content)
        assertEquals("0.1", json.getValue("tts-silence-scale").jsonPrimitive.content)
    }

    @Test
    fun mapsKeywordSpotterRequiredFiles() {
        val config = KeywordSpotterConfig(
            modelConfig = OnlineModelConfig(
                model = OnlineModel.Transducer(
                    encoder = ModelPath("encoder.onnx"),
                    decoder = ModelPath("decoder.onnx"),
                    joiner = ModelPath("joiner.onnx"),
                ),
                tokens = ModelPath("tokens.txt"),
            ),
            keywordsFile = ModelPath("keywords.txt"),
        )

        val json = options(config.toBridgeOptionsJson(PreparedModel("/m")))

        assertEquals("/m/keywords.txt", json.getValue("keywords-file").jsonPrimitive.content)
        assertEquals(
            setOf(
                ModelPath("tokens.txt"),
                ModelPath("keywords.txt"),
                ModelPath("encoder.onnx"),
                ModelPath("decoder.onnx"),
                ModelPath("joiner.onnx"),
            ),
            config.requiredModelFiles(),
        )
    }

    @Test
    fun keywordSpotterRejectsNonTransducerModel() {
        val config = KeywordSpotterConfig(
            modelConfig = OnlineModelConfig(
                model = OnlineModel.Zipformer2Ctc(ModelPath("model.onnx")),
                tokens = ModelPath("tokens.txt"),
            ),
            keywordsFile = ModelPath("keywords.txt"),
        )

        assertFailsWith<IllegalArgumentException> { config.requiredModelFiles() }
    }

    @Test
    fun mapsPunctuationModel() {
        val config = PunctuationConfig(
            model = ModelPath("punctuation.onnx"),
            options = RuntimeOptions(numThreads = 2, provider = "cpu"),
        )

        val json = options(config.toBridgeOptionsJson(PreparedModel("/m")))

        assertEquals(
            "/m/punctuation.onnx",
            json.getValue("ct-transformer").jsonPrimitive.content,
        )
        assertEquals("2", json.getValue("num-threads").jsonPrimitive.content)
    }

    @Test
    fun mapsDiarizationWithPrefixedKeys() {
        val config = SpeakerDiarizationConfig(
            segmentationModel = ModelPath("pyannote.onnx"),
            embeddingModel = ModelPath("embedding.onnx"),
            options = RuntimeOptions(numThreads = 3),
            numClusters = 4,
            minDurationOnSeconds = 0.2f,
        )

        val json = options(config.toBridgeOptionsJson(PreparedModel("/m")))

        assertEquals(
            "/m/pyannote.onnx",
            json.getValue("segmentation.pyannote-model").jsonPrimitive.content,
        )
        assertEquals(
            "/m/embedding.onnx",
            json.getValue("embedding.model").jsonPrimitive.content,
        )
        assertEquals(
            "4",
            json.getValue("clustering.num-clusters").jsonPrimitive.content,
        )
        assertEquals("3", json.getValue("segmentation.num-threads").jsonPrimitive.content)
        assertEquals("3", json.getValue("embedding.num-threads").jsonPrimitive.content)
        assertEquals("0.2", json.getValue("min-duration-on").jsonPrimitive.content)
    }

    @Test
    fun diarizationOmitsClusterCountWhenUsingThreshold() {
        val config = SpeakerDiarizationConfig(
            segmentationModel = ModelPath("pyannote.onnx"),
            embeddingModel = ModelPath("embedding.onnx"),
            clusterThreshold = 0.9f,
        )

        val json = options(config.toBridgeOptionsJson(PreparedModel("/m")))

        assertFalse("clustering.num-clusters" in json)
        assertEquals(
            "0.9",
            json.getValue("clustering.cluster-threshold").jsonPrimitive.content,
        )
    }

    @Test
    fun mapsDenoiserModelVariant() {
        val gtcrn = SpeechEnhancementConfig(
            model = DenoiserModel.Gtcrn(ModelPath("gtcrn.onnx")),
        )
        val dpdfnet = SpeechEnhancementConfig(
            model = DenoiserModel.DpdfNet(ModelPath("dpdfnet.onnx")),
        )

        val gtcrnJson = options(gtcrn.toBridgeOptionsJson(PreparedModel("/m")))
        val dpdfnetJson = options(dpdfnet.toBridgeOptionsJson(PreparedModel("/m")))

        assertEquals(
            "/m/gtcrn.onnx",
            gtcrnJson.getValue("speech-denoiser-gtcrn-model").jsonPrimitive.content,
        )
        assertEquals(
            "/m/dpdfnet.onnx",
            dpdfnetJson.getValue("speech-denoiser-dpdfnet-model").jsonPrimitive.content,
        )
    }

    @Test
    fun diarizationEmbeddingModelKeyIsNotTheGenericModelKey() {
        // `embedding.model` is prefixed; a bare `model` key would be accepted by
        // nothing and silently leave the embedding model empty.
        val config = SpeakerDiarizationConfig(
            segmentationModel = ModelPath("pyannote.onnx"),
            embeddingModel = ModelPath("embedding.onnx"),
        )

        val json = options(config.toBridgeOptionsJson(PreparedModel("/m")))

        assertTrue("embedding.model" in json)
        assertFalse("model" in json)
    }
}
