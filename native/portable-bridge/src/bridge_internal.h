// SPDX-License-Identifier: Apache-2.0
#ifndef SHERPA_ONNX_PORTABLE_BRIDGE_BRIDGE_INTERNAL_H_
#define SHERPA_ONNX_PORTABLE_BRIDGE_BRIDGE_INTERNAL_H_

#include <memory>
#include <string>
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

class OfflineAsrBackend {
 public:
  virtual ~OfflineAsrBackend() = default;

  virtual std::string Transcribe(const Audio &audio, Error *error) = 0;
};

class OfflineAsrBackendFactory {
 public:
  virtual ~OfflineAsrBackendFactory() = default;

  virtual std::unique_ptr<OfflineAsrBackend> Create(
      const Options &options, Error *error) = 0;
};

void SetError(Error *error, SherpaOnnxBridgeStatus status,
              const std::string &message);
char *DuplicateString(const std::string &value);

void SetOfflineAsrFactoryForTesting(
    std::unique_ptr<OfflineAsrBackendFactory> factory);

}  // namespace sherpa_onnx::bridge

#endif  // SHERPA_ONNX_PORTABLE_BRIDGE_BRIDGE_INTERNAL_H_
