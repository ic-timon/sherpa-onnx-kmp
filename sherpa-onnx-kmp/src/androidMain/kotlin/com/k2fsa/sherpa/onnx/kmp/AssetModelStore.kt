// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import android.content.Context
import java.io.File

/**
 * Materializes Android assets into the application cache once.
 *
 * [assetRoot] is a path inside the APK assets, for example "models/nemo-ctc".
 */
public class AssetModelStore(
    private val context: Context,
    private val assetRoot: String,
) : ModelStore {
    override suspend fun prepare(files: Set<ModelPath>): PreparedModel {
        val targetRoot = File(
            context.cacheDir,
            "sherpa-onnx-kmp/${assetRoot.replace('/', '_')}",
        )
        val assets = context.assets
        files.forEach { path ->
            val relative = path.value
            val target = File(targetRoot, relative)
            if (!target.isFile) {
                target.parentFile?.mkdirs()
                assets.open("$assetRoot/$relative").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return PreparedModel(targetRoot.absolutePath)
    }
}
