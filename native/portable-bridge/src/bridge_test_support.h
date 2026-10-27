// SPDX-License-Identifier: Apache-2.0
#ifndef SHERPA_ONNX_PORTABLE_BRIDGE_BRIDGE_TEST_SUPPORT_H_
#define SHERPA_ONNX_PORTABLE_BRIDGE_BRIDGE_TEST_SUPPORT_H_

#include <memory>

#include "bridge_internal.h"

namespace sherpa_onnx::bridge {

void SetOfflineAsrFactoryForTesting(
    std::unique_ptr<OfflineAsrBackendFactory> factory);

}  // namespace sherpa_onnx::bridge

#endif  // SHERPA_ONNX_PORTABLE_BRIDGE_BRIDGE_TEST_SUPPORT_H_
