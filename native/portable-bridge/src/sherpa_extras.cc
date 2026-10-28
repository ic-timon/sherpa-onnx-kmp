// SPDX-License-Identifier: Apache-2.0
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "sherpa_common.h"
#include "sherpa-onnx/csrc/keyword-spotter.h"
#include "sherpa-onnx/csrc/offline-punctuation.h"
#include "sherpa-onnx/csrc/offline-speech-denoiser.h"
#include "sherpa-onnx/csrc/parse-options.h"

#if defined(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION) && \
    SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION
#include "sherpa-onnx/csrc/offline-speaker-diarization.h"
#define SHERPA_ONNX_BRIDGE_WITH_DIARIZATION 1
#endif

namespace sherpa_onnx::bridge {
namespace {

// ---------------------------------------------------------------------------
// Keyword spotting
// ---------------------------------------------------------------------------

struct KeywordSpotterState {
  std::mutex mutex;
  std::unique_ptr<sherpa_onnx::KeywordSpotter> spotter;
};

std::shared_ptr<KeywordSpotterState> CreateSpotter(const Options &options,
                                                   Error *error) {
  sherpa_onnx::KeywordSpotterConfig config;
  sherpa_onnx::ParseOptions parser("sherpa-onnx portable bridge");
  config.Register(&parser);

  if (!ApplyOptions(options, &parser, error)) {
    return nullptr;
  }
  if (!config.Validate()) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "kws configuration validation failed: " + config.ToString());
    return nullptr;
  }
  try {
    auto state = std::make_shared<KeywordSpotterState>();
    state->spotter = std::make_unique<sherpa_onnx::KeywordSpotter>(config);
    return state;
  } catch (const std::exception &e) {
    SetErrorFromException(error, e);
    return nullptr;
  }
}

std::string KeywordsArgument(const std::string &arguments) {
  if (arguments.empty()) {
    return {};
  }
  try {
    auto parsed = nlohmann::json::parse(arguments);
    if (parsed.is_object() && parsed.contains("keywords") &&
        parsed["keywords"].is_string()) {
      return parsed["keywords"].get<std::string>();
    }
  } catch (const std::exception &) {
  }
  return {};
}

class KwsStreamBackend final : public Backend {
 public:
  KwsStreamBackend(std::shared_ptr<KeywordSpotterState> state,
                   std::unique_ptr<sherpa_onnx::OnlineStream> stream)
      : state_(std::move(state)), stream_(std::move(stream)) {}

  std::vector<std::string> Operations() const override {
    return {"push-audio", "decode", "result", "reset"};
  }

  std::string Call(const std::string &operation,
                   const std::string & /*arguments*/,
                   Error *error) override {
    std::lock_guard<std::mutex> lock(state_->mutex);
    auto &spotter = *state_->spotter;
    try {
      if (operation == "kws-stream.decode") {
        while (spotter.IsReady(stream_.get())) {
          spotter.DecodeStream(stream_.get());
        }
        return ResultJson(spotter);
      }
      if (operation == "kws-stream.result") {
        return ResultJson(spotter);
      }
      if (operation == "kws-stream.reset") {
        spotter.Reset(stream_.get());
        return "{\"ok\":true}";
      }
    } catch (const std::exception &e) {
      SetErrorFromException(error, e);
      return {};
    }
    SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
             std::string("Unsupported operation: ") + operation);
    return {};
  }

  void PushAudio(const std::string &operation, const Audio &audio,
                 Error *error) override {
    if (operation != "kws-stream.push-audio") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio operation: ") + operation);
      return;
    }
    if (!ValidateAudio(audio, error)) {
      return;
    }
    std::lock_guard<std::mutex> lock(state_->mutex);
    try {
      stream_->AcceptWaveform(audio.sample_rate, audio.samples.data(),
                              static_cast<int32_t>(audio.samples.size()));
    } catch (const std::exception &e) {
      SetErrorFromException(error, e);
    }
  }

 private:
  std::string ResultJson(const sherpa_onnx::KeywordSpotter &spotter) {
    auto result = spotter.GetResult(stream_.get());
    nlohmann::json answer = {
        {"schemaVersion", 1},
        {"keyword", result.keyword},
        {"tokens", TokensJson(result.tokens, result.timestamps, {})},
        {"startTime", result.start_time},
        {"detected", !result.keyword.empty()},
        {"rawJson", result.AsJsonString()},
    };
    return answer.dump();
  }

  std::shared_ptr<KeywordSpotterState> state_;
  std::unique_ptr<sherpa_onnx::OnlineStream> stream_;
};

