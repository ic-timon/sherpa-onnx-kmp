// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

/**
 * A resource backed by an asynchronous runtime or a native handle.
 *
 * [close] is idempotent. Implementations must not rely on finalizers or garbage
 * collection to release native memory.
 */
public interface SherpaResource {
    public suspend fun close()
}

/**
 * Executes [block] and always closes this resource.
 */
public suspend inline fun <T : SherpaResource, R> T.useResource(
    block: suspend (T) -> R,
): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
