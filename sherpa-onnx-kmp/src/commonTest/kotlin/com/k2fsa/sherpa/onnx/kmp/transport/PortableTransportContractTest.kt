// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortableTransportContractTest {
    @Test
    fun preservesCommandOrderAndClosesHandles() = runTest {
        val transport = FakePortableTransport()

        transport.useResource { active ->
            val handle = active.create("offline-asr", """{"tokens":"tokens.txt"}""")
            val result = active.call(handle, "offline.transcribe")
            val audioReply = active.pullAudio(handle, "offline.result-audio")

            assertEquals("""{"text":"ok"}""", result)
            assertEquals(1, audioReply.audio?.samples?.size)
            assertEquals(
                listOf(
                    "create:offline-asr",
                    "call:offline.transcribe",
                    "pull:offline.result-audio",
                ),
                active.operations,
            )
            active.destroy(handle)
        }

        assertTrue(transport.isClosed)
    }

    @Test
    fun fakeTransportRejectsInvalidHandle() = runTest {
        val transport = FakePortableTransport()

        assertFailsWith<IllegalArgumentException> {
            transport.call(BridgeHandle(999), "offline.transcribe")
        }
    }

    private class FakePortableTransport : PortableTransport {
        val operations = mutableListOf<String>()
        private val handles = mutableSetOf<BridgeHandle>()
        private var nextHandle = 1L
        private val state = ResourceState()

        override suspend fun create(kind: String, optionsJson: String): BridgeHandle {
            state.requireOpen()
            operations += "create:$kind"
            val handle = BridgeHandle(nextHandle++)
            handles += handle
            return handle
        }

        override suspend fun call(
            handle: BridgeHandle,
            operation: String,
            argumentsJson: String,
        ): String {
            state.requireOpen()
            require(handle in handles) { "Unknown handle: $handle" }
            operations += "call:$operation"
            return """{"text":"ok"}"""
        }

        override suspend fun pushAudio(
            handle: BridgeHandle,
            operation: String,
            audio: AudioData,
        ): String {
            state.requireOpen()
            require(handle in handles) { "Unknown handle: $handle" }
            operations += "push:$operation"
            return "{}"
        }

        override suspend fun pullAudio(
            handle: BridgeHandle,
            operation: String,
            argumentsJson: String,
        ): BridgeReply {
            state.requireOpen()
            require(handle in handles) { "Unknown handle: $handle" }
            operations += "pull:$operation"
            return BridgeReply(
                json = "{}",
                audio = AudioChunk(
                    samples = floatArrayOf(0.1f),
                    sampleRate = 16_000,
                    progress = 1.0f,
                    isLast = true,
                ),
            )
        }

        override suspend fun destroy(handle: BridgeHandle) {
            state.requireOpen()
            handles -= handle
        }

        override suspend fun close() {
            state.markClosed()
            handles.clear()
        }

        val isClosed: Boolean
            get() = state.isClosed
    }
}
