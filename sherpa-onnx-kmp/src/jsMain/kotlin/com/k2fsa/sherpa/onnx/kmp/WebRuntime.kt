// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import com.k2fsa.sherpa.onnx.kmp.internal.WebWorkerTransport

/**
 * Model store backed by the worker's Emscripten virtual filesystem.
 *
 * The worker downloads each requested file from [baseUrl] and writes it under
 * [mountPath] in MEMFS. sherpa-onnx then reads the model from that path, which
 * keeps model bytes out of the page's main thread and out of the Kotlin heap.
 *
 * Determinism: a file already present at the mount path with a matching size is
 * not downloaded again, so repeated runtime creation in one worker does not
 * refetch. Model versioning is the caller's responsibility through [baseUrl].
 */
public class HttpModelStore(
    private val baseUrl: String,
    private val mountPath: String = "/models",
) : ModelStore {

    override suspend fun prepare(files: Set<ModelPath>): PreparedModel {
        require(baseUrl.isNotBlank()) { "Model base URL must not be blank" }
        require(mountPath.startsWith("/")) {
            "Mount path must be an absolute MEMFS path: $mountPath"
        }
        val names = files.map { path ->
            require(!path.value.contains("..")) {
                "Model path must not contain '..': ${path.value}"
            }
            path.value
        }
        val resolved = WebWorkerTransport.prepareModels(
            baseUrl = baseUrl.trimEnd('/'),
            mountPath = mountPath.trimEnd('/'),
            files = names,
        )
        return PreparedModel(resolved)
    }
}

/**
 * Model store for files already written into MEMFS by the host page.
 */
public class MemoryModelStore(
    private val rootPath: String,
) : ModelStore {

    override suspend fun prepare(files: Set<ModelPath>): PreparedModel {
        require(rootPath.startsWith("/")) {
            "MEMFS root must be an absolute path: $rootPath"
        }
        files.forEach { path ->
            require(!path.value.contains("..")) {
                "Model path must not contain '..': ${path.value}"
            }
        }
        return PreparedModel(rootPath.trimEnd('/'))
    }
}


public suspend fun createSherpaRuntime(modelStore: ModelStore): SherpaRuntime =
    createPortableSherpaRuntime(WebWorkerTransport, modelStore)
