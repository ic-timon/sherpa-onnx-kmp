// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RecognitionResultTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun roundTripsStructuredResult() {
        val expected = RecognitionResult(
            text = "hello world",
            tokens = listOf(
                RecognitionToken(
                    text = "hello",
                    startTime = 0.1f,
                    duration = 0.2f,
                    logProbability = -0.01f,
                ),
                RecognitionToken(text = "world", startTime = 0.3f, duration = 0.2f),
            ),
            segments = listOf(RecognitionSegment("hello world", 0.1f, 0.4f)),
            language = "en",
            rawJson = """{"text":"hello world"}""",
        )

        val encoded = json.encodeToString(RecognitionResult.serializer(), expected)
        val decoded = json.decodeFromString(RecognitionResult.serializer(), encoded)

        assertEquals(expected, decoded)
    }

    @Test
    fun ignoresUnknownBridgeFields() {
        val decoded = json.decodeFromString(
            RecognitionResult.serializer(),
            """{"text":"ok","futureField":42}""",
        )

        assertEquals("ok", decoded.text)
        assertEquals("{}", decoded.rawJson)
    }
}