class KwsBackend final : public Backend {
 public:
  explicit KwsBackend(std::shared_ptr<KeywordSpotterState> state)
      : state_(std::move(state)) {}

  std::vector<std::string> Operations() const override {
    return {"create-stream"};
  }

  std::string Call(const std::string &operation,
                   const std::string &arguments, Error *error) override {
    if (operation != "kws.create-stream") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported operation: ") + operation);
      return {};
    }
    try {
      std::unique_ptr<sherpa_onnx::OnlineStream> stream;
      {
        std::lock_guard<std::mutex> lock(state_->mutex);
        const std::string keywords = KeywordsArgument(arguments);
        stream = keywords.empty() ? state_->spotter->CreateStream()
                                  : state_->spotter->CreateStream(keywords);
      }
      if (!stream) {
        SetError(error, SHERPA_ONNX_BRIDGE_RUNTIME_ERROR,
                 "kws did not create a stream");
        return {};
      }
      const uint64_t child =
          RegisterChild("kws-stream",
                        std::make_unique<KwsStreamBackend>(state_,
                                                           std::move(stream)));
      if (child == 0) {
        SetError(error, SHERPA_ONNX_BRIDGE_INVALID_STATE,
                 "kws-stream could not be registered");
        return {};
      }
      return nlohmann::json{{"handle", child}}.dump();
    } catch (const std::exception &e) {
      SetErrorFromException(error, e);
      return {};
    }
  }

 private:
  std::shared_ptr<KeywordSpotterState> state_;
};

// ---------------------------------------------------------------------------
// Punctuation
// ---------------------------------------------------------------------------

class PunctuationBackend final : public Backend {
 public:
  explicit PunctuationBackend(
      std::unique_ptr<sherpa_onnx::OfflinePunctuation> punctuation)
      : punctuation_(std::move(punctuation)) {}

  std::vector<std::string> Operations() const override {
    return {"add-punctuation"};
  }

  std::string Call(const std::string &operation,
                   const std::string &arguments, Error *error) override {
    if (operation != "punctuation.add-punctuation") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported operation: ") + operation);
      return {};
    }
    nlohmann::json parsed;
    try {
      parsed = nlohmann::json::parse(arguments.empty() ? "{}" : arguments);
    } catch (const std::exception &e) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
               std::string("Invalid punctuation arguments: ") + e.what());
      return {};
    }
    if (!parsed.is_object() || !parsed.contains("text") ||
        !parsed["text"].is_string()) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
               "punctuation.add-punctuation requires a string 'text'");
      return {};
    }
    try {
      const std::string text = parsed["text"].get<std::string>();
      nlohmann::json answer = {
          {"schemaVersion", 1},
          {"text", punctuation_->AddPunctuation(text)},
          {"rawJson", text},
      };
      return answer.dump();
    } catch (const std::exception &e) {
      SetErrorFromException(error, e);
      return {};
    }
  }

 private:
  std::unique_ptr<sherpa_onnx::OfflinePunctuation> punctuation_;
};

// ---------------------------------------------------------------------------
// Speech enhancement
// ---------------------------------------------------------------------------

class SpeechDenoiserBackend final : public Backend {
 public:
  explicit SpeechDenoiserBackend(
      std::unique_ptr<sherpa_onnx::OfflineSpeechDenoiser> denoiser)
      : denoiser_(std::move(denoiser)) {}

  std::vector<std::string> Operations() const override {
    return {"set-audio", "denoise", "take-audio"};
  }

