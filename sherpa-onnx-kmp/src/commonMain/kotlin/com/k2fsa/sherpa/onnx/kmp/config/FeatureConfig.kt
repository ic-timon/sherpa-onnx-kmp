// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

public data class FeatureConfig(
    public val sampleRate: Int = 16_000,
    public val featureDim: Int = 80,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(featureDim > 0) { "featureDim must be positive" }
    }
}

public data class RuntimeOptions(
    public val numThreads: Int = 1,
    public val provider: String = "cpu",
    public val debug: Boolean = false,
    public val extraOptions: Map<String, String> = emptyMap(),
) {
    init {
        require(numThreads > 0) { "numThreads must be positive" }
        require(provider.isNotBlank()) { "provider must not be blank" }
    }
}
