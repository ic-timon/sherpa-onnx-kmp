// SPDX-License-Identifier: Apache-2.0
#include "sherpa_common.h"

#include "sherpa-onnx/csrc/offline-recognizer.h"
#include "sherpa-onnx/csrc/online-recognizer.h"
#include "sherpa-onnx/csrc/parse-options.h"
#include "sherpa-onnx/csrc/voice-activity-detector.h"

namespace sherpa_onnx::bridge {

bool ApplyOptions(const Options &options, sherpa_onnx::ParseOptions *parser,
                  Error *error) {
  for (const auto &[key, value] : options) {
    std::string message;
    if (!parser->TrySetOption(key, value, &message)) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT, message);
      return false;
    }
  }
  return true;
}

void SetErrorFromException(Error *error, const std::exception &e) {
  SetError(error, SHERPA_ONNX_BRIDGE_RUNTIME_ERROR, e.what());
}

bool ValidateAudio(const Audio &audio, Error *error) {
  if (audio.sample_rate <= 0) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "Audio sample rate must be positive");
    return false;
  }
  if (audio.samples.empty()) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "Audio must contain at least one sample");
    return false;
  }
  return true;
}

namespace {

nlohmann::json OptionalFloat(const std::vector<float> &values, size_t index) {
  if (index < values.size()) {
    return values[index];
  }
  return nullptr;
}

}  // namespace

nlohmann::json TokensJson(const std::vector<std::string> &tokens,
                          const std::vector<float> &timestamps,
                          const std::vector<float> &log_probabilities) {
  nlohmann::json answer = nlohmann::json::array();
  for (size_t i = 0; i != tokens.size(); ++i) {
    nlohmann::json token = {{"text", tokens[i]}};
    auto timestamp = OptionalFloat(timestamps, i);
    auto log_probability = OptionalFloat(log_probabilities, i);
    if (!timestamp.is_null()) {
      token["startTime"] = timestamp;
    }
    if (!log_probability.is_null()) {
      token["logProbability"] = log_probability;
    }
    answer.push_back(std::move(token));
  }
  return answer;
}

std::string OfflineResultJson(
    const sherpa_onnx::OfflineRecognitionResult &result) {
  nlohmann::json tokens = nlohmann::json::array();
  for (size_t i = 0; i != result.tokens.size(); ++i) {
    nlohmann::json token = {{"text", result.tokens[i]}};
    auto timestamp = OptionalFloat(result.timestamps, i);
    auto duration = OptionalFloat(result.durations, i);
    auto log_probability = OptionalFloat(result.ys_log_probs, i);
    if (!timestamp.is_null()) {
      token["startTime"] = timestamp;
    }
    if (!duration.is_null()) {
      token["duration"] = duration;
    }
    if (!log_probability.is_null()) {
      token["logProbability"] = log_probability;
    }
    tokens.push_back(std::move(token));
  }

  nlohmann::json segments = nlohmann::json::array();
  const size_t segment_count = result.segment_texts.size();
  for (size_t i = 0; i != segment_count; ++i) {
    nlohmann::json segment = {{"text", result.segment_texts[i]}};
    if (i < result.segment_timestamps.size()) {
      segment["startTime"] = result.segment_timestamps[i];
    }
    if (i < result.segment_durations.size()) {
      segment["duration"] = result.segment_durations[i];
    }
    segments.push_back(std::move(segment));
  }

  nlohmann::json answer = {
      {"schemaVersion", 1},
      {"text", result.text},
      {"tokens", std::move(tokens)},
      {"segments", std::move(segments)},
  };

  if (!result.lang.empty()) {
    answer["language"] = result.lang;
  }
  if (!result.emotion.empty()) {
    answer["emotion"] = result.emotion;
  }
  if (!result.event.empty()) {
    answer["event"] = result.event;
  }
  answer["rawJson"] = result.AsJsonString();
  return answer.dump();
}

std::string OnlineResultJson(const sherpa_onnx::OnlineRecognizerResult &result) {
  nlohmann::json answer = {
      {"schemaVersion", 1},
      {"text", result.text},
      {"tokens", TokensJson(result.tokens, result.timestamps, result.ys_probs)},
      {"segments", nlohmann::json::array()},
      {"segment", result.segment},
      {"startTime", result.start_time},
      {"isFinal", result.is_final},
      {"isEof", result.is_eof},
      {"rawJson", result.AsJsonString()},
  };
  return answer.dump();
}

nlohmann::json SpeechSegmentJson(const sherpa_onnx::SpeechSegment &segment) {
  return nlohmann::json{
      {"startSample", segment.start},
      {"numSamples", segment.samples.size()},
  };
}

}  // namespace sherpa_onnx::bridge