  std::string Call(const std::string &operation,
                   const std::string & /*arguments*/,
                   Error *error) override {
    if (operation == "speech-denoiser.denoise") {
      if (!has_audio_) {
        SetError(error, SHERPA_ONNX_BRIDGE_INVALID_STATE,
                 "No audio has been pushed to this handle");
        return {};
      }
      try {
        auto denoised = denoiser_->Run(pending_.samples.data(),
                                       static_cast<int32_t>(
                                           pending_.samples.size()),
                                       pending_.sample_rate);
        pending_audio_ = std::move(denoised.samples);
        pending_sample_rate_ = denoised.sample_rate;
        nlohmann::json answer = {
            {"schemaVersion", 1},
            {"sampleRate", pending_sample_rate_},
            {"numSamples", pending_audio_.size()},
        };
        return answer.dump();
      } catch (const std::exception &e) {
        SetErrorFromException(error, e);
        return {};
      }
    }
    if (operation == "speech-denoiser.take-audio") {
      nlohmann::json answer = {
          {"sampleRate", pending_sample_rate_},
          {"numSamples", pending_audio_.size()},
      };
      return answer.dump();
    }
    SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
             std::string("Unsupported operation: ") + operation);
    return {};
  }

  void PushAudio(const std::string &operation, const Audio &audio,
                 Error *error) override {
    if (operation != "speech-denoiser.set-audio") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio operation: ") + operation);
      return;
    }
    if (!ValidateAudio(audio, error)) {
      return;
    }
    pending_ = audio;
    has_audio_ = true;
  }

  std::string PullAudio(const std::string &operation,
                        const std::string & /*arguments*/, Audio *audio,
                        Error *error) override {
    if (operation != "speech-denoiser.take-audio") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio output operation: ") + operation);
      return {};
    }
    audio->sample_rate = pending_sample_rate_;
    audio->samples = std::move(pending_audio_);
    pending_audio_.clear();
    return nlohmann::json{
        {"sampleRate", audio->sample_rate},
        {"numSamples", audio->samples.size()},
    }.dump();
  }

 private:
  std::unique_ptr<sherpa_onnx::OfflineSpeechDenoiser> denoiser_;
  Audio pending_;
  bool has_audio_ = false;
  std::vector<float> pending_audio_;
  int32_t pending_sample_rate_ = 0;
};

#if defined(SHERPA_ONNX_BRIDGE_WITH_DIARIZATION)

// ---------------------------------------------------------------------------
// Speaker diarization
// ---------------------------------------------------------------------------

class SpeakerDiarizationBackend final : public Backend {
 public:
  explicit SpeakerDiarizationBackend(
      std::unique_ptr<sherpa_onnx::OfflineSpeakerDiarization> diarization)
      : diarization_(std::move(diarization)) {}

  std::vector<std::string> Operations() const override { return {"process"}; }

  std::string Call(const std::string &operation,
                   const std::string & /*arguments*/,
                   Error *error) override {
    if (operation != "speaker-diarization.process") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported operation: ") + operation);
      return {};
    }
    if (!has_audio_) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_STATE,
               "No audio has been pushed to this handle");
      return {};
    }
    try {
      auto result = diarization_->Process(
          pending_.samples.data(),
          static_cast<int32_t>(pending_.samples.size()));
      nlohmann::json segments = nlohmann::json::array();
      auto sorted = result.SortByStartTime();
      for (const auto &segment : sorted) {
        segments.push_back({
            {"startTime", segment.Start()},
            {"endTime", segment.End()},
            {"speaker", segment.Speaker()},
        });
      }
      nlohmann::json answer = {
          {"schemaVersion", 1},
          {"numSpeakers", result.NumSpeakers()},
          {"segments", std::move(segments)},
      };
      return answer.dump();
    } catch (const std::exception &e) {
      SetErrorFromException(error, e);
      return {};
    }
  }

  void PushAudio(const std::string &operation, const Audio &audio,
                 Error *error) override {
    if (operation != "speaker-diarization.set-audio") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio operation: ") + operation);
      return;
    }
    if (!ValidateAudio(audio, error)) {
      return;
    }
    const int32_t expected = diarization_->SampleRate();
    if (audio.sample_rate != expected) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
               "Diarization expects " + std::to_string(expected) +
                   " Hz audio, got " + std::to_string(audio.sample_rate));
      return;
    }
    pending_ = audio;
    has_audio_ = true;
  }

 private:
  std::unique_ptr<sherpa_onnx::OfflineSpeakerDiarization> diarization_;
  Audio pending_;
  bool has_audio_ = false;
};

