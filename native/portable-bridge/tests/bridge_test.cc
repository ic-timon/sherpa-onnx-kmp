// SPDX-License-Identifier: Apache-2.0
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "bridge_internal.h"
#include "nlohmann/json.hpp"
#include "sherpa-onnx-portable-bridge.h"

namespace {

using sherpa_onnx::bridge::Audio;
using sherpa_onnx::bridge::Backend;
using sherpa_onnx::bridge::BackendFactory;
using sherpa_onnx::bridge::Error;
using sherpa_onnx::bridge::Options;
using sherpa_onnx::bridge::SetError;

#define REQUIRE(condition)                                      \
  do {                                                          \
    if (!(condition)) {                                         \
      fprintf(stderr, "Requirement failed: %s\n", #condition);  \
      return 1;                                                 \
    }                                                           \
  } while (0)

void FreeError(char *error) { SherpaOnnxBridgeFree(error); }

/** Echoes the accumulated sample count so tests can prove audio was copied. */
class EchoBackend final : public Backend {
 public:
  explicit EchoBackend(bool accept_audio = true) : accept_audio_(accept_audio) {}

  std::vector<std::string> Operations() const override {
    return {"transcribe", "describe"};
  }

  std::string Call(const std::string &operation,
                   const std::string &arguments, Error *error) override {
    if (operation == "echo.describe") {
      return nlohmann::json{{"count", count_}}.dump();
    }
    if (operation == "echo.transcribe") {
      if (count_ == 0) {
        SetError(error, SHERPA_ONNX_BRIDGE_INVALID_STATE,
                 "No audio has been pushed to this handle");
        return {};
      }
      auto answer = nlohmann::json{{"text", "echo"},
                                   {"count", count_},
                                   {"arguments", arguments}};
      count_ = 0;
      return answer.dump();
    }
    if (operation == "echo.spawn") {
      const uint64_t child =
          RegisterChild("echo-child", std::make_unique<EchoBackend>());
      if (child == 0) {
        SetError(error, SHERPA_ONNX_BRIDGE_INVALID_STATE,
                 "child could not be registered");
        return {};
      }
      return nlohmann::json{{"handle", child}}.dump();
    }
    if (operation == "echo.fail") {
      SetError(error, SHERPA_ONNX_BRIDGE_RUNTIME_ERROR, "deliberate failure");
      return {};
    }
    SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
             std::string("Unsupported operation: ") + operation);
    return {};
  }

  void PushAudio(const std::string &operation, const Audio &audio,
                 Error *error) override {
    if (!accept_audio_) {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED, "audio not accepted");
      return;
    }
    if (operation != "echo.set-audio") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio operation: ") + operation);
      return;
    }
    count_ += static_cast<int32_t>(audio.samples.size());
  }

  std::string PullAudio(const std::string &operation,
                        const std::string & /*arguments*/, Audio *audio,
                        Error *error) override {
    if (operation != "echo.take-audio") {
      SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported audio operation: ") + operation);
      return {};
    }
    audio->sample_rate = 16'000;
    audio->samples = {0.25f, 0.5f, 0.75f};
    return nlohmann::json{{"channels", 1}}.dump();
  }

 private:
  bool accept_audio_;
  int32_t count_ = 0;
};

class EchoFactory final : public BackendFactory {
 public:
  explicit EchoFactory(bool accept_audio = true) : accept_audio_(accept_audio) {}

  std::unique_ptr<Backend> Create(const Options &options,
                                  Error *error) override {
    for (const auto &[key, value] : options) {
      if (key == "unknown-option") {
        SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
                 "unknown option: " + value);
        return nullptr;
      }
    }
    if (options.empty()) {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT, "options are empty");
      return nullptr;
    }
    return std::make_unique<EchoBackend>(accept_audio_);
  }

  std::vector<std::string> Operations() const override {
    return {"transcribe", "describe", "spawn", "fail"};
  }

 private:
  bool accept_audio_;
};

