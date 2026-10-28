// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

/**
 * Runs the shared feature suite on iOS and macOS.
 *
 * Only the model directory and the WAV reader are Apple-specific: the
 * assertions, the config mappers, and the cinterop transport are the same ones
 * the native desktop targets use, so a change in behavior there fails here too.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal class AppleFeatureIntegrationTest : NativeFeatureIntegrationTest() {
    override fun featureModelDirectory(): String = APPLE_FEATURE_MODEL_DIR

    override fun readMono16BitWav(path: String): AudioData = AppleWavReader.read(path)

    override fun featureFileExists(relative: String): Boolean =
        AppleFileSystem.isRegularFile("${featureModelDirectory()}/$relative")

    override fun featureDirectoryExists(relative: String): Boolean =
        AppleFileSystem.isDirectory("${featureModelDirectory()}/$relative")
}
