// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

/**
 * Filesystem probes for the Apple feature suite.
 *
 * Foundation's `NSFileManager` is the only API that behaves the same on iOS and
 * macOS, so the shared suite asks through this object rather than linking POSIX
 * directly.
 */
@OptIn(ExperimentalForeignApi::class)
internal object AppleFileSystem {
    fun isRegularFile(path: String): Boolean {
        val attributes = NSFileManager.defaultManager
            .attributesOfItemAtPath(path, null) ?: return false
        val type = attributes["NSFileType"] as? String ?: return false
        return type == "NSFileTypeRegular"
    }

    fun isDirectory(path: String): Boolean {
        val attributes = NSFileManager.defaultManager
            .attributesOfItemAtPath(path, null) ?: return false
        val type = attributes["NSFileType"] as? String ?: return false
        return type == "NSFileTypeDirectory"
    }
}
