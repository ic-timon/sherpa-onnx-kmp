add_library(sherpa-onnx-portable-bridge-core STATIC
  src/bridge.cc
)
target_include_directories(sherpa-onnx-portable-bridge-core
  PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
  PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)
target_link_libraries(sherpa-onnx-portable-bridge-core
  PUBLIC nlohmann_json::nlohmann_json
)
target_compile_definitions(sherpa-onnx-portable-bridge-core
  PRIVATE SHERPA_ONNX_PORTABLE_BRIDGE_BUILD=1
)

add_library(sherpa-onnx-portable-bridge SHARED
  src/bridge.cc
  src/sherpa_backend.cc
)
target_include_directories(sherpa-onnx-portable-bridge
  PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
  PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${SHERPA_ONNX_SOURCE_DIR}
)
set_target_properties(sherpa-onnx-portable-bridge PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
)
target_compile_definitions(sherpa-onnx-portable-bridge
  PRIVATE
    SHERPA_ONNX_PORTABLE_BRIDGE_BUILD=1
    SHERPA_ONNX_BRIDGE_WITH_SHERPA=1
)
target_link_libraries(sherpa-onnx-portable-bridge
  PRIVATE
    nlohmann_json::nlohmann_json
    sherpa-onnx-core
)
set_target_properties(sherpa-onnx-portable-bridge PROPERTIES
  OUTPUT_NAME sherpa-onnx-portable-bridge
)

add_executable(sherpa-onnx-portable-bridge-cli tools/bridge_cli.cc)
target_include_directories(sherpa-onnx-portable-bridge-cli
  PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${SHERPA_ONNX_SOURCE_DIR}
)
target_link_libraries(sherpa-onnx-portable-bridge-cli
  PRIVATE
    sherpa-onnx-portable-bridge
    sherpa-onnx-core
)

install(TARGETS sherpa-onnx-portable-bridge
  LIBRARY DESTINATION lib
  ARCHIVE DESTINATION lib
  RUNTIME DESTINATION bin
)
install(FILES include/sherpa-onnx-portable-bridge.h
  DESTINATION include
)
