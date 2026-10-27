// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import com.k2fsa.sherpa.onnx.kmp.internal.JniPortableTransport
import java.io.File

public class FileModelStore(
    private val rootPath: String,
) : ModelStore {
    override suspend fun prepare(files: Set<ModelPath>): PreparedModel {
        val root = File(rootPath).absoluteFile
        require(root.isDirectory) { "Model root does not exist: ${root.path}" }
        files.forEach { path ->
            require(File(root, path.value).isFile) {
                "Model file does not exist: ${File(root, path.value).path}"
            }
        }
        return PreparedModel(root.path)
    }
}

public suspend fun createSherpaRuntime(modelStore: ModelStore): SherpaRuntime =
    createPortableSherpaRuntime(JniPortableTransport, modelStore)
