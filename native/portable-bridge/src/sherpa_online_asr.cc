// SPDX-License-Identifier: Apache-2.0
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "sherpa_common.h"
#include "sherpa-onnx/csrc/online-recognizer.h"
#include "sherpa-onnx/csrc/parse-options.h"

namespace sherpa_onnx::bridge {
namespace {

/**
 * Recognition state shared by an `online-asr` handle and its streams.
 *
 * Streams are registered as child handles, so they outlive the call that
 * created them while the recognizer stays owned in one place. Access is
 * serialized by [mutex] because a stream and its recognizer are separate
 * handles with separate bridge locks.
 */
struct OnlineRecognizerState {
  std::mutex mutex;
  std::unique_ptr<sherpa_onnx::OnlineRecognizer> recognizer;
};

std::shared_ptr<OnlineRecognizerState> CreateRecognizer(const Options &options,
                                                        Error *error) {
  sherpa_onnx::OnlineRecognizerConfig config;
  sherpa_onnx::ParseOptions parser("sherpa-onnx portable bridge");
  config.Register(&parser);

  if (!ApplyOptions(options, &parser, error)) {
    return nullptr;
  }

  if (!config.Validate()) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "online-asr configuration validation failed: " + config.ToString());
    return nullptr;
  }

  try {
    auto state = std::make_shared<OnlineRecognizerState>();
    state->recognizer =
        std::make_unique<sherpa_onnx::OnlineRecognizer>(config);
    return state;
  } catch (const std::exception &e) {
    SetErrorFromException(error, e);
    return nullptr;
  }
}

std::string HotwordsArgument(const std::string &arguments) {
  if (arguments.empty()) {
    return {};
  }
  try {
    auto parsed = nlohmann::json::parse(arguments);
    if (parsed.is_object() && parsed.contains("hotwords") &&
        parsed["hotwords"].is_string()) {
      return parsed["hotwords"].get<std::string>();
    }
  } catch (const std::exception &) {
    // Fall through: a malformed argument is not worth failing a stream
    // creation over, and treating it as "no hotwords" matches CreateStream().
  }
  return {};
}

/** Decodes every frame the recognizer is ready for. */
void DecodeAll(const sherpa_onnx::OnlineRecognizer &recognizer,
               sherpa_onnx::OnlineStream *stream) {
  while (recognizer.IsReady(stream)) {
    recognizer.DecodeStream(stream);
  }
}

class OnlineSessionBackend final : public Backend {
 public:
  OnlineSessionBackend(std::shared_ptr<OnlineRecognizerState> state,
                       std::unique_ptr<sherpa_onnx::OnlineStream> stream)
      : state_(std::move(state)), stream_(std::move(stream)) {}

  std::vector<std::string> Operations() const override {
    return {"push-audio", "decode", "result", "reset", "finish",
            "is-endpoint"};
  }

  std::string Call(const std::string &operation,
                   const std::string & /*arguments*/,
                   Error *error) override {
    if (!state_ || !stream_) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_STATE,
               "Stream has been released");
      return {};
    }

    std::lock_guard<std::mutex> lock(state_->mutex);
    auto &recognizer = *state_->recognizer;

    try {
      if (operation == "online-session.decode") {
        DecodeAll(recognizer, stream_.get());
        return UpdateJson(recognizer);
      }
      if (operation == "online-session.result") {
        return UpdateJson(recognizer);
      }
      if (operation == "online-session.finish") {
        stream_->InputFinished();
        DecodeAll(recognizer, stream_.get());
        auto result = recognizer.GetResult(stream_.get());
        result.is_final = true;
        result.is_eof = true;
        return OnlineResultJson(result);
      }
      if (operation == "online-session.reset") {
        recognizer.Reset(stream_.get());
        return SuccessJson();
      }
      if (operation == "online-session.is-endpoint") {
        return std::string("{\"isEndpoint\":") +
               (recognizer.IsEndpoint(stream_.get()) ? "true" : "false") + "}";
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
    if (operation != "online-session.push-audio") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio operation: ") + operation);
      return;
    }
    if (!state_ || !stream_) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_STATE,
               "Stream has been released");
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
  std::string SuccessJson() const { return "{\"ok\":true}"; }

  std::string UpdateJson(const sherpa_onnx::OnlineRecognizer &recognizer) {
    auto result = recognizer.GetResult(stream_.get());
    const bool is_endpoint = recognizer.IsEndpoint(stream_.get());
    result.is_final = is_endpoint;
    std::string json = OnlineResultJson(result);
    // `isEndpoint` is a bridge-level concept (the recognizer reports it through
    // a separate call), so it is added to the stable envelope rather than
    // inferred by the caller from `isFinal`.
    auto parsed = nlohmann::json::parse(json);
    parsed["isEndpoint"] = is_endpoint;
    return parsed.dump();
  }

  std::shared_ptr<OnlineRecognizerState> state_;
  std::unique_ptr<sherpa_onnx::OnlineStream> stream_;
};

class OnlineAsrBackend final : public Backend {
 public:
  explicit OnlineAsrBackend(std::shared_ptr<OnlineRecognizerState> state)
      : state_(std::move(state)) {}

  std::vector<std::string> Operations() const override {
    return {"create-stream"};
  }

  std::string Call(const std::string &operation,
                   const std::string &arguments, Error *error) override {
    if (operation != "online-asr.create-stream") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported operation: ") + operation);
      return {};
    }

    try {
      std::unique_ptr<sherpa_onnx::OnlineStream> stream;
      {
        std::lock_guard<std::mutex> lock(state_->mutex);
        const std::string hotwords = HotwordsArgument(arguments);
        stream = hotwords.empty()
                     ? state_->recognizer->CreateStream()
                     : state_->recognizer->CreateStream(hotwords);
      }
      if (!stream) {
        SetError(error, SHERPA_ONNX_BRIDGE_RUNTIME_ERROR,
                 "online-asr did not create a stream");
        return {};
      }
      const uint64_t child =
          RegisterChild("online-session",
                        std::make_unique<OnlineSessionBackend>(
                            state_, std::move(stream)));
      if (child == 0) {
        SetError(error, SHERPA_ONNX_BRIDGE_INVALID_STATE,
                 "online-session could not be registered");
        return {};
      }
      nlohmann::json answer = {{"handle", child}};
      return answer.dump();
    } catch (const std::exception &e) {
      SetErrorFromException(error, e);
      return {};
    }
  }

 private:
  std::shared_ptr<OnlineRecognizerState> state_;
};

}  // namespace

std::unique_ptr<BackendFactory> CreateOnlineAsrFactory() {
  class Factory final : public BackendFactory {
   public:
    std::unique_ptr<Backend> Create(const Options &options,
                                    Error *error) override {
      auto state = CreateRecognizer(options, error);
      if (!state) {
        return nullptr;
      }
      return std::make_unique<OnlineAsrBackend>(std::move(state));
    }

    std::vector<std::string> Operations() const override {
      return {"create-stream"};
    }

    std::vector<std::pair<std::string, std::vector<std::string>>>
    ChildOperations() const override {
      return {{"online-session",
               {"push-audio", "decode", "result", "reset", "finish",
                "is-endpoint"}}};
    }
  };
  return std::make_unique<Factory>();
}

}  // namespace sherpa_onnx::bridge
