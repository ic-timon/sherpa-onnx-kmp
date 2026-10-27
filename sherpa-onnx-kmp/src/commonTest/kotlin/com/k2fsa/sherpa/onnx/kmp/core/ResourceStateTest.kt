// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceStateTest {
    @Test
    fun closesOnlyOnce() {
        val state = ResourceState()

        assertFalse(state.isClosed)
        assertTrue(state.markClosed())
        assertFalse(state.markClosed())
        assertTrue(state.isClosed)
    }

    @Test
    fun useAfterCloseFails() {
        val state = ResourceState()
        state.markClosed()

        assertFailsWith<SherpaClosedException> {
            state.requireOpen()
        }
    }
}

class UseResourceTest {
    @Test
    fun closesOnSuccess() = kotlinx.coroutines.test.runTest {
        val resource = FakeResource()

        resource.useResource { it.send() }

        assertEquals(1, resource.sent)
        assertTrue(resource.isClosed)
    }

    @Test
    fun closesOnFailure() = kotlinx.coroutines.test.runTest {
        val resource = FakeResource()

        assertFailsWith<IllegalStateException> {
            resource.useResource {
                error("boom")
            }
        }

        assertTrue(resource.isClosed)
    }

    private class FakeResource : SherpaResource {
        val state = ResourceState()
        var sent: Int = 0

        suspend fun send() {
            state.requireOpen()
            sent += 1
        }

        override suspend fun close() {
            state.markClosed()
        }

        val isClosed: Boolean
            get() = state.isClosed
    }
}
