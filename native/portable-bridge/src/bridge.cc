// SPDX-License-Identifier: Apache-2.0
#include "sherpa-onnx-portable-bridge.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "bridge_internal.h"
#include "nlohmann/json.hpp"

#if defined(SHERPA_ONNX_BRIDGE_WITH_SHERPA)
#include "sherpa-onnx/csrc/version.h"
#endif

namespace sherpa_onnx::bridge {

namespace {

std::mutex g_mutex;
uint64_t g_next_handle = 1;

struct HandleEntry {
  std::mutex mutex;
  std::unique_ptr<Backend> backend;
  std::string kind;
  uint64_t parent = 0;
  std::vector<uint64_t> children;
};

/** Protected by g_mutex. */
std::unordered_map<uint64_t, std::shared_ptr<HandleEntry>> g_handles;

std::shared_ptr<HandleEntry> FindHandle(uint64_t handle) {
  auto it = g_handles.find(handle);
  if (it == g_handles.end()) {
    return nullptr;
  }
  return it->second;
}

/**
 * Creates a handle entry. Caller must hold g_mutex.
 *
 * The backend's child registrar re-acquires g_mutex. That is safe because a
 * child is only ever registered while the parent's handle mutex is held, and no
 * code path takes g_mutex before a handle mutex.
 */
uint64_t InsertHandleLocked(std::unique_ptr<Backend> backend,
                            const std::string &kind, uint64_t parent) {
  const uint64_t created = g_next_handle++;
  backend->set_register_child(
      [created](const std::string &child_kind,
                std::unique_ptr<Backend> child) -> uint64_t {
        std::lock_guard<std::mutex> lock(g_mutex);
        return InsertHandleLocked(std::move(child), child_kind, created);
      });

  auto entry = std::make_shared<HandleEntry>();
  entry->kind = kind;
  entry->parent = parent;
  entry->backend = std::move(backend);
  g_handles.emplace(created, std::move(entry));
  if (parent != 0) {
    auto it = g_handles.find(parent);
    if (it != g_handles.end()) {
      it->second->children.push_back(created);
    }
  }
  return created;
}

/** Removes a handle from the registry. Caller must hold g_mutex. */
std::shared_ptr<HandleEntry> RemoveHandleLocked(uint64_t handle) {
  auto it = g_handles.find(handle);
  if (it == g_handles.end()) {
    return nullptr;
  }
  auto entry = std::move(it->second);
  g_handles.erase(it);
  if (entry->parent != 0) {
    auto parent = g_handles.find(entry->parent);
    if (parent != g_handles.end()) {
      auto &siblings = parent->second->children;
      siblings.erase(std::remove(siblings.begin(), siblings.end(), handle),
                     siblings.end());
    }
  }
  return entry;
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

}  // namespace

FactoryMap &DefaultFactories() {
  static auto *factories = new FactoryMap();
  return *factories;
}

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

std::string Backend::Call(const std::string & /*operation*/,
                          const std::string & /*arguments*/, Error *error) {
  SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
           "This resource does not support control operations");
  return {};
}

void Backend::PushAudio(const std::string & /*operation*/,
                        const Audio & /*audio*/, Error *error) {
  SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
           "This resource does not accept audio input");
}

std::string Backend::PullAudio(const std::string & /*operation*/,
                               const std::string & /*arguments*/,
                               Audio * /*audio*/, Error *error) {
  SetError(error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
           "This resource does not produce audio");
  return {};
}

void SetFactoryForTesting(const std::string &kind,
                          std::unique_ptr<BackendFactory> factory) {
  std::lock_guard<std::mutex> lock(g_mutex);
  auto &factories = DefaultFactories();
  if (factory) {
    factories[kind] = std::move(factory);
  } else {
    factories.erase(kind);
  }
}

void ResetFactoriesForTesting() {
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    DefaultFactories().clear();
  }
  RegisterDefaultFactories();
}

#if !defined(SHERPA_ONNX_BRIDGE_WITH_SHERPA)
// Without the sherpa-onnx backend there is nothing to install. Test doubles are
// registered through SetFactoryForTesting().
void RegisterDefaultFactories() {}
#endif

}  // namespace sherpa_onnx::bridge

