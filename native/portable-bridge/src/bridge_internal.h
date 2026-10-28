// SPDX-License-Identifier: Apache-2.0
#ifndef SHERPA_ONNX_PORTABLE_BRIDGE_BRIDGE_INTERNAL_H_
#define SHERPA_ONNX_PORTABLE_BRIDGE_BRIDGE_INTERNAL_H_

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "sherpa-onnx-portable-bridge.h"

namespace sherpa_onnx::bridge {

struct Error {
  SherpaOnnxBridgeStatus status = SHERPA_ONNX_BRIDGE_OK;
  std::string message;

  explicit operator bool() const {
    return status != SHERPA_ONNX_BRIDGE_OK;
  }
};

using Options = std::vector<std::pair<std::string, std::string>>;

struct Audio {
  int32_t sample_rate = 0;
  std::vector<float> samples;
};

/**
 * A bridge resource.
 *
 * Every method is invoked while the owning handle's mutex is held, so a
 * backend does not need to serialize its own state. A backend must not call
 * back into the registry except through [register_child], which is the only
 * entry point that does not recurse into the bridge lock.
 */
class Backend {
 public:
  using ChildRegistrar = std::function<uint64_t(
      const std::string &kind, std::unique_ptr<Backend> backend)>;

  virtual ~Backend() = default;

  void set_register_child(ChildRegistrar registrar) {
    register_child_ = std::move(registrar);
  }

  /** Operations this resource supports, excluding "health". */
  virtual std::vector<std::string> Operations() const = 0;

  /** Control operation. Returns a result JSON string on success. */
  virtual std::string Call(const std::string &operation,
                           const std::string &arguments, Error *error);

  /** Audio input. */
  virtual void PushAudio(const std::string &operation, const Audio &audio,
                         Error *error);

  /**
   * Audio output. Fills [audio] and returns optional result JSON. An operation
   * that yields no audio leaves [audio] empty and returns an empty string.
   */
  virtual std::string PullAudio(const std::string &operation,
                                const std::string &arguments, Audio *audio,
                                Error *error);

 protected:
  /** Registers a child resource owned by the current handle. */
  uint64_t RegisterChild(const std::string &kind,
                         std::unique_ptr<Backend> backend) {
    return register_child_ ? register_child_(kind, std::move(backend)) : 0;
  }

 private:
  ChildRegistrar register_child_;
};

class BackendFactory {
 public:
  virtual ~BackendFactory() = default;

  virtual std::unique_ptr<Backend> Create(const Options &options,
                                          Error *error) = 0;

  /**
   * Operations every backend created by this factory supports, excluding
   * "health". Reported by SherpaOnnxBridgeInfoJson() so capability discovery
   * cannot drift from the implementation.
   */
  virtual std::vector<std::string> Operations() const = 0;

  /**
   * Child kinds this factory's resources create, mapped to the operations the
   * children support. Reported as resources so a transport can see the full
   * surface before it creates anything.
   */
  virtual std::vector<std::pair<std::string, std::vector<std::string>>>
  ChildOperations() const {
    return {};
  }
};

using FactoryMap =
    std::unordered_map<std::string, std::unique_ptr<BackendFactory>>;

/** The kind-to-factory registry. Guarded by the bridge registry mutex. */
FactoryMap &DefaultFactories();

void SetError(Error *error, SherpaOnnxBridgeStatus status,
              const std::string &message);
char *DuplicateString(const std::string &value);

/** Install the sherpa-onnx-backed factories. Called once at startup. */
void RegisterDefaultFactories();

/**
 * Replaces one kind with a test double. Passing null removes the factory.
 * Intended for bridge unit tests only.
 */
void SetFactoryForTesting(const std::string &kind,
                          std::unique_ptr<BackendFactory> factory);

/** Removes every test double and restores the default factories. */
void ResetFactoriesForTesting();

}  // namespace sherpa_onnx::bridge

#endif  // SHERPA_ONNX_PORTABLE_BRIDGE_BRIDGE_INTERNAL_H_
