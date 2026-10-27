// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class JvmIntegrationTest {
    @Test
    fun transcribesPinnedModelWhenConfigured() = runTest {
        val nativePath = System.getenv("SHERPA_ONNX_KMP_NATIVE_PATH") ?: return@runTest
        val modelDir = System.getenv("SHERPA_ONNX_KMP_TEST_MODEL_DIR") ?: return@runTest
        val model = File(modelDir, "model.int8.onnx")
        val tokens = File(modelDir, "tokens.txt")
        val wav = File(modelDir, "test_wavs/0.wav")
        if (!model.isFile || !tokens.isFile || !wav.isFile) return@runTest

        System.setProperty("sherpa.onnx.kmp.native.path", nativePath)
        val audio = readMonoSamples(wav)
        createSherpaRuntime(FileModelStore(modelDir)).useResource { runtime ->
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

    private fun readMonoSamples(file: File): AudioData {
        AudioSystem.getAudioInputStream(file).use { stream ->
            val format = stream.format
            val bytes = stream.readAllBytes()
            val frameSize = format.frameSize
            val sampleRate = format.sampleRate.toInt()
            require(format.channels == 1) { "Expected mono WAV" }
            require(format.sampleSizeInBits == 16) { "Expected 16-bit WAV" }
            require(frameSize == 2) { "Expected 16-bit PCM frames" }
            val samples = FloatArray(bytes.size / 2)
            for (i in samples.indices) {
                val lo = bytes[i * 2].toInt() and 0xff
                val hi = bytes[i * 2 + 1].toInt()
                val value = (hi shl 8) or lo
                samples[i] = value / 32768.0f
            }
            return AudioData(samples = samples, sampleRate = sampleRate)
        }
    }
}
