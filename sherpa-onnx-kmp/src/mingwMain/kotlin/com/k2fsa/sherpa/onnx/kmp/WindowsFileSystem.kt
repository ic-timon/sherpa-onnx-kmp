// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.GetFileAttributesW
import platform.windows.INVALID_FILE_ATTRIBUTES

/**
 * The MinGW CRT does not export `_stat` through the Kotlin/Native platform
 * libraries, so type probing uses `GetFileAttributesW` instead. Kotlin/Native
 * maps the `LPCWSTR` parameter to `String`, so the call takes the path
 * directly without manual UTF-16 marshalling.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun isDirectory(path: String): Boolean {
    val attributes = attributesOf(path) ?: return false
    return attributes and FILE_ATTRIBUTE_DIRECTORY != 0
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun isRegularFile(path: String): Boolean {
    val attributes = attributesOf(path) ?: return false
    return attributes and FILE_ATTRIBUTE_DIRECTORY == 0
}

internal actual fun pathSeparator(): Char = '\\'

@OptIn(ExperimentalForeignApi::class)
private fun attributesOf(path: String): Int? {
    val attributes = GetFileAttributesW(path)
    return if (attributes == INVALID_FILE_ATTRIBUTES) null else attributes.toInt()
}
