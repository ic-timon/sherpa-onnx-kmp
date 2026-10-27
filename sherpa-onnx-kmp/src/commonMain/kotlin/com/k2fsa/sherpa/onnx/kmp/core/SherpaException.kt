// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

public open class SherpaOnnxException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

public class SherpaValidationException(
    message: String,
    cause: Throwable? = null,
) : SherpaOnnxException(message, cause)

public class SherpaClosedException(
    message: String = "Sherpa resource has been closed",
) : SherpaOnnxException(message)

public class SherpaUnsupportedException(
    message: String,
) : SherpaOnnxException(message)
