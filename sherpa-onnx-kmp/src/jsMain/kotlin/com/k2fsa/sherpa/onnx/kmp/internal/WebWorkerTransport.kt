// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp.internal

import com.k2fsa.sherpa.onnx.kmp.AudioChunk
import com.k2fsa.sherpa.onnx.kmp.AudioData
import com.k2fsa.sherpa.onnx.kmp.BridgeHandle
import com.k2fsa.sherpa.onnx.kmp.BridgeReply
import com.k2fsa.sherpa.onnx.kmp.PortableTransport
import com.k2fsa.sherpa.onnx.kmp.SherpaOnnxException
import com.k2fsa.sherpa.onnx.kmp.SherpaWebWorkerConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.MessageEvent
import org.w3c.dom.Worker
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Web Worker transport for Kotlin/JS.
 *
 * The worker owns the Emscripten module, so every bridge call crosses the
 * worker boundary. Requests are serialized by id and each caller suspends until
 * its reply arrives. Audio crosses the boundary as a transferable Float32
 * buffer instead of a structured clone, so samples are moved rather than copied.
 *
 * See ADR 0004 for why the wrapper is Kotlin/JS and the engine is Emscripten.
 */
internal object WebWorkerTransport : PortableTransport {
    private val pending = mutableMapOf<Int, PendingRequest>()
    private val ready = mutableListOf<() -> Unit>()
    private var nextId = 1
    private var worker: Worker? = null
    private var readyState = false
    private var fatalError: String? = null

    /** Set by the worker once the Emscripten module is initialized. */
    internal fun markReady() {
        if (readyState) return
        readyState = true
        val callbacks = ready.toList()
        ready.clear()
        callbacks.forEach { it() }
    }

    internal fun markFatal(message: String) {
        fatalError = message
        val callbacks = ready.toList()
        ready.clear()
        readyState = false
        val error = SherpaOnnxException(message)
        pending.values.forEach { it.continuation.resumeWithException(error) }
        pending.clear()
        callbacks.forEach { it() }
    }

    private fun ensureWorker(): Worker {
        worker?.let { return it }
        val created = createWorker()
        created.onmessage = ::onMessage
        created.onerror = { event: Event ->
            val detail = (event.asDynamic().message as? String) ?: "Worker error"
            markFatal(detail)
        }
        worker = created
        return created
    }

    private fun createWorker(): Worker {
        val url = SherpaWebWorkerConfig.scriptUrl ?: "sherpa-onnx-worker.js"
        return try {
            Worker(url)
        } catch (t: Throwable) {
            throw SherpaOnnxException(
                "Unable to start the sherpa-onnx worker from '$url'. " +
                    "Web inference requires a browser environment and a bundled " +
                    "worker script; see docs/web-transport.md.",
                t,
            )
        }
    }

    private fun onMessage(event: MessageEvent) {
        val payload = event.data.asDynamic()
        val type = payload.type as? String ?: return
        when (type) {
            "ready" -> markReady()
            "fatal" -> {
                val message = payload.message as? String ?: "Worker failed"
                markFatal(message)
            }
            "reply" -> {
                val id = (payload.id as? Number)?.toInt() ?: return
                val request = pending.remove(id) ?: return
                val ok = payload.ok as? Boolean ?: false
                val json = payload.result as? String ?: "{}"
                if (ok) {
                    request.continuation.resume(
                        WorkerReply(json = json, audio = readAudio(payload)),
                    )
                } else {
                    val message = payload.message as? String ?: "Worker call failed"
                    request.continuation.resumeWithException(
                        SherpaOnnxException(message),
                    )
                }
            }
        }
    }

    /**
     * Reads the optional audio payload from a reply.
     *
     * The worker transfers a `Float32Array` buffer back, so the samples arrive
     * without a copy. A reply without audio is a normal end-of-stream signal.
     */
    private fun readAudio(payload: dynamic): AudioChunk? {
        val audio = payload.audio
        if (audio == null) return null
        val samples = audio.samples
        if (samples == null) return null
        val length = (samples.length as? Number)?.toInt() ?: 0
        if (length <= 0) return null
        val copy = FloatArray(length)
        for (index in 0 until length) {
            copy[index] = (samples[index] as Number).toFloat()
        }
        val sampleRate = (audio.sampleRate as? Number)?.toInt() ?: 0
        return AudioChunk(
            samples = copy,
            sampleRate = sampleRate,
            // `progress` and `isLast` are payload fields the engine reads from
            // the JSON `result`, so the transport leaves neutral values.
            progress = 1.0f,
            isLast = true,
        )
    }

