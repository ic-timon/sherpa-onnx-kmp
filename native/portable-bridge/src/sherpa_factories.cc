// SPDX-License-Identifier: Apache-2.0
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "bridge_internal.h"

namespace sherpa_onnx::bridge {

#if defined(SHERPA_ONNX_BRIDGE_WITH_SHERPA)

std::unique_ptr<BackendFactory> CreateOfflineAsrFactory();
std::unique_ptr<BackendFactory> CreateOnlineAsrFactory();
std::unique_ptr<BackendFactory> CreateVadFactory();
std::unique_ptr<BackendFactory> CreateTtsFactory();
std::unique_ptr<BackendFactory> CreateKwsFactory();
std::unique_ptr<BackendFactory> CreatePunctuationFactory();
std::unique_ptr<BackendFactory> CreateSpeechDenoiserFactory();
#if defined(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION) && \
    SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION
std::unique_ptr<BackendFactory> CreateSpeakerDiarizationFactory();
#endif

namespace {

/**
 * Registers every sherpa-onnx-backed kind exactly once.
 *
 * `SherpaOnnxBridgeInfoJson` and `SherpaOnnxBridgeCreate` both call this, and
 * both may run concurrently on different threads, so the guard is a
 * function-local static: C++11 guarantees a single, thread-safe initialization.
 *
 * A factory whose feature was compiled out is not registered. That is how the
 * capability report stays truthful for a build such as the Emscripten bridge,
 * which currently links with `SHERPA_ONNX_ENABLE_TTS=OFF`.
 */
bool InstallDefaults() {
  auto &factories = DefaultFactories();
  factories.emplace("offline-asr", CreateOfflineAsrFactory());
  factories.emplace("online-asr", CreateOnlineAsrFactory());
  factories.emplace("vad", CreateVadFactory());
#if defined(SHERPA_ONNX_ENABLE_TTS) && SHERPA_ONNX_ENABLE_TTS
  factories.emplace("tts", CreateTtsFactory());
#endif
  factories.emplace("kws", CreateKwsFactory());
  factories.emplace("punctuation", CreatePunctuationFactory());
  factories.emplace("speech-denoiser", CreateSpeechDenoiserFactory());
#if defined(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION) && \
    SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION
  factories.emplace("speaker-diarization", CreateSpeakerDiarizationFactory());
#endif
  return true;
}

}  // namespace

void RegisterDefaultFactories() {
  static const bool installed = InstallDefaults();
  (void)installed;
}

#else

void RegisterDefaultFactories() {}

#endif

}  // namespace sherpa_onnx::bridge
