// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Runs the shared feature suite on Linux x64/arm64 and Windows x64.
 *
 * Only the model directory and the WAV reader are platform-specific; the
 * assertions are the ones Apple and the JVM also run.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NativeDesktopFeatureIntegrationTest : NativeFeatureIntegrationTest() {
    override fun featureModelDirectory(): String = NATIVE_DESKTOP_FEATURE_MODEL_DIR

    override fun readMono16BitWav(path: String): AudioData = PosixWavReader.read(path)

    override fun featureFileExists(relative: String): Boolean =
        isRegularFile("${featureModelDirectory()}/$relative")

    override fun featureDirectoryExists(relative: String): Boolean =
        isDirectory("${featureModelDirectory()}/$relative")
}
