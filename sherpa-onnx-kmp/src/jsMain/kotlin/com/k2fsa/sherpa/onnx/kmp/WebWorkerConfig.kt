// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

/**
 * Global that identifies the worker script bundled with the web artifact.
 *
 * Applications can override it, for example to serve the worker from a
 * versioned asset path or a CDN:
 *
 * ```kotlin
 * configureSherpaWebWorker { scriptUrl = "/assets/sherpa-onnx-worker.js" }
 * ```
 */
public object SherpaWebWorkerConfig {
    /**
     * Script URL for the Emscripten-backed worker. When null, the worker
     * resolves `sherpa-onnx-worker.js` relative to the current page.
     */
    public var scriptUrl: String? = null

    /**
     * Maximum time to wait for the worker to report readiness during runtime
     * creation, in milliseconds.
     */
    public var readyTimeoutMillis: Long = 60_000
}

/**
 * Sets the worker script URL used by the web transport.
 */
public fun configureSherpaWebWorker(scriptUrl: String?) {
    SherpaWebWorkerConfig.scriptUrl = scriptUrl
}
