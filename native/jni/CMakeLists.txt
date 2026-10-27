add_library(sherpa-onnx-kmp-jni SHARED
  jni_bridge.cc
)
target_include_directories(sherpa-onnx-kmp-jni PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/../portable-bridge/include
)
if(NOT ANDROID)
  target_include_directories(sherpa-onnx-kmp-jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
target_link_libraries(sherpa-onnx-kmp-jni
  PRIVATE sherpa-onnx-portable-bridge
)
set_target_properties(sherpa-onnx-kmp-jni PROPERTIES
  OUTPUT_NAME sherpa-onnx-kmp-jni
  LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib
  RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib
)
if(APPLE)
  set_target_properties(sherpa-onnx-kmp-jni PROPERTIES
    BUILD_RPATH ${CMAKE_BINARY_DIR}/lib
    INSTALL_RPATH "@loader_path"
  )
elseif(UNIX)
  set_target_properties(sherpa-onnx-kmp-jni PROPERTIES
    BUILD_RPATH ${CMAKE_BINARY_DIR}/lib
    INSTALL_RPATH "$ORIGIN"
  )
endif()
