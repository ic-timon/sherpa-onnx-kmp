package com.k2fsa.sherpa.onnx.kmp.sample.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.k2fsa.sherpa.onnx.kmp.AssetModelStore
import com.k2fsa.sherpa.onnx.kmp.AudioData
import com.k2fsa.sherpa.onnx.kmp.ModelPath
import com.k2fsa.sherpa.onnx.kmp.OfflineModel
import com.k2fsa.sherpa.onnx.kmp.OfflineModelConfig
import com.k2fsa.sherpa.onnx.kmp.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.kmp.createSherpaRuntime
import com.k2fsa.sherpa.onnx.kmp.useResource
import java.io.DataInputStream
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            text = "Copy the pinned model into assets/models/nemo-ctc, then tap Transcribe."
            textSize = 18f
            gravity = Gravity.START
        }
        val button = Button(this).apply {
            text = "Transcribe test_wavs/0.wav"
            setOnClickListener {
                isEnabled = false
                output.text = "Loading model..."
                scope.launch {
                    runCatching { transcribeBundledAudio() }
                        .onSuccess { output.text = it }
                        .onFailure { output.text = it.stackTraceToString() }
                    isEnabled = true
                }
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
                addView(button)
                addView(
                    output,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = 48 },
                )
            },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun transcribeBundledAudio(): String {
        val assetRoot = "models/nemo-ctc"
        val audio = withContext(Dispatchers.IO) {
            readWav(assets.open("$assetRoot/test_wavs/0.wav"))
        }
        return createSherpaRuntime(AssetModelStore(this, assetRoot)).useResource { runtime ->
            val recognizer = runtime.createOfflineRecognizer(
                OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        model = OfflineModel.NemoCtc(ModelPath("model.int8.onnx")),
                        tokens = ModelPath("tokens.txt"),
                    ),
                ),
            )
            recognizer.useResource { it.transcribe(audio).text }
        }
    }

    private fun readWav(stream: InputStream): AudioData {
        DataInputStream(stream.buffered()).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            require(String(header, 0, 4) == "RIFF")
            require(String(header, 8, 4) == "WAVE")
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            var samples = FloatArray(0)
            while (true) {
                val chunk = ByteArray(8)
                val n = input.read(chunk)
                if (n < 0) break
                require(n == 8)
                val size = littleEndianInt(chunk, 4)
                when (String(chunk, 0, 4)) {
                    "fmt " -> {
                        val fmt = ByteArray(size)
                        input.readFully(fmt)
                        format = littleEndianShort(fmt, 0)
                        channels = littleEndianShort(fmt, 2)
                        sampleRate = littleEndianInt(fmt, 4)
                        bits = littleEndianShort(fmt, 14)
                    }
                    "data" -> {
                        val data = ByteArray(size)
                        input.readFully(data)
                        samples = FloatArray(size / 2) { i ->
                            val low = data[i * 2].toInt() and 0xff
                            val high = data[i * 2 + 1].toInt()
                            ((high shl 8) or low) / 32768.0f
                        }
                    }
                    else -> input.skipNBytes(size.toLong())
                }
                if (size % 2 != 0) input.skipNBytes(1)
            }
            require(format == 1 && channels == 1 && bits == 16 && sampleRate > 0)
            return AudioData(samples, sampleRate)
        }
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
}