    private suspend fun awaitReady() {
        fatalError?.let { throw SherpaOnnxException(it) }
        if (readyState) return
        ensureWorker()
        suspendCancellableCoroutine { continuation ->
            val callback = {
                if (continuation.isActive) {
                    val failure = fatalError
                    if (failure != null) {
                        continuation.resumeWithException(SherpaOnnxException(failure))
                    } else {
                        continuation.resume(Unit)
                    }
                }
            }
            ready.add(callback)
            continuation.invokeOnCancellation { ready.remove(callback) }
        }
    }

    private suspend fun request(
        type: String,
        payload: dynamic = js("({})"),
        transfer: Array<dynamic>? = null,
    ): WorkerReply {
        awaitReady()
        val active = ensureWorker()
        val id = nextId++
        payload.id = id
        payload.type = type
        return suspendCancellableCoroutine { continuation ->
            pending[id] = PendingRequest(continuation)
            continuation.invokeOnCancellation { pending.remove(id) }
            if (transfer != null && transfer.isNotEmpty()) {
                active.postMessage(payload, transfer)
            } else {
                active.postMessage(payload)
            }
        }
    }

    override suspend fun infoJson(): String = request("info").json

    override suspend fun create(kind: String, optionsJson: String): BridgeHandle {
        val payload = js("({})")
        payload.kind = kind
        payload.optionsJson = optionsJson
        val raw = request("create", payload).json
        val handle = raw.toLongOrNull()
            ?: throw SherpaOnnxException("Worker returned an invalid handle: $raw")
        return BridgeHandle(handle)
    }

    override suspend fun call(
        handle: BridgeHandle,
        operation: String,
        argumentsJson: String,
    ): String {
        val payload = js("({})")
        payload.handle = handle.value.toString()
        payload.operation = operation
        payload.argumentsJson = argumentsJson
        return request("call", payload).json
    }

    override suspend fun pushAudio(
        handle: BridgeHandle,
        operation: String,
        audio: AudioData,
    ): String {
        val payload = js("({})")
        payload.handle = handle.value.toString()
        payload.operation = operation
        payload.sampleRate = audio.sampleRate
        payload.numSamples = audio.samples.size
        val typed: dynamic = createFloat32Array(audio.samples.size)
        audio.samples.forEachIndexed { index, value -> typed[index] = value }
        payload.samples = typed
        // Transfer the backing buffer so the samples are moved to the worker
        // instead of structured-cloned.
        val buffer: dynamic = typed.buffer
        return request("pushAudio", payload, arrayOf<dynamic>(buffer)).json
    }

    override suspend fun pullAudio(
        handle: BridgeHandle,
        operation: String,
        argumentsJson: String,
    ): BridgeReply {
        val payload = js("({})")
        payload.handle = handle.value.toString()
        payload.operation = operation
        payload.argumentsJson = argumentsJson
        val reply = request("pullAudio", payload)
        return BridgeReply(json = reply.json, audio = reply.audio)
    }

    override suspend fun destroy(handle: BridgeHandle) {
        val payload = js("({})")
        payload.handle = handle.value.toString()
        runCatching { request("destroy", payload) }
    }

    override suspend fun close() {
        val active = worker ?: return
        worker = null
        readyState = false
        pending.values.forEach {
            it.continuation.resumeWithException(SherpaOnnxException("Worker was closed"))
        }
        pending.clear()
        active.terminate()
    }

    /**
     * Ensures the worker has the requested model files in its virtual
     * filesystem, then returns the mount path. Used by the web ModelStore.
     */
    internal suspend fun prepareModels(
        baseUrl: String,
        mountPath: String,
        files: List<String>,
    ): String {
        val payload = js("({})")
        payload.baseUrl = baseUrl
        payload.mountPath = mountPath
        payload.files = files.toTypedArray()
        return request("models.prepare", payload).json
    }

    private class PendingRequest(
        val continuation: kotlinx.coroutines.CancellableContinuation<WorkerReply>,
    )

    private class WorkerReply(
        val json: String,
        val audio: AudioChunk?,
    )
}

/**
 * Allocates a `Float32Array` of the given length.
 *
 * `js()` requires a constant string, so the size is passed in as a variable
 * rather than interpolated into the source snippet.
 */
private fun createFloat32Array(size: Int): dynamic =
    js("new Float32Array(size)")
