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

/**
 * Bridge ABI version.
 *
 * Incremented for additive changes that a transport must be able to detect,
 * and for any change to the meaning of an existing entry point. Version 2
 * added audio pull, streaming ASR, and VAD.
 */
#define SHERPA_ONNX_BRIDGE_ABI_VERSION 2

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
 *
 * Shape:
 *   {
 *     "abiVersion": 2,
 *     "schemaVersion": 1,
 *     "resources": ["offline-asr", "online-asr", "vad", ...],
 *     "operations": ["health", "offline-asr.transcribe", ...],
 *     "coreVersion": "...",        // when built with sherpa-onnx
 *     "onnxruntimeVersion": "..."   // when built with sherpa-onnx
 *   }
 */
SHERPA_ONNX_BRIDGE_API char *SherpaOnnxBridgeInfoJson(void);

/**
 * Create a bridge resource.
 *
 * Supported kinds are reported by SherpaOnnxBridgeInfoJson(). Options are a
 * flat JSON object whose values are strings, booleans, or numbers. Keys are
 * sherpa-onnx configuration option names, for example:
 *
 * {"nemo-ctc-model":"model.int8.onnx","tokens":"tokens.txt","num-threads":"1"}
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
 * internally. When result_json is non-null, a newly allocated UTF-8 JSON string
 * is written to *result_json on success.
 */
SHERPA_ONNX_BRIDGE_API int32_t SherpaOnnxBridgeCall(
    uint64_t handle, const char *operation, const char *arguments_json,
    char **result_json, char **error);

/**
 * Copy mono Float32 PCM into a resource.
 *
 * The operation selects what the samples mean, for example
 * "offline-asr.set-audio" or "online-session.push-audio".
 */
SHERPA_ONNX_BRIDGE_API int32_t SherpaOnnxBridgePushAudio(
    uint64_t handle, const char *operation, int32_t sample_rate,
    const float *samples, int32_t num_samples, char **error);

/**
 * Pull mono Float32 PCM out of a resource.
 *
 * On success, *samples is a newly allocated buffer of *num_samples floats and
 * *sample_rate is its sample rate. Release it with SherpaOnnxBridgeFreeAudio().
 * When the operation produces no audio, *samples is null and *num_samples is 0.
 *
 * An operation may return side-channel metadata in *result_json. Free it with
 * SherpaOnnxBridgeFree().
 */
SHERPA_ONNX_BRIDGE_API int32_t SherpaOnnxBridgePullAudio(
    uint64_t handle, const char *operation, const char *arguments_json,
    int32_t *sample_rate, float **samples, int32_t *num_samples,
    char **result_json, char **error);

/**
 * Destroy a resource. Unknown or already destroyed handles are ignored.
 *
 * Destroying a resource also destroys the child resources it owns, such as the
 * streams of an online recognizer.
 */
SHERPA_ONNX_BRIDGE_API void SherpaOnnxBridgeDestroy(uint64_t handle);

/**
 * Free a string returned by the bridge.
 */
SHERPA_ONNX_BRIDGE_API void SherpaOnnxBridgeFree(char *ptr);

/**
 * Free a buffer returned by SherpaOnnxBridgePullAudio().
 */
SHERPA_ONNX_BRIDGE_API void SherpaOnnxBridgeFreeAudio(float *ptr);

#ifdef __cplusplus
}
#endif

#endif  // SHERPA_ONNX_PORTABLE_BRIDGE_H_
