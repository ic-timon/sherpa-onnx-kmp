// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class)
internal actual fun isDirectory(path: String): Boolean = isType(path, S_IFDIR)

@OptIn(ExperimentalForeignApi::class)
internal actual fun isRegularFile(path: String): Boolean = isType(path, S_IFREG)

internal actual fun pathSeparator(): Char = '/'

@OptIn(ExperimentalForeignApi::class)
private fun isType(path: String, expected: Int): Boolean = memScoped {
    val info = alloc<stat>()
    if (stat(path, info.ptr) != 0) {
        false
    } else {
        (info.st_mode.toInt() and S_IFMT) == expected
    }
}
