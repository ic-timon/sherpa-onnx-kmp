package com.k2fsa.sherpa.onnx.kmp.sample

import com.k2fsa.sherpa.onnx.kmp.AudioData
import com.k2fsa.sherpa.onnx.kmp.FileModelStore
import com.k2fsa.sherpa.onnx.kmp.ModelPath
import com.k2fsa.sherpa.onnx.kmp.OfflineModel
import com.k2fsa.sherpa.onnx.kmp.OfflineModelConfig
import com.k2fsa.sherpa.onnx.kmp.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.kmp.createSherpaRuntime
import com.k2fsa.sherpa.onnx.kmp.useResource
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("Usage: jvm-cli MODEL_DIR WAV_FILE")
        System.err.println("Set SHERPA_ONNX_KMP_NATIVE_PATH to the directory containing the JNI libraries.")
        kotlin.system.exitProcess(2)
    }

    val modelDir = File(args[0]).absoluteFile
    val audio = readMono16BitWav(File(args[1]))

    runBlocking {
        createSherpaRuntime(FileModelStore(modelDir.path)).useResource { runtime ->
            println("bridge ABI: ${runtime.backendInfo.bridgeAbiVersion}")
            println("core version: ${runtime.backendInfo.coreVersion}")
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
                println(result.text)
            }
        }
    }
}

private fun readMono16BitWav(file: File): AudioData {
    AudioSystem.getAudioInputStream(file).use { stream ->
        val format = stream.format
        require(format.channels == 1)
        require(format.sampleSizeInBits == 16)
        val bytes = stream.readAllBytes()
        val samples = FloatArray(bytes.size / 2) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            ((high shl 8) or low) / 32768.0f
        }
        return AudioData(samples, format.sampleRate.toInt())
    }
}
