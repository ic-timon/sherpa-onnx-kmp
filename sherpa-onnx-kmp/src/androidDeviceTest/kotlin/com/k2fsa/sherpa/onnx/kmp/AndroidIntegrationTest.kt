// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class AndroidIntegrationTest {
    @Test
    fun transcribesPinnedModelOnDevice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val assetRoot = "models/nemo-ctc"
        val audio = readWav(context.assets.open("$assetRoot/test_wavs/0.wav"))

        createSherpaRuntime(AssetModelStore(context, assetRoot)).useResource { runtime ->
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

    private fun readWav(stream: InputStream): AudioData {
        DataInputStream(stream.buffered()).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            require(String(header, 0, 4) == "RIFF")
            require(String(header, 8, 4) == "WAVE")

            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var audioFormat = 0
            var samples = FloatArray(0)

            while (true) {
                val chunkHeader = ByteArray(8)
                val read = input.read(chunkHeader)
                if (read < 0) break
                require(read == 8)
                val chunkId = String(chunkHeader, 0, 4)
                val chunkSize = littleEndianInt(chunkHeader, 4)
                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize)
                        input.readFully(fmt)
                        audioFormat = littleEndianShort(fmt, 0)
                        channels = littleEndianShort(fmt, 2)
                        sampleRate = littleEndianInt(fmt, 4)
                        bitsPerSample = littleEndianShort(fmt, 14)
                    }

                    "data" -> {
                        val data = ByteArray(chunkSize)
                        input.readFully(data)
                        samples = FloatArray(chunkSize / 2) { index ->
                            val low = data[index * 2].toInt() and 0xff
                            val high = data[index * 2 + 1].toInt()
                            ((high shl 8) or low) / 32768.0f
                        }
                    }

                    else -> input.skipNBytes(chunkSize.toLong())
                }
                if (chunkSize % 2 != 0) input.skipNBytes(1)
            }

            require(audioFormat == 1) { "Expected PCM WAV" }
            require(channels == 1) { "Expected mono WAV" }
            require(bitsPerSample == 16) { "Expected 16-bit WAV" }
            require(sampleRate > 0)
            require(samples.isNotEmpty())
            return AudioData(samples = samples, sampleRate = sampleRate)
        }
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
}
