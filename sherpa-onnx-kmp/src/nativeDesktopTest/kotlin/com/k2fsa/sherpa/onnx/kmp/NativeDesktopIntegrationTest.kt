// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.ftell
import kotlin.test.Test
import kotlin.test.assertTrue
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

    private fun readMono16BitWav(path: String): AudioData {
        val bytes = readFile("$path")
        require(bytes.size >= 12) { "WAV file is too short: $path" }
        fun byteAt(index: Int): Int = bytes[index].toInt() and 0xff
        fun intAt(index: Int): Int =
            byteAt(index) or
                (byteAt(index + 1) shl 8) or
                (byteAt(index + 2) shl 16) or
                (byteAt(index + 3) shl 24)
        fun shortAt(index: Int): Int = byteAt(index) or (byteAt(index + 1) shl 8)
        fun asciiAt(index: Int): String =
            ByteArray(4) { bytes[index + it] }.decodeToString()

        require(asciiAt(0) == "RIFF") { "Expected a RIFF file: $path" }
        require(asciiAt(8) == "WAVE") { "Expected a WAVE file: $path" }

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var samples: FloatArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = asciiAt(offset)
            val chunkSize = intAt(offset + 4)
            val body = offset + 8
            require(body + chunkSize <= bytes.size) { "Truncated WAV chunk: $id" }
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
        require(sampleRate > 0) { "Invalid sample rate" }
        return AudioData(
            samples = requireNotNull(samples) { "WAV has no data chunk" },
            sampleRate = sampleRate,
        )
    }

    private fun readFile(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("Unable to open file: $path")
        try {
            if (fseek(file, 0, SEEK_END) != 0) error("Unable to seek file: $path")
            val size = ftell(file)
            if (size <= 0) error("Unable to measure file: $path")
            if (fseek(file, 0, SEEK_SET) != 0) error("Unable to rewind file: $path")
            val bytes = ByteArray(size.toInt())
            val read = bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, size.toULong(), file).toLong()
            }
            if (read != size.toLong()) error("Short read for file: $path")
            return bytes
        } finally {
            fclose(file)
        }
    }
}
