// SPDX-License-Identifier: Apache-2.0
#include "sherpa-onnx-portable-bridge.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "bridge_internal.h"
#include "nlohmann/json.hpp"

namespace sherpa_onnx::bridge {

#if defined(SHERPA_ONNX_BRIDGE_WITH_SHERPA)
void RegisterDefaultOfflineAsrFactory();
#endif

namespace {

std::mutex g_mutex;
uint64_t g_next_handle = 1;

struct HandleEntry {
  std::mutex mutex;
  std::unique_ptr<OfflineAsrBackend> backend;
  Audio pending_audio;
  bool has_audio = false;
};

std::unique_ptr<OfflineAsrBackendFactory> g_offline_asr_factory;
std::unordered_map<uint64_t, std::shared_ptr<HandleEntry>> g_handles;

std::shared_ptr<HandleEntry> FindHandle(uint64_t handle) {
  auto it = g_handles.find(handle);
  if (it == g_handles.end()) {
    return nullptr;
  }
  return it->second;
}

bool ParseOptionsJson(const char *json, Options *options, Error *error) {
  if (json == nullptr) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "options_json must not be null");
    return false;
  }

  nlohmann::json parsed;
  try {
    parsed = nlohmann::json::parse(json);
  } catch (const std::exception &e) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             std::string("Invalid options JSON: ") + e.what());
    return false;
  }

  if (!parsed.is_object()) {
    SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "options_json must be a JSON object");
    return false;
  }

  options->clear();
  options->reserve(parsed.size());
  for (const auto &[key, value] : parsed.items()) {
    if (value.is_string()) {
      options->emplace_back(key, value.get<std::string>());
    } else if (value.is_boolean()) {
      options->emplace_back(key, value.get<bool>() ? "true" : "false");
    } else if (value.is_number_integer()) {
      options->emplace_back(key, std::to_string(value.get<int64_t>()));
    } else if (value.is_number_unsigned()) {
      options->emplace_back(key, std::to_string(value.get<uint64_t>()));
    } else if (value.is_number_float()) {
      options->emplace_back(key, std::to_string(value.get<double>()));
    } else {
      SetError(error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
               "Option values must be strings, booleans, or numbers: " + key);
      return false;
    }
  }

  return true;
}

std::string SuccessJson() { return "{\"ok\":true}"; }

}  // namespace

void SetError(Error *error, SherpaOnnxBridgeStatus status,
              const std::string &message) {
  if (error != nullptr) {
    error->status = status;
    error->message = message;
  }
}

char *DuplicateString(const std::string &value) {
  auto *ans = static_cast<char *>(std::malloc(value.size() + 1));
  if (ans == nullptr) {
    return nullptr;
  }
  std::memcpy(ans, value.data(), value.size());
  ans[value.size()] = '\0';
  return ans;
}

void SetOfflineAsrFactoryForTesting(
    std::unique_ptr<OfflineAsrBackendFactory> factory) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_offline_asr_factory = std::move(factory);
  g_handles.clear();
}

}  // namespace sherpa_onnx::bridge

extern "C" {

int32_t SherpaOnnxBridgeAbiVersion(void) {
  return SHERPA_ONNX_BRIDGE_ABI_VERSION;
}

char *SherpaOnnxBridgeInfoJson(void) {
  namespace bridge = sherpa_onnx::bridge;
  const nlohmann::json info = {
      {"abiVersion", SHERPA_ONNX_BRIDGE_ABI_VERSION},
      {"schemaVersion", 1},
      {"resources", nlohmann::json::array({"offline-asr"})},
      {"operations", nlohmann::json::array({"health",
                                            "offline-asr.set-audio",
                                            "offline-asr.transcribe"})},
  };
  return bridge::DuplicateString(info.dump());
}

int32_t SherpaOnnxBridgeCreate(const char *kind, const char *options_json,
                               uint64_t *handle, char **error) {
  using namespace sherpa_onnx::bridge;

  if (error != nullptr) {
    *error = nullptr;
  }
  if (handle != nullptr) {
    *handle = 0;
  }

  Error bridge_error;
  if (kind == nullptr || handle == nullptr) {
    SetError(&bridge_error, SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT,
             "kind and handle must not be null");
  } else if (std::strcmp(kind, "offline-asr") != 0) {
    SetError(&bridge_error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
             std::string("Unsupported resource kind: ") + kind);
  }

  Options options;
  if (!bridge_error) {
    ParseOptionsJson(options_json, &options, &bridge_error);
  }

#if defined(SHERPA_ONNX_BRIDGE_WITH_SHERPA)
  bool needs_default_factory = false;
  if (!bridge_error) {
    std::lock_guard<std::mutex> lock(g_mutex);
    needs_default_factory = !g_offline_asr_factory;
  }
  if (needs_default_factory) {
    RegisterDefaultOfflineAsrFactory();
  }
#endif

  std::unique_ptr<OfflineAsrBackend> backend;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!bridge_error && !g_offline_asr_factory) {
      SetError(&bridge_error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               "offline-asr backend is not configured");
    }
    if (!bridge_error) {
      backend = g_offline_asr_factory->Create(options, &bridge_error);
      if (!bridge_error && !backend) {
        SetError(&bridge_error, SHERPA_ONNX_BRIDGE_RUNTIME_ERROR,
                 "offline-asr backend returned a null instance");
      }
    }
    if (!bridge_error) {
      const uint64_t created = g_next_handle++;
      auto entry = std::make_shared<HandleEntry>();
      entry->backend = std::move(backend);
      g_handles.emplace(created, std::move(entry));
      *handle = created;
    }
  }

  if (bridge_error) {
    if (error != nullptr) {
      *error = DuplicateString(bridge_error.message);
    }
    return bridge_error.status;
  }
  return SHERPA_ONNX_BRIDGE_OK;
}

