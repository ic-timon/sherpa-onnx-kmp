// SPDX-License-Identifier: Apache-2.0

/**
 * Browser entry point for the web sample.
 *
 * Compiled with the Kotlin/JS production bundle and exported as
 * `runSample(modelBaseUrl)` for `index.html`.
 */
package com.k2fsa.sherpa.onnx.kmp.sample

import com.k2fsa.sherpa.onnx.kmp.AudioData
import com.k2fsa.sherpa.onnx.kmp.HttpModelStore
import com.k2fsa.sherpa.onnx.kmp.ModelPath
import com.k2fsa.sherpa.onnx.kmp.OfflineModel
import com.k2fsa.sherpa.onnx.kmp.OfflineModelConfig
import com.k2fsa.sherpa.onnx.kmp.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.kmp.configureSherpaWebWorker
import com.k2fsa.sherpa.onnx.kmp.createSherpaRuntime
import com.k2fsa.sherpa.onnx.kmp.useResource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transcribes `test_wavs/0.wav` from [modelBaseUrl] and returns the text.
 *
 * Kept as a suspend function behind a `@JsExport` so the HTML page can call it
 * with `await` from a module script.
 */
@JsExport
public suspend fun runSample(modelBaseUrl: String): String {
    configureSherpaWebWorker("./sherpa-onnx-worker.js")

    val wav = loadWav("$modelBaseUrl/test_wavs/0.wav")
    val store = HttpModelStore(baseUrl = modelBaseUrl, mountPath = "/models/nemo-ctc")

    createSherpaRuntime(store).useResource { runtime ->
        val recognizer = runtime.createOfflineRecognizer(
            OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    model = OfflineModel.NemoCtc(ModelPath("model.int8.onnx")),
                    tokens = ModelPath("tokens.txt"),
                ),
            ),
        )
        recognizer.useResource {
            return it.transcribe(wav).text
        }
    }
}

private suspend fun loadWav(url: String): AudioData {
    val buffer: dynamic = fetchArrayBuffer(url)
    val view: dynamic = js("new Uint8Array(buffer)")
    val length: Int = view.length as Int
    require(length >= 12) { "WAV file is too short: $url" }

    fun byteAt(index: Int): Int = (view[index] as Int) and 0xff
    fun intAt(index: Int): Int =
        byteAt(index) or
            (byteAt(index + 1) shl 8) or
            (byteAt(index + 2) shl 16) or
            (byteAt(index + 3) shl 24)
    fun shortAt(index: Int): Int = byteAt(index) or (byteAt(index + 1) shl 8)
    fun asciiAt(index: Int): String =
        (0 until 4).map { byteAt(index + it).toChar() }.joinToString("")

    require(asciiAt(0) == "RIFF") { "Expected a RIFF file: $url" }
    require(asciiAt(8) == "WAVE") { "Expected a WAVE file: $url" }

    var sampleRate = 0
    var channels = 0
    var bitsPerSample = 0
    var audioFormat = 0
    var samples: FloatArray? = null
    var offset = 12
    while (offset + 8 <= length) {
        val id = asciiAt(offset)
        val chunkSize = intAt(offset + 4)
        val body = offset + 8
        require(body + chunkSize <= length) { "Truncated WAV chunk: $id" }
        when (id) {
            "fmt " -> {
                audioFormat = shortAt(body)
                channels = shortAt(body + 2)
                sampleRate = intAt(body + 4)
                bitsPerSample = shortAt(body + 14)
            }
            "data" -> {
                samples = FloatArray(chunkSize / 2) { index ->
                    val sample = shortAt(body + index * 2)
                    val signed = if (sample >= 0x8000) sample - 0x10000 else sample
                    signed / 32768.0f
                }
            }
        }
        offset = body + chunkSize + (chunkSize and 1)
    }

    require(audioFormat == 1) { "Expected PCM WAV" }
    require(channels == 1) { "Expected mono WAV" }
    require(bitsPerSample == 16) { "Expected 16-bit WAV" }
    require(sampleRate > 0) { "Invalid sample rate" }

    return AudioData(
        samples = requireNotNull(samples) { "WAV has no data chunk" },
        sampleRate = sampleRate,
    )
}

/**
 * Downloads a URL and returns its ArrayBuffer.
 *
 * The request must be asynchronous: browsers reject assigning `responseType`
 * on a synchronous `XMLHttpRequest`, and `arraybuffer` is the only response
 * type that yields the raw bytes without text-decoding them first.
 */
private suspend fun fetchArrayBuffer(url: String): dynamic =
    suspendCancellableCoroutine { continuation ->
        val request: dynamic = js("new XMLHttpRequest()")
        request.open("GET", url, true)
        request.responseType = "arraybuffer"
        request.onload = {
            val status: Int = request.status as Int
            if (status < 200 || status > 299) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Unable to load $url (HTTP $status)"),
                    )
                }
            } else {
                val response: dynamic = request.response
                if (response == null) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Empty response for $url"),
                        )
                    }
                } else if (continuation.isActive) {
                    continuation.resume(response)
                }
            }
        }
        request.onerror = {
            if (continuation.isActive) {
                continuation.resumeWithException(
                    IllegalStateException("Network error while loading $url"),
                )
            }
        }
        continuation.invokeOnCancellation { request.abort() }
        request.send()
    }
