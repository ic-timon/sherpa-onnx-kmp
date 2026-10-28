// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import com.k2fsa.sherpa.onnx.kmp.internal.NativePortableTransport

/**
 * Filesystem-backed model store for native desktop targets.
 *
 * The root must already exist and be a directory. Requested model files are
 * resolved relative to it, and a path containing `..` is rejected so callers
 * cannot escape the configured root.
 */
public class FileModelStore(
    private val rootPath: String,
) : ModelStore {
    @Throws(Throwable::class)
    override suspend fun prepare(files: Set<ModelPath>): PreparedModel {
        require(rootPath.isNotBlank()) { "Model root must not be blank" }
        require(isDirectory(rootPath)) {
            "Model root does not exist or is not a directory: $rootPath"
        }
        files.forEach { path ->
            require(!path.value.contains("..")) {
                "Model path must not contain '..': ${path.value}"
            }
            val resolved = joinPath(rootPath, path.value)
            require(isRegularFile(resolved)) {
                "Model file does not exist: $resolved"
            }
        }
        return PreparedModel(rootPath)
    }
}

@Throws(Throwable::class)
public suspend fun createSherpaRuntime(modelStore: ModelStore): SherpaRuntime =
    createPortableSherpaRuntime(NativePortableTransport, modelStore)

internal expect fun isDirectory(path: String): Boolean

internal expect fun isRegularFile(path: String): Boolean

internal expect fun pathSeparator(): Char

internal fun joinPath(root: String, relative: String): String {
    val separator = pathSeparator()
    val trimmed = root.trimEnd('/', '\\')
    return "$trimmed$separator$relative"
}
