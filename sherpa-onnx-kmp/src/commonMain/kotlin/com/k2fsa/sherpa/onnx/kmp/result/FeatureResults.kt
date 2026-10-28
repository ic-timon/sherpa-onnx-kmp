// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.serialization.Serializable

/**
 * Result of one keyword-spotting decode step.
 *
 * [keyword] is empty when no keyword has been triggered yet, which is the
 * normal state for a stream that has only seen silence. [tokens] describes the
 * decoded tail, so a caller can display partial matches.
 */
@Serializable
public data class KeywordSpotterResult(
    public val keyword: String = "",
    public val tokens: List<RecognitionToken> = emptyList(),
    public val startTime: Float = 0.0f,
    public val detected: Boolean = false,
    public val rawJson: String = "{}",
)

/**
 * One speaker turn produced by diarization.
 */
@Serializable
public data class DiarizationSegment(
    public val startTime: Float,
    public val endTime: Float,
    public val speaker: Int,
)

@Serializable
public data class DiarizationResult(
    public val numSpeakers: Int,
    public val segments: List<DiarizationSegment> = emptyList(),
    public val rawJson: String = "{}",
)

/**
 * Result of punctuation restoration.
 */
@Serializable
public data class PunctuationResult(
    public val text: String,
    public val rawJson: String = "{}",
)

/**
 * Metadata reported by the bridge for one audio-producing operation.
 *
 * The samples themselves travel outside JSON so the control plane never has to
 * base64-encode or stringify audio.
 */
@Serializable
public data class AudioPayload(
    public val sampleRate: Int,
    public val numSamples: Int,
    public val isLast: Boolean = true,
)
