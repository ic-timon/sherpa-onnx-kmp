// SPDX-License-Identifier: Apache-2.0
package com.k2fsa.sherpa.onnx.kmp

import kotlinx.coroutines.flow.Flow

public interface SherpaRuntime : SherpaResource {
    public val capabilities: SherpaCapabilities
    public val backendInfo: BackendInfo

    @Throws(Throwable::class)
    public suspend fun createOfflineRecognizer(
        config: OfflineRecognizerConfig,
    ): OfflineRecognizer

    @Throws(Throwable::class)
    public suspend fun createOnlineRecognizer(
        config: OnlineRecognizerConfig,
    ): OnlineRecognizer

    @Throws(Throwable::class)
    public suspend fun createVoiceActivityDetector(
        config: VadConfig,
    ): VoiceActivityDetector

    @Throws(Throwable::class)
    public suspend fun createOfflineTts(
        config: OfflineTtsConfig,
    ): OfflineTts

    @Throws(Throwable::class)
    public suspend fun createKeywordSpotter(
        config: KeywordSpotterConfig,
    ): KeywordSpotter

    @Throws(Throwable::class)
    public suspend fun createPunctuation(
        config: PunctuationConfig,
    ): Punctuation

    @Throws(Throwable::class)
    public suspend fun createSpeakerDiarization(
        config: SpeakerDiarizationConfig,
    ): SpeakerDiarization

    @Throws(Throwable::class)
    public suspend fun createSpeechEnhancer(
        config: SpeechEnhancementConfig,
    ): SpeechEnhancer
}

public interface OfflineRecognizer : SherpaResource {
    @Throws(Throwable::class)
    public suspend fun transcribe(audio: AudioData): RecognitionResult
    @Throws(Throwable::class)
    public suspend fun transcribeBatch(audio: List<AudioData>): List<RecognitionResult>
}

public interface OnlineRecognizer : SherpaResource {
    /**
     * Opens one decoding stream.
     *
     * [hotwords] is a phrase list in sherpa-onnx's on-the-fly format, with
     * tokens separated by spaces and phrases by `/`. It overrides the config's
     * hotwords file for this stream only.
     */
    @Throws(Throwable::class)
    public suspend fun openStream(hotwords: String = ""): OnlineSession
}

public interface OnlineSession : SherpaResource {
    /**
     * Feeds audio and returns the transcript so far.
     *
     * The call decodes every frame the recognizer is ready for, so the returned
     * text is current rather than one chunk behind.
     */
    @Throws(Throwable::class)
    public suspend fun push(audio: AudioData): OnlineUpdate

    /**
     * Signals end of input, decodes the tail, and returns the final result.
     *
     * The session remains queryable through [result] afterwards.
     */
    @Throws(Throwable::class)
    public suspend fun finish(): OnlineUpdate

    @Throws(Throwable::class)
    public suspend fun result(): OnlineUpdate

    @Throws(Throwable::class)
    public suspend fun reset()
}

public interface VoiceActivityDetector : SherpaResource {
    /**
     * Feeds audio and returns every segment that completed.
     *
     * Segments are removed from the detector's queue, so consecutive calls
     * never repeat a segment.
     */
    @Throws(Throwable::class)
    public suspend fun acceptWaveform(audio: AudioData): List<SpeechSegment>

    /**
     * Ends the utterance and returns the trailing segment, if any.
     */
    @Throws(Throwable::class)
    public suspend fun flush(): List<SpeechSegment>

    @Throws(Throwable::class)
    public suspend fun reset()

    /**
     * True while the detector considers the input to be speech.
     */
    @Throws(Throwable::class)
    public suspend fun isSpeechDetected(): Boolean
}

public interface OfflineTts : SherpaResource {
    public val sampleRate: Int
    public val numSpeakers: Int

    /**
     * Synthesizes [request] and emits audio chunks as they become available.
     *
     * The flow is cold: each collection runs a fresh synthesis. Generation
     * happens in the transport's worker, so the flow suspends rather than
     * blocking the caller's thread.
     */
    public fun generate(request: TtsRequest): Flow<AudioChunk>
}

public interface KeywordSpotter : SherpaResource {
    @Throws(Throwable::class)
    public suspend fun openStream(keywords: String = ""): KeywordSpotterStream
}

public interface KeywordSpotterStream : SherpaResource {
    @Throws(Throwable::class)
    public suspend fun push(audio: AudioData): KeywordSpotterResult

    @Throws(Throwable::class)
    public suspend fun result(): KeywordSpotterResult

    @Throws(Throwable::class)
    public suspend fun reset()
}

public interface Punctuation : SherpaResource {
    @Throws(Throwable::class)
    public suspend fun addPunctuation(text: String): PunctuationResult
}

public interface SpeakerDiarization : SherpaResource {
    @Throws(Throwable::class)
    public suspend fun process(audio: AudioData): DiarizationResult
}

public interface SpeechEnhancer : SherpaResource {
    /**
     * Denoises [audio] and returns the enhanced waveform.
     */
    @Throws(Throwable::class)
    public suspend fun denoise(audio: AudioData): AudioData
}
