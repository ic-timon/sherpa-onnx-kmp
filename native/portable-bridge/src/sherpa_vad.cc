// SPDX-License-Identifier: Apache-2.0
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "sherpa_common.h"
#include "sherpa-onnx/csrc/parse-options.h"
#include "sherpa-onnx/csrc/vad-model-config.h"
#include "sherpa-onnx/csrc/voice-activity-detector.h"

namespace sherpa_onnx::bridge {
namespace {

std::unique_ptr<sherpa_onnx::VoiceActivityDetector> CreateDetector(
    const Options &options, Error *error) {
  sherpa_onnx::VadModelConfig config;
  sherpa_onnx::ParseOptions parser("sherpa-onnx portable bridge");
  config.Register(&parser);

  if (!ApplyOptions(options, &parser, error)) {
    return nullptr;
  }

  if (!config.Validate()) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "vad configuration validation failed: " + config.ToString());
    return nullptr;
  }

  try {
    return std::make_unique<sherpa_onnx::VoiceActivityDetector>(config);
  } catch (const std::exception &e) {
    SetErrorFromException(error, e);
    return nullptr;
  }
}

/**
 * VAD is a pull endpoint.
 *
 * `accept-waveform` only consumes samples: the detector decides internally when
 * a segment ends, so the ready segments are read afterwards. `take-audio` pops
 * one segment and returns its samples in the same call, which lets a caller
 * drain a batch of short segments without a second round trip per segment. A
 * JSON-only probe remains available through `empty`/`front`.
 */
class VadBackend final : public Backend {
 public:
  explicit VadBackend(std::unique_ptr<sherpa_onnx::VoiceActivityDetector> vad)
      : vad_(std::move(vad)) {}

  static std::vector<std::string> OperationList() {
    return {"accept-waveform", "flush", "empty", "front",   "front-audio",
            "take-audio",      "pop",   "clear", "reset",   "is-speech-detected",
            "current-segment", "current-segment-audio"};
  }

  std::vector<std::string> Operations() const override {
    return OperationList();
  }

  std::string Call(const std::string &operation,
                   const std::string & /*arguments*/,
                   Error *error) override {
    try {
      if (operation == "vad.flush") {
        vad_->Flush();
        return StatusJson();
      }
      if (operation == "vad.empty") {
        return StatusJson();
      }
      if (operation == "vad.front") {
        // Metadata only. A caller that wants the samples uses front-audio so
        // the waveform never has to travel through JSON.
        if (vad_->Empty()) {
          return std::string("{\"empty\":true,\"startSample\":-1,"
                             "\"numSamples\":0,\"isSpeechDetected\":") +
                 (vad_->IsSpeechDetected() ? "true" : "false") + "}";
        }
        auto segment = SpeechSegmentJson(vad_->Front());
        segment["empty"] = false;
        segment["isSpeechDetected"] = vad_->IsSpeechDetected();
        return segment.dump();
      }
      if (operation == "vad.pop") {
        if (!vad_->Empty()) {
          vad_->Pop();
        }
        return StatusJson();
      }
      if (operation == "vad.clear") {
        vad_->Clear();
        return StatusJson();
      }
      if (operation == "vad.reset") {
        vad_->Reset();
        return StatusJson();
      }
      if (operation == "vad.is-speech-detected") {
        return std::string("{\"isSpeechDetected\":") +
               (vad_->IsSpeechDetected() ? "true" : "false") + "}";
      }
      if (operation == "vad.current-segment") {
        auto segment = vad_->CurrentSpeechSegment();
        if (segment.start < 0) {
          return std::string("{\"startSample\":-1,\"numSamples\":0}");
        }
        return SpeechSegmentJson(segment).dump();
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
    if (operation != "vad.accept-waveform") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio operation: ") + operation);
      return;
    }
    if (!ValidateAudio(audio, error)) {
      return;
    }
    if (audio.sample_rate != vad_->GetConfig().sample_rate) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
               "VAD expects " + std::to_string(vad_->GetConfig().sample_rate) +
                   " Hz audio, got " + std::to_string(audio.sample_rate));
      return;
    }
    try {
      vad_->AcceptWaveform(audio.samples.data(),
                           static_cast<int32_t>(audio.samples.size()));
    } catch (const std::exception &e) {
      SetErrorFromException(error, e);
    }
  }

  std::string PullAudio(const std::string &operation,
                        const std::string & /*arguments*/, Audio *audio,
                        Error *error) override {
    if (operation != "vad.front-audio" &&
        operation != "vad.take-audio" &&
        operation != "vad.current-segment-audio") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio output operation: ") + operation);
      return {};
    }
    try {
      if (operation == "vad.current-segment-audio") {
        auto segment = vad_->CurrentSpeechSegment();
        if (segment.start < 0) {
          return std::string("{\"startSample\":-1,\"numSamples\":0}");
        }
        audio->sample_rate = vad_->GetConfig().sample_rate;
        audio->samples = segment.samples;
        return SpeechSegmentJson(segment).dump();
      }

      // `front-audio` peeks and `take-audio` also pops, so a caller loops on
      // take-audio until it receives no audio.
      if (vad_->Empty()) {
        return std::string(
            "{\"empty\":true,\"startSample\":-1,\"numSamples\":0}");
      }
      const auto &segment = vad_->Front();
      audio->sample_rate = vad_->GetConfig().sample_rate;
      audio->samples = segment.samples;
      auto answer = SpeechSegmentJson(segment);
      answer["empty"] = false;
      if (operation == "vad.take-audio") {
        vad_->Pop();
        answer["remaining"] = !vad_->Empty();
      }
      return answer.dump();
    } catch (const std::exception &e) {
      SetErrorFromException(error, e);
      return {};
    }
  }

 private:
  std::string StatusJson() const {
    nlohmann::json answer = {
        {"empty", vad_->Empty()},
        {"isSpeechDetected", vad_->IsSpeechDetected()},
    };
    if (!vad_->Empty()) {
      answer["startSample"] = vad_->Front().start;
      answer["numSamples"] = vad_->Front().samples.size();
    }
    return answer.dump();
  }

  std::unique_ptr<sherpa_onnx::VoiceActivityDetector> vad_;
};

}  // namespace

std::unique_ptr<BackendFactory> CreateVadFactory() {
  class Factory final : public BackendFactory {
   public:
    std::unique_ptr<Backend> Create(const Options &options,
                                    Error *error) override {
      auto vad = CreateDetector(options, error);
      if (!vad) {
        return nullptr;
      }
      return std::make_unique<VadBackend>(std::move(vad));
    }

    std::vector<std::string> Operations() const override {
      return VadBackend::OperationList();
    }
  };
  return std::make_unique<Factory>();
}

}  // namespace sherpa_onnx::bridge
