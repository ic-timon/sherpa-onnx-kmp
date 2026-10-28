// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp.internal

import com.k2fsa.sherpa.onnx.kmp.AudioChunk
import com.k2fsa.sherpa.onnx.kmp.AudioData
import com.k2fsa.sherpa.onnx.kmp.BridgeHandle
import com.k2fsa.sherpa.onnx.kmp.BridgeReply
import com.k2fsa.sherpa.onnx.kmp.PortableTransport
import com.k2fsa.sherpa.onnx.kmp.SherpaOnnxException
import com.k2fsa.sherpa.onnx.kmp.SherpaUnsupportedException
import com.k2fsa.sherpa.onnx.kmp.SherpaValidationException
import com.k2fsa.sherpa.onnx.kmp.cinterop.SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT
import com.k2fsa.sherpa.onnx.kmp.cinterop.SHERPA_ONNX_BRIDGE_OK
import com.k2fsa.sherpa.onnx.kmp.cinterop.SHERPA_ONNX_BRIDGE_UNSUPPORTED
import com.k2fsa.sherpa.onnx.kmp.cinterop.SherpaOnnxBridgeCall
import com.k2fsa.sherpa.onnx.kmp.cinterop.SherpaOnnxBridgeCreate
import com.k2fsa.sherpa.onnx.kmp.cinterop.SherpaOnnxBridgeDestroy
import com.k2fsa.sherpa.onnx.kmp.cinterop.SherpaOnnxBridgeFree
import com.k2fsa.sherpa.onnx.kmp.cinterop.SherpaOnnxBridgeFreeAudio
import com.k2fsa.sherpa.onnx.kmp.cinterop.SherpaOnnxBridgeInfoJson
import com.k2fsa.sherpa.onnx.kmp.cinterop.SherpaOnnxBridgePullAudio
import com.k2fsa.sherpa.onnx.kmp.cinterop.SherpaOnnxBridgePushAudio
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Portable Bridge transport shared by every Kotlin/Native target.
 *
 * Apple, Linux, and Windows differ only in linking and packaging, so the
 * transport itself has no platform-conditional code. A target that links the
 * bridge archive gets the same behavior.
 */
@OptIn(ExperimentalForeignApi::class)
internal object NativePortableTransport : PortableTransport {
    override suspend fun infoJson(): String = bridgeCall {
        consumeString(SherpaOnnxBridgeInfoJson()).ifEmpty {
            throw SherpaOnnxException("Bridge returned empty capability information")
        }
    }

    override suspend fun create(kind: String, optionsJson: String): BridgeHandle = bridgeCall {
        memScoped {
            val handle = alloc<ULongVar>()
            val error = alloc<CPointerVar<ByteVar>>().apply { value = null }
            val status = SherpaOnnxBridgeCreate(kind, optionsJson, handle.ptr, error.ptr)
            checkStatus(status, "create $kind", error.value)
            BridgeHandle(handle.value.toLong())
        }
    }

    override suspend fun call(
        handle: BridgeHandle,
        operation: String,
        argumentsJson: String,
    ): String = bridgeCall {
        memScoped {
            val result = alloc<CPointerVar<ByteVar>>().apply { value = null }
            val error = alloc<CPointerVar<ByteVar>>().apply { value = null }
            val status = SherpaOnnxBridgeCall(
                handle = handle.value.toULong(),
                operation = operation,
                arguments_json = argumentsJson,
                result_json = result.ptr,
                error = error.ptr,
            )
            checkStatus(status, operation, error.value)
            consumeString(result.value).ifEmpty {
                throw SherpaOnnxException("Bridge operation returned an empty result: $operation")
            }
        }
    }

    override suspend fun pushAudio(
        handle: BridgeHandle,
        operation: String,
        audio: AudioData,
    ): String = bridgeCall {
        memScoped {
            val error = alloc<CPointerVar<ByteVar>>().apply { value = null }
            val status = audio.samples.usePinned { pinned ->
                val samples = if (audio.samples.isEmpty()) {
                    null
                } else {
                    pinned.addressOf(0)
                }
                SherpaOnnxBridgePushAudio(
                    handle = handle.value.toULong(),
                    operation = operation,
                    sample_rate = audio.sampleRate,
                    samples = samples,
                    num_samples = audio.samples.size,
                    error = error.ptr,
                )
            }
            checkStatus(status, operation, error.value)
            "{}"
        }
    }

    override suspend fun pullAudio(
        handle: BridgeHandle,
        operation: String,
        argumentsJson: String,
    ): BridgeReply = bridgeCall {
        memScoped {
            val sampleRate = alloc<IntVar>().apply { value = 0 }
            val numSamples = alloc<IntVar>().apply { value = 0 }
            val samples = alloc<CPointerVar<FloatVar>>().apply { value = null }
            val result = alloc<CPointerVar<ByteVar>>().apply { value = null }
            val error = alloc<CPointerVar<ByteVar>>().apply { value = null }
            val status = SherpaOnnxBridgePullAudio(
                handle = handle.value.toULong(),
                operation = operation,
                arguments_json = argumentsJson,
                sample_rate = sampleRate.ptr,
                samples = samples.ptr,
                num_samples = numSamples.ptr,
                result_json = result.ptr,
                error = error.ptr,
            )
            checkStatus(status, operation, error.value)

            val json = consumeString(result.value)
            val pointer = samples.value
            val count = numSamples.value
            val rate = sampleRate.value
            try {
                val chunk = if (pointer == null || count <= 0) {
                    null
                } else {
                    val copy = FloatArray(count)
                    for (index in 0 until count) {
                        copy[index] = pointer[index]
                    }
                    AudioChunk(
                        samples = copy,
                        sampleRate = rate,
                        // `progress` and `isLast` live in the operation's JSON;
                        // the transport leaves them at their neutral values and
                        // the engine overrides them from the payload.
                        progress = 1.0f,
                        isLast = true,
                    )
                }
                BridgeReply(json = json.ifEmpty { "{}" }, audio = chunk)
            } finally {
                if (pointer != null) {
                    SherpaOnnxBridgeFreeAudio(pointer)
                }
            }
        }
    }

    override suspend fun destroy(handle: BridgeHandle) {
        withContext(Dispatchers.Default) {
            SherpaOnnxBridgeDestroy(handle.value.toULong())
        }
    }

    override suspend fun close() = Unit

    private suspend fun <T> bridgeCall(block: () -> T): T = withContext(Dispatchers.Default) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is SherpaOnnxException) throw t
            throw SherpaOnnxException(t.message ?: "Portable bridge call failed", t)
        }
    }

    private fun checkStatus(
        status: Int,
        operation: String,
        error: CPointer<ByteVar>?,
    ) {
        if (status == SHERPA_ONNX_BRIDGE_OK.toInt()) {
            error?.let(::consumeString)
            return
        }
        val message = error?.let(::consumeString)
            ?.takeIf(String::isNotBlank)
            ?: "Portable bridge failed during $operation (status $status)"
        throw when (status.toUInt()) {
            SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT -> SherpaValidationException(message)
            SHERPA_ONNX_BRIDGE_UNSUPPORTED -> SherpaUnsupportedException(message)
            else -> SherpaOnnxException(message)
        }
    }

    private fun consumeString(pointer: CPointer<ByteVar>?): String {
        if (pointer == null) return ""
        return try {
            pointer.toKString()
        } finally {
            SherpaOnnxBridgeFree(pointer)
        }
    }
}