extern "C" {

int32_t SherpaOnnxBridgeAbiVersion(void) {
  return SHERPA_ONNX_BRIDGE_ABI_VERSION;
}

char *SherpaOnnxBridgeInfoJson(void) {
  using namespace sherpa_onnx::bridge;

  RegisterDefaultFactories();

  nlohmann::json resources = nlohmann::json::array();
  nlohmann::json operations = nlohmann::json::array({"health"});
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto &factories = DefaultFactories();
    std::vector<std::string> kinds;
    kinds.reserve(factories.size());
    for (const auto &entry : factories) {
      kinds.push_back(entry.first);
    }
    std::sort(kinds.begin(), kinds.end());

    nlohmann::json children = nlohmann::json::array();
    for (const auto &kind : kinds) {
      resources.push_back(kind);
      // The factory is the single source of truth for its resources'
      // operations, so capability discovery cannot drift from behavior.
      for (const auto &name : factories[kind]->Operations()) {
        operations.push_back(kind + "." + name);
      }
      for (const auto &[child_kind, child_ops] :
           factories[kind]->ChildOperations()) {
        children.push_back(child_kind);
        for (const auto &name : child_ops) {
          operations.push_back(child_kind + "." + name);
        }
      }
    }
    for (auto &child : children) {
      resources.push_back(child);
    }
  }
  // `resources` and `operations` are sorted so a transport can compare two
  // builds' reports directly. Sorting after collecting also keeps the child
  // kinds interleaved correctly with their parents.
  {
    std::vector<std::string> flat;
    for (auto &item : resources) {
      flat.push_back(item.get<std::string>());
    }
    std::sort(flat.begin(), flat.end());
    flat.erase(std::unique(flat.begin(), flat.end()), flat.end());
    resources = nlohmann::json::array();
    for (const auto &kind : flat) {
      resources.push_back(kind);
    }

    flat.clear();
    for (auto &item : operations) {
      flat.push_back(item.get<std::string>());
    }
    std::sort(flat.begin(), flat.end());
    operations = nlohmann::json::array();
    for (const auto &name : flat) {
      operations.push_back(name);
    }
  }

  nlohmann::json info = {
      {"abiVersion", SHERPA_ONNX_BRIDGE_ABI_VERSION},
      {"schemaVersion", 1},
      {"resources", std::move(resources)},
      {"operations", std::move(operations)},
  };
#if defined(SHERPA_ONNX_BRIDGE_WITH_SHERPA)
  info["coreVersion"] = sherpa_onnx::GetVersionStr();
  info["onnxruntimeVersion"] = sherpa_onnx::GetOnnxruntimeVersionStr();
#endif
  return DuplicateString(info.dump());
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
  }

  Options options;
  if (!bridge_error) {
    ParseOptionsJson(options_json, &options, &bridge_error);
  }

  RegisterDefaultFactories();

  std::unique_ptr<Backend> backend;
  if (!bridge_error) {
    // Factories are never removed while the bridge is in use, so calling one
    // outside the registry lock is safe and keeps resource creation (which can
    // load a model for seconds) from blocking unrelated handles.
    std::unique_ptr<BackendFactory> *factory = nullptr;
    {
      std::lock_guard<std::mutex> lock(g_mutex);
      auto it = DefaultFactories().find(kind);
      if (it != DefaultFactories().end()) {
        factory = &it->second;
      }
    }
    if (factory == nullptr) {
      SetError(&bridge_error, SHERPA_ONNX_BRIDGE_UNSUPPORTED,
               std::string("Unsupported resource kind: ") + kind);
    } else {
      backend = (*factory)->Create(options, &bridge_error);
      if (!bridge_error && !backend) {
        SetError(&bridge_error, SHERPA_ONNX_BRIDGE_RUNTIME_ERROR,
                 std::string(kind) + " backend returned a null instance");
      }
    }
  }

  if (!bridge_error) {
    std::lock_guard<std::mutex> lock(g_mutex);
    *handle = InsertHandleLocked(std::move(backend), kind, 0);
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
  } else if (std::strcmp(operation, "health") == 0) {
    result = "{\"ok\":true}";
  } else {
    std::lock_guard<std::mutex> lock(entry->mutex);
    if (!entry->backend) {
      SetError(&bridge_error, SHERPA_ONNX_BRIDGE_INVALID_HANDLE,
               "Handle was destroyed");
    } else {
      result = entry->backend->Call(operation, arguments_json, &bridge_error);
    }
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

  Audio audio;
  audio.sample_rate = sample_rate;
  if (num_samples > 0) {
    audio.samples.assign(samples, samples + num_samples);
  }

  Error bridge_error;
  {
    std::lock_guard<std::mutex> lock(entry->mutex);
    if (!entry->backend) {
      SetError(&bridge_error, SHERPA_ONNX_BRIDGE_INVALID_HANDLE,
               "Handle was destroyed");
    } else {
      entry->backend->PushAudio(operation, audio, &bridge_error);
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

int32_t SherpaOnnxBridgePullAudio(uint64_t handle, const char *operation,
                                  const char *arguments_json,
                                  int32_t *sample_rate, float **samples,
                                  int32_t *num_samples, char **result_json,
                                  char **error) {
  using namespace sherpa_onnx::bridge;

  if (error != nullptr) {
    *error = nullptr;
  }
  if (result_json != nullptr) {
    *result_json = nullptr;
  }
  if (samples != nullptr) {
    *samples = nullptr;
  }
  if (num_samples != nullptr) {
    *num_samples = 0;
  }
  if (sample_rate != nullptr) {
    *sample_rate = 0;
  }

  if (operation == nullptr || samples == nullptr || num_samples == nullptr ||
      sample_rate == nullptr) {
    if (error != nullptr) {
      *error = DuplicateString("Invalid audio output arguments");
    }
    return SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT;
  }
  if (arguments_json == nullptr) {
    arguments_json = "{}";
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

  Error bridge_error;
  Audio audio;
  std::string result;
  {
    std::lock_guard<std::mutex> lock(entry->mutex);
    if (!entry->backend) {
      SetError(&bridge_error, SHERPA_ONNX_BRIDGE_INVALID_HANDLE,
               "Handle was destroyed");
    } else {
      result = entry->backend->PullAudio(operation, arguments_json, &audio,
                                         &bridge_error);
    }
  }

  if (bridge_error) {
    if (error != nullptr) {
      *error = DuplicateString(bridge_error.message);
    }
    return bridge_error.status;
  }

  if (!audio.samples.empty()) {
    auto *copy =
        static_cast<float *>(std::malloc(sizeof(float) * audio.samples.size()));
    if (copy == nullptr) {
      if (error != nullptr) {
        *error = DuplicateString("Failed to allocate audio output buffer");
      }
      return SHERPA_ONNX_BRIDGE_RUNTIME_ERROR;
    }
    std::memcpy(copy, audio.samples.data(), sizeof(float) * audio.samples.size());
    *samples = copy;
    *num_samples = static_cast<int32_t>(audio.samples.size());
    *sample_rate = audio.sample_rate;
  }

  if (result_json != nullptr) {
    *result_json = DuplicateString(result);
  }
  return SHERPA_ONNX_BRIDGE_OK;
}

void SherpaOnnxBridgeDestroy(uint64_t handle) {
  using namespace sherpa_onnx::bridge;

  std::vector<std::shared_ptr<HandleEntry>> removed;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::vector<uint64_t> pending = {handle};
    while (!pending.empty()) {
      const uint64_t current = pending.back();
      pending.pop_back();
      auto entry = RemoveHandleLocked(current);
      if (!entry) {
        continue;
      }
      pending.insert(pending.end(), entry->children.begin(),
                     entry->children.end());
      entry->children.clear();
      removed.push_back(std::move(entry));
    }
  }

  // The registry no longer holds these entries, but an in-flight call may. Take
  // each entry mutex before releasing the backend so destruction cannot race a
  // running operation.
  for (auto &entry : removed) {
    std::lock_guard<std::mutex> lock(entry->mutex);
    entry->backend.reset();
  }
}

void SherpaOnnxBridgeFree(char *ptr) { std::free(ptr); }

void SherpaOnnxBridgeFreeAudio(float *ptr) { std::free(ptr); }

}  // extern "C"
