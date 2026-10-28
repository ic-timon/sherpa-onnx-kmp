// SPDX-License-Identifier: Apache-2.0
#include <deque>
#include <iterator>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "sherpa_common.h"
#include "sherpa-onnx/csrc/parse-options.h"

#if defined(SHERPA_ONNX_ENABLE_TTS) && SHERPA_ONNX_ENABLE_TTS
#include "sherpa-onnx/csrc/offline-tts.h"
#define SHERPA_ONNX_BRIDGE_WITH_TTS 1
#endif

namespace sherpa_onnx::bridge {

#if defined(SHERPA_ONNX_BRIDGE_WITH_TTS)

namespace {

std::unique_ptr<sherpa_onnx::OfflineTts> CreateTts(const Options &options,
                                                   Error *error) {
  sherpa_onnx::OfflineTtsConfig config;
  sherpa_onnx::ParseOptions parser("sherpa-onnx portable bridge");
  config.Register(&parser);

  if (!ApplyOptions(options, &parser, error)) {
    return nullptr;
  }

  if (!config.Validate()) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "tts configuration validation failed: " + config.ToString());
    return nullptr;
  }

  try {
    return std::make_unique<sherpa_onnx::OfflineTts>(config);
  } catch (const std::exception &e) {
    SetErrorFromException(error, e);
    return nullptr;
  }
}

/**
 * TTS produces audio.
 *
 * `synthesize` runs generation to completion and queues every callback chunk,
 * because the C ABI has no way to deliver a callback across a worker boundary.
 * The caller then drains the queue one chunk at a time through `take-chunk`,
 * which is what makes `Flow<AudioChunk>` possible without holding a native
 * callback alive between messages.
 *
 * Generation is intentionally not incremental: a second `synthesize` call while
 * chunks are pending is rejected rather than interleaved, so a caller can never
 * mix two utterances' audio.
 */
class TtsBackend final : public Backend {
 public:
  explicit TtsBackend(std::unique_ptr<sherpa_onnx::OfflineTts> tts)
      : tts_(std::move(tts)) {}

  std::vector<std::string> Operations() const override {
    return {"describe", "synthesize", "take-chunk"};
  }

