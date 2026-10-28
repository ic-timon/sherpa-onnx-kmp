// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import com.k2fsa.sherpa.onnx.kmp.internal.NativePortableTransport
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

@OptIn(ExperimentalForeignApi::class)
public class FileModelStore(
    private val rootPath: String,
) : ModelStore {
    @Throws(Throwable::class)
    override suspend fun prepare(files: Set<ModelPath>): PreparedModel {
        val manager = NSFileManager.defaultManager
        require(manager.fileExistsAtPath(rootPath)) {
            "Model root does not exist: $rootPath"
        }
        files.forEach { path ->
            val resolved = joinPath(rootPath, path.value)
            require(manager.fileExistsAtPath(resolved)) {
                "Model file does not exist: $resolved"
            }
        }
        return PreparedModel(rootPath)
    }
}

/**
 * Resolves model paths relative to a directory copied into the application
 * bundle, for example "models/nemo-ctc".
 */
@OptIn(ExperimentalForeignApi::class)
public class BundleModelStore(
    private val resourceRoot: String = "models",
    private val bundle: NSBundle = NSBundle.mainBundle,
) : ModelStore {
    @Throws(Throwable::class)
    override suspend fun prepare(files: Set<ModelPath>): PreparedModel {
        val resourcePath = requireNotNull(bundle.resourcePath) {
            "Application bundle does not expose a resource path"
        }
        val manager = NSFileManager.defaultManager
        val root = listOf(
            joinPath(resourcePath, resourceRoot),
            joinPath(joinPath(resourcePath, "Resources"), resourceRoot),
        ).firstOrNull(manager::fileExistsAtPath)
            ?: error("Model resource directory does not exist in the bundle: $resourceRoot")
        files.forEach { path ->
            val resolved = joinPath(root, path.value)
            require(manager.fileExistsAtPath(resolved)) {
                "Model resource does not exist in the bundle: $resourceRoot/${path.value}"
            }
        }
        return PreparedModel(root)
    }
}

@Throws(Throwable::class)
public suspend fun createSherpaRuntime(modelStore: ModelStore): SherpaRuntime =
    createPortableSherpaRuntime(NativePortableTransport, modelStore)

private fun joinPath(root: String, relative: String): String =
    if (root.endsWith('/')) root + relative else "$root/$relative"
