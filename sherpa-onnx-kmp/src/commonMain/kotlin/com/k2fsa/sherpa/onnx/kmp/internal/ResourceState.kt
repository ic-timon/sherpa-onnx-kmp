// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

internal class ResourceState {
    var isClosed: Boolean = false
        private set

    fun requireOpen() {
        if (isClosed) {
            throw SherpaClosedException()
        }
    }

    fun markClosed(): Boolean {
        if (isClosed) {
            return false
        }
        isClosed = true
        return true
    }
}