  std::string Call(const std::string &operation,
                   const std::string &arguments, Error *error) override {
    if (operation == "tts.describe") {
      nlohmann::json answer = {
          {"sampleRate", tts_->SampleRate()},
          {"numSpeakers", tts_->NumSpeakers()},
          {"language", tts_->Lang()},
      };
      return answer.dump();
    }

    if (operation == "tts.synthesize") {
      return Synthesize(arguments, error);
    }

    if (operation == "tts.take-chunk") {
      return PendingSummary();
    }

    SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
             std::string("Unsupported operation: ") + operation);
    return {};
  }

  std::string PullAudio(const std::string &operation,
                        const std::string & /*arguments*/, Audio *audio,
                        Error *error) override {
    if (operation != "tts.take-chunk") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio output operation: ") + operation);
      return {};
    }
    if (pending_.empty()) {
      // An empty pull is a normal end-of-stream signal, not an error: the
      // caller stops when `isLast` arrives with no audio.
      return std::string(
          "{\"sampleRate\":0,\"numSamples\":0,\"progress\":1,"
          "\"isLast\":true}");
    }
    auto chunk = std::move(pending_.front());
    pending_.pop_front();
    audio->sample_rate = chunk.sample_rate;
    audio->samples = std::move(chunk.samples);
    nlohmann::json answer = {
        {"sampleRate", audio->sample_rate},
        {"numSamples", audio->samples.size()},
        {"progress", chunk.progress},
        {"isLast", chunk.is_last && pending_.empty()},
    };
    return answer.dump();
  }

 private:
  struct Chunk {
    std::vector<float> samples;
    int32_t sample_rate = 0;
    float progress = 1.0f;
    bool is_last = false;
  };

  std::string PendingSummary() const {
    const bool empty = pending_.empty();
    nlohmann::json answer = {
        {"sampleRate", empty ? 0 : pending_.front().sample_rate},
        {"numSamples", empty ? 0 : pending_.front().samples.size()},
        {"progress", empty ? 1.0f : pending_.front().progress},
        {"isLast", empty || (pending_.front().is_last && pending_.size() == 1)},
    };
    return answer.dump();
  }

  std::string Synthesize(const std::string &arguments, Error *error) {
    if (!pending_.empty()) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_STATE,
               "A previous synthesis still has pending audio chunks");
      return {};
    }

    nlohmann::json parsed;
    try {
      parsed = nlohmann::json::parse(arguments.empty() ? "{}" : arguments);
    } catch (const std::exception &e) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
               std::string("Invalid synthesize arguments: ") + e.what());
      return {};
    }
    if (!parsed.is_object() || !parsed.contains("text") ||
        !parsed["text"].is_string()) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
               "tts.synthesize requires a string 'text'");
      return {};
    }

    sherpa_onnx::GenerationConfig config;
    if (parsed.contains("speakerId") && parsed["speakerId"].is_number()) {
      config.sid = parsed["speakerId"].get<int32_t>();
    }
    if (parsed.contains("speed") && parsed["speed"].is_number()) {
      config.speed = parsed["speed"].get<float>();
    }
    if (parsed.contains("numSteps") && parsed["numSteps"].is_number()) {
      config.num_steps = parsed["numSteps"].get<int32_t>();
    }
    // sherpa-onnx v2.0.0 removed the built-in espeak-ng phonemizer. A Piper,
    // Coqui, icefall, or Inflect model without a lexicon therefore needs its
    // phonemes supplied here, or generation returns no audio at all.
    if (parsed.contains("phonemeCodepoints")) {
      if (!parsed["phonemeCodepoints"].is_array()) {
        SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
                 "'phonemeCodepoints' must be an array of integer arrays");
        return {};
      }
      for (const auto &sentence : parsed["phonemeCodepoints"]) {
        if (!sentence.is_array()) {
          SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
                   "'phonemeCodepoints' must be an array of integer arrays");
          return {};
        }
        std::vector<int32_t> codepoints;
        codepoints.reserve(sentence.size());
        for (const auto &value : sentence) {
          if (!value.is_number_integer()) {
            SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
                     "'phonemeCodepoints' entries must be integers");
            return {};
          }
          codepoints.push_back(value.get<int32_t>());
        }
        config.phoneme_codepoints.push_back(std::move(codepoints));
      }
    }

    try {
      auto callback = [this](const float *samples, int32_t n, float progress) {
        Chunk chunk;
        chunk.sample_rate = tts_->SampleRate();
        chunk.progress = progress;
        if (samples != nullptr && n > 0) {
          chunk.samples.assign(samples, samples + n);
        }
        pending_.push_back(std::move(chunk));
        ++delivered_chunks;
        return 1;
      };

      delivered_chunks = 0;
      auto generated =
          tts_->Generate(parsed["text"].get<std::string>(), config, callback);

      // Upstream's return value is the concatenation of every chunk it already
      // handed to the callback, so appending it would duplicate the audio. It
      // is used only when no callback fired, which happens for an empty
      // utterance or a model that returns without invoking the callback.
      if (delivered_chunks == 0 && !generated.samples.empty()) {
        Chunk chunk;
        chunk.sample_rate = generated.sample_rate;
        chunk.samples = std::move(generated.samples);
        chunk.progress = 1.0f;
        pending_.push_back(std::move(chunk));
      }
      // Drop any zero-length chunk so `isLast` never lands on an empty pull.
      for (auto it = pending_.begin(); it != pending_.end();) {
        it = it->samples.empty() ? pending_.erase(it) : std::next(it);
      }
      if (pending_.empty()) {
        SetError(error, SHERPA_ONNX_BRIDGE_RUNTIME_ERROR,
                 "TTS generated no audio for the given text. For a Piper, "
                 "Coqui, icefall, or Inflect VITS model the phonemes must come "
                 "from the caller (GenerationConfig.phoneme_codepoints) or from "
                 "a lexicon, because sherpa-onnx v2.0.0 removed the built-in "
                 "espeak-ng phonemizer.");
        return {};
      }
      pending_.back().is_last = true;
      pending_.back().progress = 1.0f;

      nlohmann::json answer = {
          {"schemaVersion", 1},
          {"sampleRate", tts_->SampleRate()},
          {"numSpeakers", tts_->NumSpeakers()},
          {"numChunks", pending_.size()},
      };
      return answer.dump();
    } catch (const std::exception &e) {
      pending_.clear();
      SetErrorFromException(error, e);
      return {};
    }
  }

  std::unique_ptr<sherpa_onnx::OfflineTts> tts_;
  std::deque<Chunk> pending_;
  int32_t delivered_chunks = 0;
};

}  // namespace

std::unique_ptr<BackendFactory> CreateTtsFactory() {
  class Factory final : public BackendFactory {
   public:
    std::unique_ptr<Backend> Create(const Options &options,
                                    Error *error) override {
      auto tts = CreateTts(options, error);
      if (!tts) {
        return nullptr;
      }
      return std::make_unique<TtsBackend>(std::move(tts));
    }

    std::vector<std::string> Operations() const override {
      return {"describe", "synthesize", "take-chunk"};
    }
  };
  return std::make_unique<Factory>();
}

#else

std::unique_ptr<BackendFactory> CreateTtsFactory() { return nullptr; }

#endif  // SHERPA_ONNX_BRIDGE_WITH_TTS

}  // namespace sherpa_onnx::bridge
