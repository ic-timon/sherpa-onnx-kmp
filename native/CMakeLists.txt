cmake_minimum_required(VERSION 3.20)
project(sherpa_onnx_kmp_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(SHERPA_ONNX_KMP_BUILD_TESTS "Build portable bridge tests" ON)
option(SHERPA_ONNX_KMP_BUILD_JNI "Build the JVM/Android JNI transport" ON)

# Set these before adding the sherpa-onnx project.
set(SHERPA_ONNX_ENABLE_PYTHON OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_CHECK OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PORTAUDIO OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_JNI OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_C_API OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_WEBSOCKET OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_GPU OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TTS OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_BINARY OFF CACHE BOOL "" FORCE)

# v0.4 Apple builds support CPU only. The static macOS ONNX Runtime package
# also does not include the CoreML provider factory, so keep the bridge
# self-contained instead of leaving an unresolved CoreML symbol.
if(APPLE AND NOT BUILD_SHARED_LIBS)
  add_compile_definitions(SHERPA_ONNX_DISABLE_COREML)
endif()

set(SHERPA_ONNX_SOURCE_DIR "" CACHE PATH
    "Path to a sherpa-onnx source tree with the TrySetOption patch applied")

if(SHERPA_ONNX_SOURCE_DIR)
  message(STATUS "Using sherpa-onnx source: ${SHERPA_ONNX_SOURCE_DIR}")
  add_subdirectory(${SHERPA_ONNX_SOURCE_DIR}
                   ${CMAKE_BINARY_DIR}/sherpa-onnx)
else()
  include(FetchContent)
  FetchContent_Declare(
    sherpa_onnx
    GIT_REPOSITORY https://github.com/k2-fsa/sherpa-onnx.git
    GIT_TAG v2.0.1
    GIT_SHALLOW TRUE
    PATCH_COMMAND git apply --whitespace=fix
      ${CMAKE_CURRENT_SOURCE_DIR}/../patches/0001-parse-options-try-set-option.patch
  )
  FetchContent_MakeAvailable(sherpa_onnx)
  set(SHERPA_ONNX_SOURCE_DIR ${sherpa_onnx_SOURCE_DIR})
endif()

add_subdirectory(portable-bridge)

if(SHERPA_ONNX_KMP_BUILD_JNI)
  if(NOT ANDROID)
    find_package(JNI REQUIRED)
  endif()
  add_subdirectory(jni)
endif()

if(SHERPA_ONNX_KMP_BUILD_TESTS)
  enable_testing()
  add_subdirectory(portable-bridge/tests)
endif()
