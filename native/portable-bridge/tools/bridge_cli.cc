// SPDX-License-Identifier: Apache-2.0
#include <cstring>
#include <vector>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>

#include "sherpa-onnx-portable-bridge.h"
#include "sherpa-onnx/csrc/wave-reader.h"

namespace {

std::string ReadTextFile(const std::string &filename) {
  std::ifstream is(filename);
  if (!is) {
    return {};
  }
  return std::string(std::istreambuf_iterator<char>(is),
                     std::istreambuf_iterator<char>());
}

void PrintError(const char *fallback, char *error) {
  std::cerr << (error != nullptr ? error : fallback) << '\n';
  SherpaOnnxBridgeFree(error);
}

}  // namespace

int main(int argc, char **argv) {
  if (argc != 3) {
    std::cerr
        << "Usage: sherpa-onnx-portable-bridge-cli options.json audio.wav\n";
    return 2;
  }

  const std::string options = ReadTextFile(argv[1]);
  if (options.empty()) {
    std::cerr << "Failed to read options file: " << argv[1] << '\n';
    return 2;
  }

  int32_t sample_rate = 0;
  bool wave_ok = false;
  const std::vector<float> samples =
      sherpa_onnx::ReadWave(argv[2], &sample_rate, &wave_ok);
  if (!wave_ok || samples.empty()) {
    std::cerr << "Failed to read wave file: " << argv[2] << '\n';
    return 2;
  }

  uint64_t handle = 0;
  char *error = nullptr;
  const int32_t create_status = SherpaOnnxBridgeCreate(
      "offline-asr", options.c_str(), &handle, &error);
  if (create_status != SHERPA_ONNX_BRIDGE_OK) {
    PrintError("Failed to create offline ASR", error);
    return 1;
  }

  error = nullptr;
  const int32_t audio_status = SherpaOnnxBridgePushAudio(
      handle, "offline-asr.set-audio", sample_rate, samples.data(),
      static_cast<int32_t>(samples.size()), &error);
  if (audio_status != SHERPA_ONNX_BRIDGE_OK) {
    PrintError("Failed to push audio", error);
    SherpaOnnxBridgeDestroy(handle);
    return 1;
  }

  char *result = nullptr;
  error = nullptr;
  const int32_t transcribe_status = SherpaOnnxBridgeCall(
      handle, "offline-asr.transcribe", "{}", &result, &error);
  if (transcribe_status != SHERPA_ONNX_BRIDGE_OK) {
    PrintError("Failed to transcribe audio", error);
    SherpaOnnxBridgeDestroy(handle);
    return 1;
  }

  std::cout << (result != nullptr ? result : "{}") << '\n';
  SherpaOnnxBridgeFree(result);
  SherpaOnnxBridgeDestroy(handle);
  return 0;
}
