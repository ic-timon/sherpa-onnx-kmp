// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AppleIntegrationTest {
    @Test
    fun transcribesPinnedModelWhenConfigured() = runTest {
        val modelDir = APPLE_TEST_MODEL_DIR
        if (modelDir.isBlank()) return@runTest

        val audio = AppleWavReader.read("$modelDir/test_wavs/0.wav")
        createSherpaRuntime(FileModelStore(modelDir)).useResource { runtime ->
            assertTrue(runtime.backendInfo.bridgeAbiVersion > 0)
            assertTrue(runtime.capabilities.features.contains(SherpaFeature.OFFLINE_ASR))
            val recognizer = runtime.createOfflineRecognizer(
                OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        model = OfflineModel.NemoCtc(ModelPath("model.int8.onnx")),
                        tokens = ModelPath("tokens.txt"),
                    ),
                ),
            )
            recognizer.useResource {
                val result = it.transcribe(audio)
                assertTrue(result.text.isNotBlank())
                assertTrue(result.tokens.isNotEmpty())
            }
        }
    }
}
