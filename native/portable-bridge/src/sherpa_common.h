// SPDX-License-Identifier: Apache-2.0
#ifndef SHERPA_ONNX_PORTABLE_BRIDGE_SHERPA_COMMON_H_
#define SHERPA_ONNX_PORTABLE_BRIDGE_SHERPA_COMMON_H_

#include <string>
#include <vector>

#include "bridge_internal.h"
#include "nlohmann/json.hpp"
#include "sherpa-onnx/csrc/offline-stream.h"
#include "sherpa-onnx/csrc/online-recognizer.h"
#include "sherpa-onnx/csrc/parse-options.h"
#include "sherpa-onnx/csrc/voice-activity-detector.h"

namespace sherpa_onnx::bridge {

/**
 * Applies flat options to a sherpa-onnx config.
 *
 * The config must already be registered with `Register(&parser)`. Every option
 * goes through `TrySetOption`, so an unknown key is reported as
 * `INVALID_ARGUMENT` instead of aborting the process the way upstream's
 * `ParseOptions::SetOption` does.
 */
bool ApplyOptions(const Options &options, sherpa_onnx::ParseOptions *parser,
                  Error *error);

/** Converts an exception into a bridge runtime error. */
void SetErrorFromException(Error *error, const std::exception &e);

/** Validates an input audio buffer. */
bool ValidateAudio(const Audio &audio, Error *error);

/** Builds the stable token array shared by offline and online results. */
nlohmann::json TokensJson(const std::vector<std::string> &tokens,
                          const std::vector<float> &timestamps,
                          const std::vector<float> &log_probabilities);

/** Converts an offline recognition result to the stable bridge schema. */
std::string OfflineResultJson(
    const sherpa_onnx::OfflineRecognitionResult &result);

/** Converts an online recognition result to the stable bridge schema. */
std::string OnlineResultJson(const sherpa_onnx::OnlineRecognizerResult &result);

/** Converts a VAD segment to the stable bridge schema. */
nlohmann::json SpeechSegmentJson(const sherpa_onnx::SpeechSegment &segment);

}  // namespace sherpa_onnx::bridge

#endif  // SHERPA_ONNX_PORTABLE_BRIDGE_SHERPA_COMMON_H_