int CheckCapabilityReport() {
  char *info = SherpaOnnxBridgeInfoJson();
  REQUIRE(info != nullptr);
  auto parsed = nlohmann::json::parse(info);
  SherpaOnnxBridgeFree(info);

  REQUIRE(parsed["abiVersion"] == SHERPA_ONNX_BRIDGE_ABI_VERSION);
  REQUIRE(parsed["schemaVersion"] == 1);

  const auto &resources = parsed["resources"];
  REQUIRE(resources.is_array());
  auto has = [&resources](const char *kind) {
    for (const auto &item : resources) {
      if (item == kind) return true;
    }
    return false;
  };
  REQUIRE(has("echo"));

  const auto &operations = parsed["operations"];
  auto has_op = [&operations](const std::string &name) {
    for (const auto &item : operations) {
      if (item == name) return true;
    }
    return false;
  };
  REQUIRE(has_op("health"));
  REQUIRE(has_op("echo.transcribe"));
  REQUIRE(has_op("echo.spawn"));
  return 0;
}

int CheckCreateRejects() {
  uint64_t handle = 0;
  char *error = nullptr;

  REQUIRE(SherpaOnnxBridgeCreate("echo", "{}", &handle, &error) ==
          SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT);
  REQUIRE(handle == 0);
  REQUIRE(error != nullptr);
  FreeError(error);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("echo", R"({"unknown-option":"x"})", &handle,
                                 &error) == SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT);
  REQUIRE(handle == 0);
  REQUIRE(error != nullptr);
  FreeError(error);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("missing-kind", R"({"tokens":"x"})", &handle,
                                 &error) == SHERPA_ONNX_BRIDGE_UNSUPPORTED);
  REQUIRE(handle == 0);
  REQUIRE(error != nullptr);
  FreeError(error);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("echo", "not json", &handle, &error) ==
          SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT);
  REQUIRE(error != nullptr);
  FreeError(error);
  return 0;
}

int CheckTranscribeFlow() {
  uint64_t handle = 0;
  char *error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("echo", R"({"tokens":"x"})", &handle,
                                 &error) == SHERPA_ONNX_BRIDGE_OK);
  REQUIRE(handle != 0);
  REQUIRE(error == nullptr);

  const std::vector<float> audio = {0.1f, 0.2f, 0.3f};
  error = nullptr;
  REQUIRE(SherpaOnnxBridgePushAudio(handle, "echo.set-audio", 16000,
                                    audio.data(),
                                    static_cast<int32_t>(audio.size()),
                                    &error) == SHERPA_ONNX_BRIDGE_OK);
  REQUIRE(error == nullptr);

  char *result = nullptr;
  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(handle, "echo.transcribe", R"({"argv":1})",
                               &result, &error) == SHERPA_ONNX_BRIDGE_OK);
  REQUIRE(error == nullptr);
  REQUIRE(result != nullptr);
  auto parsed = nlohmann::json::parse(result);
  REQUIRE(parsed["text"] == "echo");
  REQUIRE(parsed["count"] == 3);
  REQUIRE(parsed["arguments"] == R"({"argv":1})");
  SherpaOnnxBridgeFree(result);

  // The buffer is consumed only after a successful transcription.
  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(handle, "echo.transcribe", "{}", &result,
                               &error) == SHERPA_ONNX_BRIDGE_INVALID_STATE);
  REQUIRE(error != nullptr);
  FreeError(error);

  SherpaOnnxBridgeDestroy(handle);
  return 0;
}

int CheckHealthAndErrors() {
  uint64_t handle = 0;
  char *error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("echo", R"({"tokens":"x"})", &handle,
                                 &error) == SHERPA_ONNX_BRIDGE_OK);

  char *result = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(handle, "health", "{}", &result, &error) ==
          SHERPA_ONNX_BRIDGE_OK);
  REQUIRE(result != nullptr);
  SherpaOnnxBridgeFree(result);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(handle, "echo.unknown", "{}", &result, &error) ==
          SHERPA_ONNX_BRIDGE_UNSUPPORTED);
  REQUIRE(error != nullptr);
  FreeError(error);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(handle, "echo.fail", "{}", &result, &error) ==
          SHERPA_ONNX_BRIDGE_RUNTIME_ERROR);
  REQUIRE(error != nullptr);
  FreeError(error);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(handle, nullptr, "{}", &result, &error) ==
          SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT);
  FreeError(error);

  SherpaOnnxBridgeDestroy(handle);
  return 0;
}

