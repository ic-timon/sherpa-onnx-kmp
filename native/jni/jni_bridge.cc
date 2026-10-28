// SPDX-License-Identifier: Apache-2.0
#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <string>
#include <vector>

#include "sherpa-onnx-portable-bridge.h"

namespace {

std::string ToString(JNIEnv *env, jstring value) {
  if (value == nullptr) {
    return {};
  }
  const char *chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) {
    return {};
  }
  std::string ans(chars);
  env->ReleaseStringUTFChars(value, chars);
  return ans;
}

jstring NewString(JNIEnv *env, const std::string &value) {
  return env->NewStringUTF(value.c_str());
}

void ThrowIllegalState(JNIEnv *env, char *error) {
  const char *message = error != nullptr ? error : "portable bridge call failed";
  jclass cls = env->FindClass("java/lang/IllegalStateException");
  if (cls != nullptr) {
    env->ThrowNew(cls, message);
    env->DeleteLocalRef(cls);
  }
  SherpaOnnxBridgeFree(error);
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_k2fsa_sherpa_onnx_kmp_internal_JniPortableTransport_nativeInfoJson(
    JNIEnv *env, jobject /*thiz*/) {
  char *info = SherpaOnnxBridgeInfoJson();
  if (info == nullptr) {
    ThrowIllegalState(env, nullptr);
    return nullptr;
  }
  jstring ans = NewString(env, info);
  SherpaOnnxBridgeFree(info);
  return ans;
}

JNIEXPORT jlong JNICALL
Java_com_k2fsa_sherpa_onnx_kmp_internal_JniPortableTransport_nativeCreate(
    JNIEnv *env, jobject /*thiz*/, jstring kind, jstring options_json) {
  const std::string kind_str = ToString(env, kind);
  const std::string options_str = ToString(env, options_json);
  uint64_t handle = 0;
  char *error = nullptr;
  const int32_t status = SherpaOnnxBridgeCreate(
      kind_str.c_str(), options_str.c_str(), &handle, &error);
  if (status != SHERPA_ONNX_BRIDGE_OK) {
    ThrowIllegalState(env, error);
    return 0;
  }
  return static_cast<jlong>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_k2fsa_sherpa_onnx_kmp_internal_JniPortableTransport_nativeCall(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring operation,
    jstring arguments_json) {
  const std::string operation_str = ToString(env, operation);
  const std::string arguments_str = ToString(env, arguments_json);
  char *result = nullptr;
  char *error = nullptr;
  const int32_t status = SherpaOnnxBridgeCall(
      static_cast<uint64_t>(handle), operation_str.c_str(),
      arguments_str.c_str(), &result, &error);
  if (status != SHERPA_ONNX_BRIDGE_OK) {
    SherpaOnnxBridgeFree(result);
    ThrowIllegalState(env, error);
    return nullptr;
  }
  std::string result_str = result != nullptr ? result : "";
  SherpaOnnxBridgeFree(result);
  return NewString(env, result_str);
}

JNIEXPORT jstring JNICALL
Java_com_k2fsa_sherpa_onnx_kmp_internal_JniPortableTransport_nativePushAudio(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring operation,
    jint sample_rate, jfloatArray samples) {
  const std::string operation_str = ToString(env, operation);
  const jsize size = samples != nullptr ? env->GetArrayLength(samples) : 0;
  jfloat *data = samples != nullptr
                     ? env->GetFloatArrayElements(samples, nullptr)
                     : nullptr;
  if (samples != nullptr && data == nullptr) {
    return nullptr;
  }

  char *error = nullptr;
  const int32_t status = SherpaOnnxBridgePushAudio(
      static_cast<uint64_t>(handle), operation_str.c_str(),
      static_cast<int32_t>(sample_rate), data, static_cast<int32_t>(size),
      &error);

  if (data != nullptr) {
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
  }

  if (status != SHERPA_ONNX_BRIDGE_OK) {
    ThrowIllegalState(env, error);
    return nullptr;
  }
  return NewString(env, R"({"ok":true})");
}

JNIEXPORT jobjectArray JNICALL
Java_com_k2fsa_sherpa_onnx_kmp_internal_JniPortableTransport_nativePullAudio(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring operation,
    jstring arguments_json) {
  const std::string operation_str = ToString(env, operation);
  const std::string arguments_str = ToString(env, arguments_json);

  int32_t sample_rate = 0;
  int32_t num_samples = 0;
  float *samples = nullptr;
  char *result = nullptr;
  char *error = nullptr;
  const int32_t status = SherpaOnnxBridgePullAudio(
      static_cast<uint64_t>(handle), operation_str.c_str(),
      arguments_str.c_str(), &sample_rate, &samples, &num_samples, &result,
      &error);

  if (status != SHERPA_ONNX_BRIDGE_OK) {
    SherpaOnnxBridgeFreeAudio(samples);
    SherpaOnnxBridgeFree(result);
    ThrowIllegalState(env, error);
    return nullptr;
  }

  // The reply is a fixed three-element array:
  //   [0] java.lang.Integer sample rate
  //   [1] float[] samples (never null; empty when the operation produced none)
  //   [2] java.lang.String result JSON
  //
  // A dedicated holder class would need Kotlin/JVM-side registration for no
  // benefit: the transport immediately converts it to BridgeReply.
  jclass object_class = env->FindClass("java/lang/Object");
  if (object_class == nullptr) {
    SherpaOnnxBridgeFreeAudio(samples);
    SherpaOnnxBridgeFree(result);
    return nullptr;
  }
  jobjectArray reply =
      env->NewObjectArray(3, object_class, nullptr);
  env->DeleteLocalRef(object_class);
  if (reply == nullptr) {
    SherpaOnnxBridgeFreeAudio(samples);
    SherpaOnnxBridgeFree(result);
    return nullptr;
  }

  jclass integer_class = env->FindClass("java/lang/Integer");
  if (integer_class != nullptr) {
    jmethodID integer_init =
        env->GetStaticMethodID(integer_class, "valueOf", "(I)Ljava/lang/Integer;");
    if (integer_init != nullptr) {
      jobject boxed = env->CallStaticObjectMethod(integer_class, integer_init,
                                                  static_cast<jint>(sample_rate));
      if (boxed != nullptr) {
        env->SetObjectArrayElement(reply, 0, boxed);
        env->DeleteLocalRef(boxed);
      }
    }
    env->DeleteLocalRef(integer_class);
  }

  jfloatArray waveform = env->NewFloatArray(num_samples > 0 ? num_samples : 0);
  if (waveform != nullptr) {
    if (num_samples > 0 && samples != nullptr) {
      env->SetFloatArrayRegion(waveform, 0, num_samples, samples);
    }
    env->SetObjectArrayElement(reply, 1, waveform);
    env->DeleteLocalRef(waveform);
  }

  jstring json = NewString(env, result != nullptr ? result : "");
  if (json != nullptr) {
    env->SetObjectArrayElement(reply, 2, json);
    env->DeleteLocalRef(json);
  }

  SherpaOnnxBridgeFreeAudio(samples);
  SherpaOnnxBridgeFree(result);
  return reply;
}

JNIEXPORT void JNICALL
Java_com_k2fsa_sherpa_onnx_kmp_internal_JniPortableTransport_nativeDestroy(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
  SherpaOnnxBridgeDestroy(static_cast<uint64_t>(handle));
}

}  // extern "C"
