add_executable(sherpa-onnx-portable-bridge-test
  bridge_test.cc
)
target_include_directories(sherpa-onnx-portable-bridge-test
  PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../src
)
target_link_libraries(sherpa-onnx-portable-bridge-test
  PRIVATE
    sherpa-onnx-portable-bridge-core
)
add_test(NAME sherpa-onnx-portable-bridge-test
         COMMAND sherpa-onnx-portable-bridge-test)
