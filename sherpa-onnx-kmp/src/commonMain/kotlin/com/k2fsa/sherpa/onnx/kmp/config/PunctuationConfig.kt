// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

/**
 * Configuration for offline punctuation restoration.
 *
 * The model is a CT-Transformer that inserts punctuation into a token-free
 * transcript. It is a text post-processor, so it never touches audio.
 */
public data class PunctuationConfig(
    public val model: ModelPath,
    public val options: RuntimeOptions = RuntimeOptions(),
    public val extraOptions: Map<String, String> = emptyMap(),
)

public fun PunctuationConfig.requiredModelFiles(): Set<ModelPath> = setOf(model)
