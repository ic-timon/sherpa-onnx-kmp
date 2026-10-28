// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/**
 * Reads a mono 16-bit PCM WAV through POSIX stdio, which exists on Linux, macOS,
 * and MinGW. Used by both native desktop targets so the shared feature suite can
 * load the same fixtures the JVM suite uses.
 *
 * Unknown chunks are skipped: sherpa-onnx's test waves carry a `LIST` chunk
 * between `fmt ` and `data`, and rejecting it would make the reader reject the
 * pinned audio itself.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixWavReader {
    fun read(path: String): AudioData {
        val bytes = readFile(path)
        require(bytes.size >= 12) { "WAV file is too short: $path" }
        fun byteAt(index: Int): Int = bytes[index].toInt() and 0xff
        fun intAt(index: Int): Int =
            byteAt(index) or
                (byteAt(index + 1) shl 8) or
                (byteAt(index + 2) shl 16) or
                (byteAt(index + 3) shl 24)
        fun shortAt(index: Int): Int = byteAt(index) or (byteAt(index + 1) shl 8)
        fun asciiAt(index: Int): String =
            ByteArray(4) { bytes[index + it] }.decodeToString()

        require(asciiAt(0) == "RIFF") { "Expected a RIFF file: $path" }
        require(asciiAt(8) == "WAVE") { "Expected a WAVE file: $path" }

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var samples: FloatArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = asciiAt(offset)
            val chunkSize = intAt(offset + 4)
            val body = offset + 8
            require(body + chunkSize <= bytes.size) { "Truncated WAV chunk: $id" }
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
        require(sampleRate > 0) { "Invalid sample rate: $path" }
        return AudioData(
            samples = requireNotNull(samples) { "WAV has no data chunk: $path" },
            sampleRate = sampleRate,
        )
    }

    private fun readFile(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("Unable to open file: $path")
        try {
            if (fseek(file, 0, SEEK_END) != 0) error("Unable to seek file: $path")
            val size = ftell(file)
            if (size <= 0) error("Unable to measure file: $path")
            if (fseek(file, 0, SEEK_SET) != 0) error("Unable to rewind file: $path")
            val bytes = ByteArray(size.toInt())
            val read = bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, size.toULong(), file).toLong()
            }
            if (read != size.toLong()) error("Short read for file: $path")
            return bytes
        } finally {
            fclose(file)
        }
    }
}
