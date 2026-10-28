// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest

/**
 * Real-model integration test shared by Linux x64/arm64 and Windows x64.
 *
 * The model directory is supplied through `SHERPA_ONNX_KMP_TEST_MODEL_DIR` and
 * baked into a generated constant, the same mechanism the Apple integration
 * test uses. When the variable is absent the test is a no-op so the contract
 * suite still runs on hosts without the pinned model.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeDesktopIntegrationTest {
    @Test
    fun transcribesPinnedModelWhenConfigured() = runTest {
        val modelDir = NATIVE_DESKTOP_TEST_MODEL_DIR
        if (modelDir.isBlank()) return@runTest

        val audio = PosixWavReader.read("$modelDir/test_wavs/0.wav")
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

    @Test
    fun rejectsMissingModelFiles() = runTest {
        createSherpaRuntime(FileModelStore("/nonexistent-model-root")).useResource {
            val failure = runCatching {
                it.createOfflineRecognizer(
                    OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            model = OfflineModel.NemoCtc(ModelPath("model.int8.onnx")),
                            tokens = ModelPath("tokens.txt"),
                        ),
                    ),
                )
            }
            assertTrue(failure.isFailure)
        }
    }
}
