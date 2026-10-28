// SPDX-License-Identifier: Apache-2.0
//
// Host CLI over the Portable Bridge C ABI.
//
// Two modes, selected by the first argument:
//
//   sherpa-onnx-portable-bridge-cli options.json audio.wav
//       Legacy offline-ASR mode: push the wav, transcribe, print the JSON.
//
//   sherpa-onnx-portable-bridge-cli <kind> options.json [arguments.json]
//       Generic mode: create <kind>, then either print `describe` for a
//       resource that has no single-shot operation (VAD, TTS, KWS) or run the
//       kind's operations and print every reply. Audio is pulled, never
//       written, so the mode stays a pure ABI check.
//
// The generic mode exists so each bridge feature can be exercised on the host
// without the KMP test harness, which is what the release hardening checks use.
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>

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

int RunOfflineAsr(const std::string &options_path,
                  const std::string &audio_path) {
  const std::string options = ReadTextFile(options_path);
  if (options.empty()) {
    std::cerr << "Failed to read options file: " << options_path << '\n';
    return 2;
  }

  int32_t sample_rate = 0;
  bool wave_ok = false;
  const std::vector<float> samples =
      sherpa_onnx::ReadWave(audio_path, &sample_rate, &wave_ok);
  if (!wave_ok || samples.empty()) {
    std::cerr << "Failed to read wave file: " << audio_path << '\n';
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

void PrintInfo() {
  char *info = SherpaOnnxBridgeInfoJson();
  std::cout << (info != nullptr ? info : "{}") << '\n';
  SherpaOnnxBridgeFree(info);
}

int RunGeneric(const std::string &kind, const std::string &options_path,
               const std::string &arguments_path) {
  if (kind == "info") {
    PrintInfo();
    return 0;
  }

  const std::string options = ReadTextFile(options_path);
  if (options.empty()) {
    std::cerr << "Failed to read options file: " << options_path << '\n';
    return 2;
  }

  uint64_t handle = 0;
  char *error = nullptr;
  const int32_t create_status =
      SherpaOnnxBridgeCreate(kind.c_str(), options.c_str(), &handle, &error);
  if (create_status != SHERPA_ONNX_BRIDGE_OK) {
    PrintError("Failed to create resource", error);
    return 1;
  }

  const std::string arguments =
      arguments_path.empty() ? "{}" : ReadTextFile(arguments_path);

  // TTS is the one kind whose single-shot operation still has a text input.
  if (kind == "tts" && !arguments_path.empty()) {
    char *result = nullptr;
    error = nullptr;
    if (SherpaOnnxBridgeCall(handle, "tts.synthesize", arguments.c_str(),
                             &result, &error) != SHERPA_ONNX_BRIDGE_OK) {
      PrintError("Failed to synthesize", error);
      SherpaOnnxBridgeDestroy(handle);
      return 1;
    }
    std::cout << (result != nullptr ? result : "{}") << '\n';
    SherpaOnnxBridgeFree(result);

    // Drain every queued chunk and report the totals. The audio itself is
    // discarded: the caller is checking that samples exist and that the stream
    // ends with `isLast`, not listening to the voice.
    int64_t total_samples = 0;
    int32_t chunks = 0;
    while (true) {
      int32_t sample_rate = 0;
      float *samples = nullptr;
      int32_t num_samples = 0;
      char *meta = nullptr;
      error = nullptr;
      if (SherpaOnnxBridgePullAudio(handle, "tts.take-chunk", "{}", &sample_rate,
                                    &samples, &num_samples, &meta,
                                    &error) != SHERPA_ONNX_BRIDGE_OK) {
        PrintError("Failed to pull audio", error);
        SherpaOnnxBridgeDestroy(handle);
        return 1;
      }
      std::string meta_json = meta != nullptr ? meta : "{}";
      SherpaOnnxBridgeFreeAudio(samples);
      SherpaOnnxBridgeFree(meta);
      ++chunks;
      total_samples += num_samples;
      std::cout << "chunk " << chunks << ": sampleRate=" << sample_rate
                << " numSamples=" << num_samples << " " << meta_json << '\n';
      if (meta_json.find("\"isLast\":true") != std::string::npos) {
        break;
      }
    }
    std::cout << "chunks=" << chunks << " totalSamples=" << total_samples
              << '\n';
    SherpaOnnxBridgeDestroy(handle);
    return total_samples > 0 ? 0 : 1;
  }

  char *result = nullptr;
  error = nullptr;
  const int32_t call_status = SherpaOnnxBridgeCall(
      handle, (kind + ".describe").c_str(), arguments.c_str(), &result, &error);
  if (call_status != SHERPA_ONNX_BRIDGE_OK) {
    // `describe` is not universal; a kind without one still proves creation.
    std::cout << "created " << kind << " (no describe operation)\n";
    SherpaOnnxBridgeFree(error);
    SherpaOnnxBridgeDestroy(handle);
    return 0;
  }
  std::cout << (result != nullptr ? result : "{}") << '\n';
  SherpaOnnxBridgeFree(result);
  SherpaOnnxBridgeDestroy(handle);
  return 0;
}

}  // namespace

int main(int argc, char **argv) {
  if (argc == 2 && std::strcmp(argv[1], "info") == 0) {
    PrintInfo();
    return 0;
  }

  if (argc < 3) {
    std::cerr
        << "Usage:\n"
        << "  sherpa-onnx-portable-bridge-cli options.json audio.wav\n"
        << "  sherpa-onnx-portable-bridge-cli info\n"
        << "  sherpa-onnx-portable-bridge-cli <kind> options.json "
           "[arguments.json]\n";
    return 2;
  }

  if (argc == 3) {
    // The two-argument form remains the offline-ASR audio path, which is what
    // the existing contract scripts call.
    const std::string second = argv[2];
    if (second.size() >= 4 && second.compare(second.size() - 4, 4, ".wav") == 0) {
      return RunOfflineAsr(argv[1], argv[2]);
    }
  }

  const std::string kind = argv[1];
  const std::string options = argv[2];
  const std::string arguments = argc >= 4 ? argv[3] : std::string();
  return RunGeneric(kind, options, arguments);
}
