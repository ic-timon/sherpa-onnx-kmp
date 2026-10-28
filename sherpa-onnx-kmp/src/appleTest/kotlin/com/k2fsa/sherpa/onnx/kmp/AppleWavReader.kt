// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

/**
 * Reads a mono 16-bit PCM WAV using Foundation, which is the only file reader
 * that works the same on iOS and macOS.
 *
 * Unknown chunks are skipped rather than rejected: sherpa-onnx's test waves are
 * written by sox and carry a `LIST` chunk between `fmt ` and `data`.
 */
@OptIn(ExperimentalForeignApi::class)
internal object AppleWavReader {
    fun read(path: String): AudioData {
        val data = requireNotNull(NSData.dataWithContentsOfFile(path)) {
            "Unable to read WAV file: $path"
        }
        val bytesPointer = requireNotNull(data.bytes) { "WAV data is empty: $path" }
        val bytes = bytesPointer.reinterpret<ByteVar>()
        val length = data.length.toInt()
        fun byteAt(index: Int): Int = bytes[index].toInt() and 0xff
        fun intAt(index: Int): Int =
            byteAt(index) or
                (byteAt(index + 1) shl 8) or
                (byteAt(index + 2) shl 16) or
                (byteAt(index + 3) shl 24)
        fun shortAt(index: Int): Int = byteAt(index) or (byteAt(index + 1) shl 8)
        fun asciiAt(index: Int): String =
            ByteArray(4) { bytes[index + it].toInt().toByte() }.decodeToString()

        require(length >= 12) { "WAV file is too short: $path" }
        require(asciiAt(0) == "RIFF") { "Expected a RIFF file: $path" }
        require(asciiAt(8) == "WAVE") { "Expected a WAVE file: $path" }

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
                    require(chunkSize >= 16) { "Short fmt chunk: $path" }
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

        require(audioFormat == 1) { "Expected PCM WAV: $path" }
        require(channels == 1) { "Expected mono WAV: $path" }
        require(bitsPerSample == 16) { "Expected 16-bit WAV: $path" }
        require(sampleRate > 0) { "WAV has no sample rate: $path" }
        return AudioData(
            samples = requireNotNull(samples) { "WAV has no data chunk: $path" },
            sampleRate = sampleRate,
        )
    }
}
