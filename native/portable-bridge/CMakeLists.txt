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
  LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib
  RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib
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

add_library(sherpa-onnx-portable-bridge-static STATIC
  src/bridge.cc
  src/sherpa_backend.cc
)
target_include_directories(sherpa-onnx-portable-bridge-static
  PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
  PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${SHERPA_ONNX_SOURCE_DIR}
)
set_target_properties(sherpa-onnx-portable-bridge-static PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib
)
target_compile_definitions(sherpa-onnx-portable-bridge-static
  PRIVATE
    SHERPA_ONNX_PORTABLE_BRIDGE_BUILD=1
    SHERPA_ONNX_BRIDGE_WITH_SHERPA=1
)
target_link_libraries(sherpa-onnx-portable-bridge-static
  PRIVATE
    nlohmann_json::nlohmann_json
    sherpa-onnx-core
)
set_target_properties(sherpa-onnx-portable-bridge-static PROPERTIES
  OUTPUT_NAME sherpa-onnx-portable-bridge
)

# WebAssembly build of the same bridge.
#
# Declared as an executable with `SUFFIX .js` so CMake drives Emscripten's
# linker directly. Running `emcc` by hand over `lib/*.a` loses the transitive
# static link order, which a C++ archive graph needs; going through CMake keeps
# every `target_link_libraries` edge intact.
if(EMSCRIPTEN)
  add_executable(sherpa-onnx-bridge-web src/bridge.cc src/sherpa_backend.cc)
  target_include_directories(sherpa-onnx-bridge-web
    PRIVATE
      ${CMAKE_CURRENT_SOURCE_DIR}/include
      ${CMAKE_CURRENT_SOURCE_DIR}/src
      ${SHERPA_ONNX_SOURCE_DIR}
  )
  target_compile_definitions(sherpa-onnx-bridge-web
    PRIVATE
      SHERPA_ONNX_PORTABLE_BRIDGE_BUILD=1
      SHERPA_ONNX_BRIDGE_WITH_SHERPA=1
  )
  target_link_libraries(sherpa-onnx-bridge-web
    PRIVATE
      nlohmann_json::nlohmann_json
      sherpa-onnx-core
  )
  set_target_properties(sherpa-onnx-bridge-web PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/web
    OUTPUT_NAME sherpa-onnx-bridge
    SUFFIX ".js"
  )

  # Emscripten linker settings. The exported set is exactly the bridge C ABI
  # plus the two memory helpers the Kotlin/JS worker needs to copy audio into
  # the Wasm heap. Capability discovery on the Web must reflect this list.
  target_link_options(sherpa-onnx-bridge-web PRIVATE
    -O3
    -sWASM=1
    -sMODULARIZE=1
    -sEXPORT_NAME=createSherpaOnnxBridgeModule
    -sEXPORT_ES6=0
    -sENVIRONMENT=worker
    -sALLOW_MEMORY_GROWTH=1
    -sINITIAL_MEMORY=268435456
    -sMAXIMUM_MEMORY=2147483648
    -sFILESYSTEM=1
    -sASSERTIONS=0
    "-sEXPORTED_FUNCTIONS=['_SherpaOnnxBridgeAbiVersion','_SherpaOnnxBridgeInfoJson','_SherpaOnnxBridgeCreate','_SherpaOnnxBridgeCall','_SherpaOnnxBridgePushAudio','_SherpaOnnxBridgeDestroy','_SherpaOnnxBridgeFree','_malloc','_free']"
    "-sEXPORTED_RUNTIME_METHODS=['UTF8ToString','getValue','setValue','FS']"
  )
endif()

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
