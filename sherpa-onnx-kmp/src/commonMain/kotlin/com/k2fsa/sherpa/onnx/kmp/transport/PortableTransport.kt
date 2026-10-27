// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlin.jvm.JvmInline

@JvmInline
internal value class BridgeHandle(
    val value: Long,
)

internal data class BridgeReply(
    val json: String,
    val audio: AudioChunk? = null,
)

/**
 * Internal contract implemented by JNI, native cinterop, and web worker
 * transports. Feature-specific logic must not live in implementations.
 */
internal interface PortableTransport : SherpaResource {
    suspend fun create(
        kind: String,
        optionsJson: String,
    ): BridgeHandle

    suspend fun call(
        handle: BridgeHandle,
        operation: String,
        argumentsJson: String = "{}",
    ): String

    suspend fun pushAudio(
        handle: BridgeHandle,
        operation: String,
        audio: AudioData,
    ): String

    suspend fun pullAudio(
        handle: BridgeHandle,
        operation: String,
        argumentsJson: String = "{}",
    ): BridgeReply

    suspend fun destroy(handle: BridgeHandle)
}
