// SPDX-License-Identifier: Apache-2.0
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

#include "bridge_internal.h"
#include "nlohmann/json.hpp"
#include "sherpa-onnx/csrc/offline-recognizer.h"
#include "sherpa-onnx/csrc/parse-options.h"

namespace sherpa_onnx::bridge {
namespace {

void SetErrorFromException(Error *error, const std::exception &e) {
  SetError(error, SHERPA_ONNX_BRIDGE_RUNTIME_ERROR, e.what());
}

std::unique_ptr<sherpa_onnx::OfflineRecognizer> CreateRecognizer(
    const Options &options, Error *error) {
  sherpa_onnx::OfflineRecognizerConfig config;
  sherpa_onnx::ParseOptions parser("sherpa-onnx portable bridge");
  config.Register(&parser);

  for (const auto &[key, value] : options) {
    std::string message;
    if (!parser.TrySetOption(key, value, &message)) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT, message);
      return nullptr;
    }
  }

  if (!config.Validate()) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "offline-asr configuration validation failed");
    return nullptr;
  }

  try {
    return std::make_unique<sherpa_onnx::OfflineRecognizer>(config);
  } catch (const std::exception &e) {
    SetErrorFromException(error, e);
    return nullptr;
  }
}

nlohmann::json OptionalFloatArray(const std::vector<float> &values,
                                  int32_t index) {
  if (index >= 0 && static_cast<size_t>(index) < values.size()) {
    return values[static_cast<size_t>(index)];
  }
  return nullptr;
}

std::string ToBridgeResultJson(
    const sherpa_onnx::OfflineRecognitionResult &result) {
  nlohmann::json tokens = nlohmann::json::array();
  for (size_t i = 0; i != result.tokens.size(); ++i) {
    nlohmann::json token = {{"text", result.tokens[i]}};
    auto timestamp = OptionalFloatArray(result.timestamps, i);
    auto duration = OptionalFloatArray(result.durations, i);
    auto log_probability = OptionalFloatArray(result.ys_log_probs, i);
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

class SherpaOfflineAsrBackend final : public OfflineAsrBackend {
 public:
  explicit SherpaOfflineAsrBackend(
      std::unique_ptr<sherpa_onnx::OfflineRecognizer> recognizer)
      : recognizer_(std::move(recognizer)) {}

  std::string Transcribe(const Audio &audio, Error *error) override {
    if (audio.sample_rate <= 0) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
               "Audio sample rate must be positive");
      return {};
    }
    if (audio.samples.empty()) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
               "Audio must contain at least one sample");
      return {};
    }

    try {
      std::unique_ptr<sherpa_onnx::OfflineStream> stream =
          recognizer_->CreateStream();
      stream->AcceptWaveform(audio.sample_rate, audio.samples.data(),
                             static_cast<int32_t>(audio.samples.size()));
      recognizer_->DecodeStream(stream.get());
      return ToBridgeResultJson(stream->GetResult());
    } catch (const std::exception &e) {
      SetErrorFromException(error, e);
      return {};
    }
  }

 private:
  std::unique_ptr<sherpa_onnx::OfflineRecognizer> recognizer_;
};

class SherpaOfflineAsrFactory final : public OfflineAsrBackendFactory {
 public:
  std::unique_ptr<OfflineAsrBackend> Create(
      const Options &options, Error *error) override {
    auto recognizer = CreateRecognizer(options, error);
    if (!recognizer) {
      return nullptr;
    }
    return std::make_unique<SherpaOfflineAsrBackend>(std::move(recognizer));
  }
};

}  // namespace

void RegisterDefaultOfflineAsrFactory() {
  SetOfflineAsrFactoryForTesting(std::make_unique<SherpaOfflineAsrFactory>());
}

}  // namespace sherpa_onnx::bridge
