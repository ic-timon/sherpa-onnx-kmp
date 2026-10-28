// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp.internal

import com.k2fsa.sherpa.onnx.kmp.AudioChunk
import com.k2fsa.sherpa.onnx.kmp.AudioData
import com.k2fsa.sherpa.onnx.kmp.BridgeHandle
import com.k2fsa.sherpa.onnx.kmp.BridgeReply
import com.k2fsa.sherpa.onnx.kmp.PortableTransport
import com.k2fsa.sherpa.onnx.kmp.SherpaOnnxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object JniPortableTransport : PortableTransport {
    init {
        NativeLoader.load()
    }

    override suspend fun infoJson(): String = bridgeCall {
        nativeInfoJson()
    }

    override suspend fun create(kind: String, optionsJson: String): BridgeHandle =
        bridgeCall {
            BridgeHandle(nativeCreate(kind, optionsJson))
        }

    override suspend fun call(
        handle: BridgeHandle,
        operation: String,
        argumentsJson: String,
    ): String = bridgeCall {
        nativeCall(handle.value, operation, argumentsJson)
    }

    override suspend fun pushAudio(
        handle: BridgeHandle,
        operation: String,
        audio: AudioData,
    ): String = bridgeCall {
        nativePushAudio(
            handle.value,
            operation,
            audio.sampleRate,
            audio.samples,
        )
    }

    override suspend fun pullAudio(
        handle: BridgeHandle,
        operation: String,
        argumentsJson: String,
    ): BridgeReply = bridgeCall {
        val raw = nativePullAudio(handle.value, operation, argumentsJson)
            ?: throw SherpaOnnxException("Native bridge returned no audio reply")
        // The JNI shim returns [sampleRate: Int, samples: FloatArray, json: String].
        val sampleRate = raw[0] as Int
        val samples = raw[1] as FloatArray
        val json = raw[2] as String
        val chunk = if (samples.isEmpty()) {
            null
        } else {
            // `progress` and `isLast` are payload fields the engine reads from
            // `json`, so the transport fills only the audio and its rate.
            AudioChunk(
                samples = samples,
                sampleRate = sampleRate,
                progress = 1.0f,
                isLast = true,
            )
        }
        BridgeReply(json = json.ifEmpty { "{}" }, audio = chunk)
    }

    override suspend fun destroy(handle: BridgeHandle) {
        withContext(Dispatchers.Default) {
            nativeDestroy(handle.value)
        }
    }

    override suspend fun close() = Unit

    private suspend fun <T> bridgeCall(block: () -> T): T =
        withContext(Dispatchers.Default) {
            try {
                block()
            } catch (e: IllegalStateException) {
                throw SherpaOnnxException(e.message ?: "Native bridge call failed", e)
            }
        }

    private external fun nativeInfoJson(): String
    private external fun nativeCreate(kind: String, optionsJson: String): Long
    private external fun nativeCall(handle: Long, operation: String, argumentsJson: String): String
    private external fun nativePushAudio(
        handle: Long,
        operation: String,
        sampleRate: Int,
        samples: FloatArray,
    ): String
    private external fun nativePullAudio(
        handle: Long,
        operation: String,
        argumentsJson: String,
    ): Array<Any?>?
    private external fun nativeDestroy(handle: Long)
}

private object NativeLoader {
    private const val LIBRARY_NAME = "sherpa-onnx-kmp-jni"

    private const val BRIDGE_NAME = "sherpa-onnx-portable-bridge"
    private const val RESOURCE_ROOT = "sherpa-onnx/native"

    fun load() {
        val configured = System.getProperty("sherpa.onnx.kmp.native.path")
            ?: System.getenv("SHERPA_ONNX_KMP_NATIVE_PATH")
        if (configured != null) {
            val directory = File(configured).let { if (it.isDirectory) it else it.parentFile }
            loadDirectory(directory)
            return
        }

        val platform = platformDirectory()
        val bridgeResource = "$RESOURCE_ROOT/$platform/${System.mapLibraryName(BRIDGE_NAME)}"
        val loader = checkNotNull(NativeLoader::class.java.classLoader) {
            "Class loader is not available"
        }
        if (loader.getResource(bridgeResource) != null) {
            loadResources(platform)
            return
        }

        System.loadLibrary(LIBRARY_NAME)
    }

    private fun loadDirectory(directory: File) {
        val bridge = File(directory, System.mapLibraryName(BRIDGE_NAME))
        val jni = File(directory, System.mapLibraryName(LIBRARY_NAME))
        check(bridge.isFile) { "Bridge library does not exist: ${bridge.absolutePath}" }
        check(jni.isFile) { "JNI library does not exist: ${jni.absolutePath}" }
        System.load(bridge.absolutePath)
        System.load(jni.absolutePath)
    }

    private fun loadResources(platform: String) {
        val target = Files.createTempDirectory("sherpa-onnx-kmp-native").toFile()
        target.deleteOnExit()
        val names = listOf(BRIDGE_NAME, LIBRARY_NAME)
        names.forEach { name ->
            val mapped = System.mapLibraryName(name)
            val resource = "$RESOURCE_ROOT/$platform/$mapped"
            val loader = checkNotNull(NativeLoader::class.java.classLoader) {
                "Class loader is not available"
            }
            val input = loader.getResourceAsStream(resource)
                ?: error("Missing native resource: $resource")
            input.use {
                val output = File(target, mapped)
                Files.copy(it, output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                output.deleteOnExit()
            }
        }
        System.load(File(target, System.mapLibraryName(BRIDGE_NAME)).absolutePath)
        System.load(File(target, System.mapLibraryName(LIBRARY_NAME)).absolutePath)
    }

    private fun platformDirectory(): String {
        val os = (System.getProperty("os.name") ?: "").lowercase()
        val arch = (System.getProperty("os.arch") ?: "").lowercase()
        val osName = when {
            os.contains("mac") || os.contains("darwin") -> "osx"
            os.contains("win") -> "win"
            os.contains("linux") -> "linux"
            else -> error("Unsupported operating system: $os")
        }
        val archName = when {
            arch == "aarch64" || arch == "arm64" -> "aarch64"
            arch == "amd64" || arch == "x86_64" -> "x64"
            else -> error("Unsupported architecture: $arch")
        }
        return "$osName-$archName"
    }
}