int CheckPullAudio() {
  uint64_t handle = 0;
  char *error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("echo", R"({"tokens":"x"})", &handle,
                                 &error) == SHERPA_ONNX_BRIDGE_OK);

  int32_t sample_rate = 0;
  float *samples = nullptr;
  int32_t num_samples = 0;
  char *result = nullptr;
  error = nullptr;
  REQUIRE(SherpaOnnxBridgePullAudio(handle, "echo.take-audio", "{}",
                                    &sample_rate, &samples, &num_samples,
                                    &result, &error) == SHERPA_ONNX_BRIDGE_OK);
  REQUIRE(error == nullptr);
  REQUIRE(sample_rate == 16'000);
  REQUIRE(num_samples == 3);
  REQUIRE(samples != nullptr);
  REQUIRE(samples[1] == 0.5f);
  REQUIRE(result != nullptr);
  auto parsed = nlohmann::json::parse(result);
  REQUIRE(parsed["channels"] == 1);
  SherpaOnnxBridgeFree(result);
  SherpaOnnxBridgeFreeAudio(samples);

  // A rejected pull must not leak a buffer.
  samples = nullptr;
  num_samples = 7;
  error = nullptr;
  REQUIRE(SherpaOnnxBridgePullAudio(handle, "echo.nope", "{}", &sample_rate,
                                    &samples, &num_samples, &result, &error) ==
          SHERPA_ONNX_BRIDGE_UNSUPPORTED);
  REQUIRE(samples == nullptr);
  REQUIRE(num_samples == 0);
  FreeError(error);

  SherpaOnnxBridgeDestroy(handle);
  return 0;
}

int CheckChildOwnership() {
  uint64_t parent = 0;
  char *error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("echo", R"({"tokens":"x"})", &parent,
                                 &error) == SHERPA_ONNX_BRIDGE_OK);

  char *result = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(parent, "echo.spawn", "{}", &result, &error) ==
          SHERPA_ONNX_BRIDGE_OK);
  REQUIRE(result != nullptr);
  const uint64_t child =
      nlohmann::json::parse(result)["handle"].get<uint64_t>();
  SherpaOnnxBridgeFree(result);
  REQUIRE(child != 0);

  // A child is a first-class handle.
  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(child, "health", "{}", &result, &error) ==
          SHERPA_ONNX_BRIDGE_OK);
  SherpaOnnxBridgeFree(result);

  // Destroying the parent cascades to its children.
  SherpaOnnxBridgeDestroy(parent);
  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(child, "health", "{}", &result, &error) ==
          SHERPA_ONNX_BRIDGE_INVALID_HANDLE);
  FreeError(error);

  // Destroying an already destroyed handle is a no-op.
  SherpaOnnxBridgeDestroy(parent);
  SherpaOnnxBridgeDestroy(child);
  SherpaOnnxBridgeDestroy(0);
  return 0;
}

int CheckAudioRejection() {
  uint64_t handle = 0;
  char *error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("echo", R"({"tokens":"x"})", &handle,
                                 &error) == SHERPA_ONNX_BRIDGE_OK);

  const std::vector<float> audio = {0.5f};
  error = nullptr;
  REQUIRE(SherpaOnnxBridgePushAudio(handle, "echo.wrong", 16000, audio.data(),
                                    1, &error) ==
          SHERPA_ONNX_BRIDGE_UNSUPPORTED);
  FreeError(error);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgePushAudio(handle, "echo.set-audio", 0, audio.data(),
                                    1, &error) ==
          SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT);
  FreeError(error);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgePushAudio(handle, "echo.set-audio", 16000, nullptr,
                                    1, &error) ==
          SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT);
  FreeError(error);

  SherpaOnnxBridgeDestroy(handle);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgePushAudio(handle, "echo.set-audio", 16000,
                                    audio.data(), 1, &error) ==
          SHERPA_ONNX_BRIDGE_INVALID_HANDLE);
  FreeError(error);
  return 0;
}

}  // namespace

int main() {
  REQUIRE(SherpaOnnxBridgeAbiVersion() == SHERPA_ONNX_BRIDGE_ABI_VERSION);

  // The test binary has no sherpa-onnx backend, so every kind is a double.
  sherpa_onnx::bridge::SetFactoryForTesting(
      "echo", std::make_unique<EchoFactory>());

  if (CheckCapabilityReport() != 0) return 1;
  if (CheckCreateRejects() != 0) return 1;
  if (CheckTranscribeFlow() != 0) return 1;
  if (CheckHealthAndErrors() != 0) return 1;
  if (CheckPullAudio() != 0) return 1;
  if (CheckChildOwnership() != 0) return 1;
  if (CheckAudioRejection() != 0) return 1;

  sherpa_onnx::bridge::SetFactoryForTesting("echo", nullptr);
  printf("portable bridge tests: OK\n");
  return 0;
}