#endif  // SHERPA_ONNX_BRIDGE_WITH_DIARIZATION

}  // namespace

std::unique_ptr<BackendFactory> CreateKwsFactory() {
  class Factory final : public BackendFactory {
   public:
    std::unique_ptr<Backend> Create(const Options &options,
                                    Error *error) override {
      auto state = CreateSpotter(options, error);
      if (!state) {
        return nullptr;
      }
      return std::make_unique<KwsBackend>(std::move(state));
    }

    std::vector<std::string> Operations() const override {
      return {"create-stream"};
    }

    std::vector<std::pair<std::string, std::vector<std::string>>>
    ChildOperations() const override {
      return {{"kws-stream", {"push-audio", "decode", "result", "reset"}}};
    }
  };
  return std::make_unique<Factory>();
}

std::unique_ptr<BackendFactory> CreatePunctuationFactory() {
  class Factory final : public BackendFactory {
   public:
    std::unique_ptr<Backend> Create(const Options &options,
                                    Error *error) override {
      sherpa_onnx::OfflinePunctuationConfig config;
      sherpa_onnx::ParseOptions parser("sherpa-onnx portable bridge");
      config.Register(&parser);
      if (!ApplyOptions(options, &parser, error)) {
        return nullptr;
      }
      if (!config.Validate()) {
        SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
                 "punctuation configuration validation failed: " +
                     config.ToString());
        return nullptr;
      }
      try {
        return std::make_unique<PunctuationBackend>(
            std::make_unique<sherpa_onnx::OfflinePunctuation>(config));
      } catch (const std::exception &e) {
        SetErrorFromException(error, e);
        return nullptr;
      }
    }

    std::vector<std::string> Operations() const override {
      return {"add-punctuation"};
    }
  };
  return std::make_unique<Factory>();
}

std::unique_ptr<BackendFactory> CreateSpeechDenoiserFactory() {
  class Factory final : public BackendFactory {
   public:
    std::unique_ptr<Backend> Create(const Options &options,
                                    Error *error) override {
      sherpa_onnx::OfflineSpeechDenoiserConfig config;
      sherpa_onnx::ParseOptions parser("sherpa-onnx portable bridge");
      config.Register(&parser);
      if (!ApplyOptions(options, &parser, error)) {
        return nullptr;
      }
      if (!config.Validate()) {
        SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
                 "speech-denoiser configuration validation failed: " +
                     config.ToString());
        return nullptr;
      }
      try {
        return std::make_unique<SpeechDenoiserBackend>(
            std::make_unique<sherpa_onnx::OfflineSpeechDenoiser>(config));
      } catch (const std::exception &e) {
        SetErrorFromException(error, e);
        return nullptr;
      }
    }

    std::vector<std::string> Operations() const override {
      return {"set-audio", "denoise", "take-audio"};
    }
  };
  return std::make_unique<Factory>();
}

#if defined(SHERPA_ONNX_BRIDGE_WITH_DIARIZATION)
std::unique_ptr<BackendFactory> CreateSpeakerDiarizationFactory() {
  class Factory final : public BackendFactory {
   public:
    std::unique_ptr<Backend> Create(const Options &options,
                                    Error *error) override {
      sherpa_onnx::OfflineSpeakerDiarizationConfig config;
      sherpa_onnx::ParseOptions parser("sherpa-onnx portable bridge");
      config.Register(&parser);
      if (!ApplyOptions(options, &parser, error)) {
        return nullptr;
      }
      if (!config.Validate()) {
        SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
                 "speaker-diarization configuration validation failed: " +
                     config.ToString());
        return nullptr;
      }
      try {
        return std::make_unique<SpeakerDiarizationBackend>(
            std::make_unique<sherpa_onnx::OfflineSpeakerDiarization>(config));
      } catch (const std::exception &e) {
        SetErrorFromException(error, e);
        return nullptr;
      }
    }

    std::vector<std::string> Operations() const override { return {"process"}; }
  };
  return std::make_unique<Factory>();
}
#endif

}  // namespace sherpa_onnx::bridge
