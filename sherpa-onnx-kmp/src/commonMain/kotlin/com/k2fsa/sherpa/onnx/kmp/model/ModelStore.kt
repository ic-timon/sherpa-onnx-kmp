// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlin.jvm.JvmInline

@JvmInline
public value class ModelPath(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ModelPath must not be blank" }
    }
}

/**
 * A platform-owned location containing the files requested from a ModelStore.
 *
 * The string is opaque to common code. Native targets receive a filesystem
 * root, while web targets receive an Emscripten MEMFS root.
 */
public data class PreparedModel(
    public val rootPath: String,
)

/**
 * Resolves logical model files into a runtime-accessible root.
 */
public fun interface ModelStore {
    @Throws(Throwable::class)
    public suspend fun prepare(files: Set<ModelPath>): PreparedModel
}