int32_t SherpaOnnxBridgeCall(uint64_t handle, const char *operation,
                             const char *arguments_json, char **result_json,
                             char **error) {
  using namespace sherpa_onnx::bridge;

  if (error != nullptr) {
    *error = nullptr;
  }
  if (result_json != nullptr) {
    *result_json = nullptr;
  }

  if (operation == nullptr) {
    if (error != nullptr) {
      *error = DuplicateString("operation must not be null");
    }
    return SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT;
  }

  if (arguments_json == nullptr) {
    arguments_json = "{}";
  }

  Error bridge_error;
  std::string result;
  std::shared_ptr<HandleEntry> entry;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    entry = FindHandle(handle);
  }
  if (!entry) {
    SetError(&bridge_error, SHERPA_ONNX_BRIDGE_INVALID_HANDLE,
             "Unknown or destroyed handle");
  } else if (std::strcmp(operation, "offline-asr.set-audio") == 0) {
    SetError(&bridge_error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
             "Use SherpaOnnxBridgePushAudio for offline-asr.set-audio");
  } else if (std::strcmp(operation, "offline-asr.transcribe") == 0) {
    std::lock_guard<std::mutex> lock(entry->mutex);
    if (!entry->has_audio) {
      SetError(&bridge_error, SHERPA_ONNX_BRIDGE_INVALID_STATE,
               "No audio has been pushed to this handle");
    } else {
      result = entry->backend->Transcribe(entry->pending_audio, &bridge_error);
      if (!bridge_error) {
        entry->pending_audio = {};
        entry->has_audio = false;
      }
    }
  } else if (std::strcmp(operation, "health") == 0) {
    result = SuccessJson();
  } else {
    SetError(&bridge_error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
             std::string("Unsupported operation: ") + operation);
  }

  if (bridge_error) {
    if (error != nullptr) {
      *error = DuplicateString(bridge_error.message);
    }
    return bridge_error.status;
  }

  if (result_json != nullptr) {
    *result_json = DuplicateString(result);
  }
  return SHERPA_ONNX_BRIDGE_OK;
}

int32_t SherpaOnnxBridgePushAudio(uint64_t handle, const char *operation,
                                  int32_t sample_rate, const float *samples,
                                  int32_t num_samples, char **error) {
  using namespace sherpa_onnx::bridge;

  if (error != nullptr) {
    *error = nullptr;
  }
  if (operation == nullptr || sample_rate <= 0 || num_samples < 0 ||
      (num_samples > 0 && samples == nullptr)) {
    if (error != nullptr) {
      *error = DuplicateString("Invalid audio arguments");
    }
    return SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT;
  }

  if (std::strcmp(operation, "offline-asr.set-audio") != 0) {
    if (error != nullptr) {
      *error = DuplicateString(std::string("Unsupported audio operation: ") +
                               operation);
    }
    return SHERPA_ONNX_BRIDGE_UNSUPPORTED;
  }

  std::shared_ptr<HandleEntry> entry;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    entry = FindHandle(handle);
  }
  if (!entry) {
    if (error != nullptr) {
      *error = DuplicateString("Unknown or destroyed handle");
    }
    return SHERPA_ONNX_BRIDGE_INVALID_HANDLE;
  }

  std::lock_guard<std::mutex> lock(entry->mutex);
  if (entry->has_audio && entry->pending_audio.sample_rate != sample_rate) {
    if (error != nullptr) {
      *error = DuplicateString(
          "All audio pushed for one transcription must use the same sample rate");
    }
    return SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT;
  }

  entry->pending_audio.sample_rate = sample_rate;
  if (num_samples > 0) {
    entry->pending_audio.samples.insert(entry->pending_audio.samples.end(),
                                        samples, samples + num_samples);
  }
  entry->has_audio = true;
  return SHERPA_ONNX_BRIDGE_OK;
}

void SherpaOnnxBridgeDestroy(uint64_t handle) {
  std::shared_ptr<sherpa_onnx::bridge::HandleEntry> entry;
  {
    std::lock_guard<std::mutex> lock(sherpa_onnx::bridge::g_mutex);
    auto it = sherpa_onnx::bridge::g_handles.find(handle);
    if (it != sherpa_onnx::bridge::g_handles.end()) {
      entry = std::move(it->second);
      sherpa_onnx::bridge::g_handles.erase(it);
    }
  }
}

void SherpaOnnxBridgeFree(char *ptr) { std::free(ptr); }

}  // extern "C"
