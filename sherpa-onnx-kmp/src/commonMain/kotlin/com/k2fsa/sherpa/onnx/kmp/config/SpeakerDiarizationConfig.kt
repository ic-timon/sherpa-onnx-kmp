// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

/**
 * Configuration for offline speaker diarization.
 *
 * Dierization needs two models: a Pyannote segmentation model that finds
 * speaker turns, and a speaker embedding model that groups the turns. Clustering
 * either uses a known cluster count or a distance threshold, never both.
 */
public data class SpeakerDiarizationConfig(
    public val segmentationModel: ModelPath,
    public val embeddingModel: ModelPath,
    public val options: RuntimeOptions = RuntimeOptions(),
    public val numClusters: Int = -1,
    public val clusterThreshold: Float = 0.5f,
    public val minDurationOnSeconds: Float = 0.3f,
    public val minDurationOffSeconds: Float = 0.5f,
    public val extraOptions: Map<String, String> = emptyMap(),
) {
    init {
        require(numClusters >= -1) { "numClusters must be -1 or positive" }
        require(numClusters > 0 || clusterThreshold >= 0.0f) {
            "Either numClusters or clusterThreshold must be provided"
        }
        require(minDurationOnSeconds >= 0.0f) {
            "minDurationOnSeconds must not be negative"
        }
        require(minDurationOffSeconds >= 0.0f) {
            "minDurationOffSeconds must not be negative"
        }
    }
}

public fun SpeakerDiarizationConfig.requiredModelFiles(): Set<ModelPath> =
    setOf(segmentationModel, embeddingModel)
