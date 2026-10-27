// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelStoreTest {
    @Test
    fun rejectsBlankPath() {
        assertFailsWith<IllegalArgumentException> {
            ModelPath(" ")
        }
    }

    @Test
    fun offlineRecognizerReportsRequiredFiles() {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                model = OfflineModel.Transducer(
                    encoder = ModelPath("encoder.onnx"),
                    decoder = ModelPath("decoder.onnx"),
                    joiner = ModelPath("joiner.onnx"),
                ),
                tokens = ModelPath("tokens.txt"),
            ),
            hotwordsFile = ModelPath("hotwords.txt"),
        )

        assertEquals(
            setOf(
                ModelPath("tokens.txt"),
                ModelPath("encoder.onnx"),
                ModelPath("decoder.onnx"),
                ModelPath("joiner.onnx"),
                ModelPath("hotwords.txt"),
            ),
            config.requiredModelFiles(),
        )
    }

    @Test
    fun modelStoreCanBeImplementedAsFunInterface() = kotlinx.coroutines.test.runTest {
        val store = ModelStore { files ->
            PreparedModel(rootPath = "/resolved/${files.size}")
        }

        assertEquals(
            PreparedModel("/resolved/2"),
            store.prepare(setOf(ModelPath("a.onnx"), ModelPath("b.txt"))),
        )
    }
}
