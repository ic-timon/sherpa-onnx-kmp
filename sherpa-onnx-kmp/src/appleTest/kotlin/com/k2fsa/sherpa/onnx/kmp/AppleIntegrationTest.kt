// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

@OptIn(ExperimentalForeignApi::class)
class AppleIntegrationTest {
    @Test
    fun transcribesPinnedModelWhenConfigured() = runTest {
        val modelDir = APPLE_TEST_MODEL_DIR
        if (modelDir.isBlank()) return@runTest

        val audio = readMono16BitWav("$modelDir/test_wavs/0.wav")
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

    private fun readMono16BitWav(path: String): AudioData {
        val data = requireNotNull(NSData.dataWithContentsOfFile(path)) {
            "Unable to read WAV file: $path"
        }
        val bytesPointer = requireNotNull(data.bytes) { "WAV data is empty: $path" }
        val bytes = bytesPointer.reinterpret<ByteVar>()
        val length = data.length.toInt()
        fun byteAt(index: Int): Int = bytes[index].toInt() and 0xff
        fun intAt(index: Int): Int =
            byteAt(index) or
                (byteAt(index + 1) shl 8) or
                (byteAt(index + 2) shl 16) or
                (byteAt(index + 3) shl 24)
        fun shortAt(index: Int): Int = byteAt(index) or (byteAt(index + 1) shl 8)
        fun asciiAt(index: Int): String =
            ByteArray(4) { bytes[index + it].toInt().toByte() }.decodeToString()

        require(length >= 12)
        require(asciiAt(0) == "RIFF")
        require(asciiAt(8) == "WAVE")

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var samples: FloatArray? = null
        var offset = 12
        while (offset + 8 <= length) {
            val id = asciiAt(offset)
            val chunkSize = intAt(offset + 4)
            val body = offset + 8
            require(body + chunkSize <= length) { "Truncated WAV chunk: $id" }
            when (id) {
                "fmt " -> {
                    require(chunkSize >= 16)
                    audioFormat = shortAt(body)
                    channels = shortAt(body + 2)
                    sampleRate = intAt(body + 4)
                    bitsPerSample = shortAt(body + 14)
                }

                "data" -> {
                    samples = FloatArray(chunkSize / 2) { index ->
                        val sample = shortAt(body + index * 2)
                        val signed = if (sample >= 0x8000) sample - 0x10000 else sample
                        signed / 32768.0f
                    }
                }
            }
            offset = body + chunkSize + (chunkSize and 1)
        }

        require(audioFormat == 1) { "Expected PCM WAV" }
        require(channels == 1) { "Expected mono WAV" }
        require(bitsPerSample == 16) { "Expected 16-bit WAV" }
        require(sampleRate > 0)
        return AudioData(
            samples = requireNotNull(samples) { "WAV has no data chunk" },
            sampleRate = sampleRate,
        )
    }
}
