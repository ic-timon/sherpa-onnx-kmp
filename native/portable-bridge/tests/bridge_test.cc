// SPDX-License-Identifier: Apache-2.0
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include "bridge_internal.h"
#include "nlohmann/json.hpp"
#include "sherpa-onnx-portable-bridge.h"

namespace {

class FakeOfflineAsrBackend final
    : public sherpa_onnx::bridge::OfflineAsrBackend {
 public:
  std::string Transcribe(const sherpa_onnx::bridge::Audio &audio,
                         sherpa_onnx::bridge::Error *error) override {
    if (audio.samples.empty()) {
      sherpa_onnx::bridge::SetError(
          error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT, "empty audio");
      return {};
    }
    const nlohmann::json result = {
        {"schemaVersion", 1},
        {"text", "fake transcript"},
        {"tokens", nlohmann::json::array()},
        {"segments", nlohmann::json::array()},
        {"rawJson", "{}"},
    };
    return result.dump();
  }
};

class FakeFactory final
    : public sherpa_onnx::bridge::OfflineAsrBackendFactory {
 public:
  std::unique_ptr<sherpa_onnx::bridge::OfflineAsrBackend> Create(
      const sherpa_onnx::bridge::Options &options,
      sherpa_onnx::bridge::Error *error) override {
    if (options.empty()) {
      sherpa_onnx::bridge::SetError(
          error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT, "options are empty");
      return nullptr;
    }
    for (const auto &[key, value] : options) {
      if (key == "unknown-option") {
        sherpa_onnx::bridge::SetError(
            error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT, "unknown option");
        return nullptr;
      }
    }
    return std::make_unique<FakeOfflineAsrBackend>();
  }
};

#define REQUIRE(condition)                                      \
  do {                                                          \
    if (!(condition)) {                                         \
      fprintf(stderr, "Requirement failed: %s\n", #condition); \
      return 1;                                                 \
    }                                                           \
  } while (0)

void FreeError(char *error) { SherpaOnnxBridgeFree(error); }

}  // namespace

int main() {
  REQUIRE(SherpaOnnxBridgeAbiVersion() == SHERPA_ONNX_BRIDGE_ABI_VERSION);

  char *info = SherpaOnnxBridgeInfoJson();
  REQUIRE(info != nullptr);
  auto info_json = nlohmann::json::parse(info);
  REQUIRE(info_json["abiVersion"] == SHERPA_ONNX_BRIDGE_ABI_VERSION);
  SherpaOnnxBridgeFree(info);

  sherpa_onnx::bridge::SetOfflineAsrFactoryForTesting(
      std::make_unique<FakeFactory>());

  uint64_t handle = 0;
  char *error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("offline-asr", "{}", &handle, &error) ==
          SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT);
  REQUIRE(handle == 0);
  REQUIRE(error != nullptr);
  FreeError(error);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("offline-asr",
                                 R"({"unknown-option":"x"})", &handle,
                                 &error) == SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT);
  REQUIRE(handle == 0);
  REQUIRE(error != nullptr);
  FreeError(error);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("invalid-kind", R"({"tokens":"x"})", &handle,
                                 &error) == SHERPA_ONNX_BRIDGE_UNSUPPORTED);
  REQUIRE(error != nullptr);
  FreeError(error);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCreate("offline-asr", R"({"tokens":"x"})", &handle,
                                 &error) == SHERPA_ONNX_BRIDGE_OK);
  REQUIRE(handle != 0);
  REQUIRE(error == nullptr);

  const std::vector<float> audio = {0.1f, 0.2f, 0.3f};
  error = nullptr;
  REQUIRE(SherpaOnnxBridgePushAudio(handle, "offline-asr.set-audio", 16000,
                                    audio.data(),
                                    static_cast<int32_t>(audio.size()),
                                    &error) == SHERPA_ONNX_BRIDGE_OK);
  REQUIRE(error == nullptr);

  char *result_json = nullptr;
  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(handle, "offline-asr.transcribe", "{}",
                               &result_json, &error) == SHERPA_ONNX_BRIDGE_OK);
  REQUIRE(error == nullptr);
  REQUIRE(result_json != nullptr);
  auto result = nlohmann::json::parse(result_json);
  REQUIRE(result["text"] == "fake transcript");
  FreeError(result_json);

  error = nullptr;
  REQUIRE(SherpaOnnxBridgeCall(handle, "offline-asr.transcribe", "{}",
                               &result_json, &error) ==
          SHERPA_ONNX_BRIDGE_INVALID_STATE);
  REQUIRE(error != nullptr);
  FreeError(error);

  SherpaOnnxBridgeDestroy(handle);
  error = nullptr;
  REQUIRE(SherpaOnnxBridgePushAudio(handle, "offline-asr.set-audio", 16000,
                                    audio.data(),
                                    static_cast<int32_t>(audio.size()),
                                    &error) ==
          SHERPA_ONNX_BRIDGE_INVALID_HANDLE);
  REQUIRE(error != nullptr);
  FreeError(error);

  return 0;
}
