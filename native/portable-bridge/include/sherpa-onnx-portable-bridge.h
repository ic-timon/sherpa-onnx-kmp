// sherpa-onnx-portable-bridge.h
//
// Copyright (c) 2026
// SPDX-License-Identifier: Apache-2.0

#ifndef SHERPA_ONNX_PORTABLE_BRIDGE_H_
#define SHERPA_ONNX_PORTABLE_BRIDGE_H_

#include <stdint.h>

#if defined(_WIN32)
#if defined(SHERPA_ONNX_PORTABLE_BRIDGE_BUILD)
#define SHERPA_ONNX_BRIDGE_API __declspec(dllexport)
#else
#define SHERPA_ONNX_BRIDGE_API __declspec(dllimport)
#endif
#else
#define SHERPA_ONNX_BRIDGE_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define SHERPA_ONNX_BRIDGE_ABI_VERSION 1

typedef enum SherpaOnnxBridgeStatus {
  SHERPA_ONNX_BRIDGE_OK = 0,
  SHERPA_ONNX_BRIDGE_INVALID_ARGUMENT = 1,
  SHERPA_ONNX_BRIDGE_INVALID_HANDLE = 2,
  SHERPA_ONNX_BRIDGE_UNSUPPORTED = 3,
  SHERPA_ONNX_BRIDGE_INVALID_STATE = 4,
  SHERPA_ONNX_BRIDGE_RUNTIME_ERROR = 5,
} SherpaOnnxBridgeStatus;

/**
 * Return the bridge ABI version.
 */
SHERPA_ONNX_BRIDGE_API int32_t SherpaOnnxBridgeAbiVersion(void);

/**
 * Return a newly allocated JSON object describing bridge capabilities.
 * Free the returned string with SherpaOnnxBridgeFree().
 */
SHERPA_ONNX_BRIDGE_API char *SherpaOnnxBridgeInfoJson(void);

/**
 * Create a bridge resource.
 *
 * Currently the only supported kind is "offline-asr". Options are a flat JSON
 * object whose values are strings. Keys are sherpa-onnx configuration option
 * names, for example:
 *
 * {"paraformer":"model.int8.onnx","tokens":"tokens.txt","num-threads":"1"}
 *
 * On success, writes a non-zero handle to *handle.
 * On failure, returns a non-zero status and, when error is non-null, writes a
 * newly allocated UTF-8 message to *error. Free messages with
 * SherpaOnnxBridgeFree().
 */
SHERPA_ONNX_BRIDGE_API int32_t SherpaOnnxBridgeCreate(
    const char *kind, const char *options_json, uint64_t *handle,
    char **error);

/**
 * Invoke a control operation on a resource.
 *
 * Supported operations depend on the resource kind. The bridge is serialized
 * internally.
 */
SHERPA_ONNX_BRIDGE_API int32_t SherpaOnnxBridgeCall(
    uint64_t handle, const char *operation, const char *arguments_json,
    char **result_json, char **error);

/**
 * Copy mono Float32 PCM into a resource.
 */
SHERPA_ONNX_BRIDGE_API int32_t SherpaOnnxBridgePushAudio(
    uint64_t handle, const char *operation, int32_t sample_rate,
    const float *samples, int32_t num_samples, char **error);

/**
 * Destroy a resource. Unknown or already destroyed handles are ignored.
 */
SHERPA_ONNX_BRIDGE_API void SherpaOnnxBridgeDestroy(uint64_t handle);

/**
 * Free a string returned by the bridge.
 */
SHERPA_ONNX_BRIDGE_API void SherpaOnnxBridgeFree(char *ptr);

#ifdef __cplusplus
}
#endif

#endif  // SHERPA_ONNX_PORTABLE_BRIDGE_H_
