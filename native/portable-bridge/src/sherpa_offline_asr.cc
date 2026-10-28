// SPDX-License-Identifier: Apache-2.0
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "sherpa_common.h"
#include "sherpa-onnx/csrc/offline-recognizer.h"
#include "sherpa-onnx/csrc/parse-options.h"

namespace sherpa_onnx::bridge {
namespace {

std::unique_ptr<sherpa_onnx::OfflineRecognizer> CreateRecognizer(
    const Options &options, Error *error) {
  sherpa_onnx::OfflineRecognizerConfig config;
  sherpa_onnx::ParseOptions parser("sherpa-onnx portable bridge");
  config.Register(&parser);

  if (!ApplyOptions(options, &parser, error)) {
    return nullptr;
  }

  if (!config.Validate()) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "offline-asr configuration validation failed: " +
                 config.ToString());
    return nullptr;
  }

  try {
    return std::make_unique<sherpa_onnx::OfflineRecognizer>(config);
  } catch (const std::exception &e) {
    SetErrorFromException(error, e);
    return nullptr;
  }
}

class SherpaOfflineAsrBackend final : public Backend {
 public:
  explicit SherpaOfflineAsrBackend(
      std::unique_ptr<sherpa_onnx::OfflineRecognizer> recognizer)
      : recognizer_(std::move(recognizer)) {}

  std::vector<std::string> Operations() const override {
    return {"set-audio", "transcribe"};
  }

  std::string Call(const std::string &operation,
                   const std::string & /*arguments*/,
                   Error *error) override {
    if (operation != "offline-asr.transcribe") {
      if (operation == "offline-asr.set-audio") {
        SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
                 "Use SherpaOnnxBridgePushAudio for offline-asr.set-audio");
        return {};
      }
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
      std::unique_ptr<sherpa_onnx::OfflineStream> stream =
          recognizer_->CreateStream();
      stream->AcceptWaveform(pending_.sample_rate, pending_.samples.data(),
                             static_cast<int32_t>(pending_.samples.size()));
      recognizer_->DecodeStream(stream.get());
      std::string result = OfflineResultJson(stream->GetResult());
      pending_ = {};
      has_audio_ = false;
      return result;
    } catch (const std::exception &e) {
      SetErrorFromException(error, e);
      return {};
    }
  }

  void PushAudio(const std::string &operation, const Audio &audio,
                 Error *error) override {
    if (operation != "offline-asr.set-audio") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio operation: ") + operation);
      return;
    }
    if (has_audio_ && pending_.sample_rate != audio.sample_rate) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
               "All audio pushed for one transcription must use the same "
               "sample rate");
      return;
    }
    pending_.sample_rate = audio.sample_rate;
    pending_.samples.insert(pending_.samples.end(), audio.samples.begin(),
                            audio.samples.end());
    has_audio_ = true;
  }

 private:
  std::unique_ptr<sherpa_onnx::OfflineRecognizer> recognizer_;
  Audio pending_;
  bool has_audio_ = false;
};

}  // namespace

std::unique_ptr<BackendFactory> CreateOfflineAsrFactory() {
  class Factory final : public BackendFactory {
   public:
    std::unique_ptr<Backend> Create(const Options &options,
                                    Error *error) override {
      auto recognizer = CreateRecognizer(options, error);
      if (!recognizer) {
        return nullptr;
      }
      return std::make_unique<SherpaOfflineAsrBackend>(std::move(recognizer));
    }

    std::vector<std::string> Operations() const override {
      return {"set-audio", "transcribe"};
    }
  };
  return std::make_unique<Factory>();
}

}  // namespace sherpa_onnx::bridge
