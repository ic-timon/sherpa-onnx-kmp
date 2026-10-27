// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.serialization.Serializable

@Serializable
public data class RecognitionToken(
    public val text: String,
    public val startTime: Float? = null,
    public val duration: Float? = null,
    public val logProbability: Float? = null,
)

@Serializable
public data class RecognitionSegment(
    public val text: String,
    public val startTime: Float,
    public val duration: Float,
)

@Serializable
public data class RecognitionResult(
    public val text: String,
    public val tokens: List<RecognitionToken> = emptyList(),
    public val segments: List<RecognitionSegment> = emptyList(),
    public val language: String? = null,
    public val emotion: String? = null,
    public val event: String? = null,
    public val rawJson: String = "{}",
)

public data class OnlineUpdate(
    public val result: RecognitionResult,
    public val isEndpoint: Boolean,
    public val isFinal: Boolean,
)
