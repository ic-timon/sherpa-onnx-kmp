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
  # Emscripten does not resolve exports through ELF visibility: `-sEXPORTED_FUNCTIONS`
  # is the only thing that decides what the Wasm module exports. Setting
  # `CXX_VISIBILITY_PRESET hidden` here would hide the bridge's `extern "C"`
  # entry points from the export machinery and produce a module that exports
  # nothing, so it is deliberately not set for this target.
  set_target_properties(sherpa-onnx-bridge-web PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/web
    OUTPUT_NAME sherpa-onnx-bridge
    SUFFIX ".js"
  )

  # Emscripten linker settings. The exported set is exactly the bridge C ABI
  # plus the two memory helpers the Kotlin/JS worker needs to copy audio into
  # the Wasm heap. Capability discovery on the Web must reflect this list.
  #
  # The options go through `SHELL:` so each one arrives at `emcc` as a single
  # argument with its commas intact. Without it, CMake re-parses the comma
  # separated value and emcc receives a truncated `-sEXPORTED_FUNCTIONS`, which
  # produces a module with an empty export table instead of failing loudly.
  set(SHERPA_ONNX_BRIDGE_WEB_EXPORTS
    "_SherpaOnnxBridgeAbiVersion,_SherpaOnnxBridgeInfoJson,_SherpaOnnxBridgeCreate,_SherpaOnnxBridgeCall,_SherpaOnnxBridgePushAudio,_SherpaOnnxBridgeDestroy,_SherpaOnnxBridgeFree,_malloc,_free")

  target_link_options(sherpa-onnx-bridge-web PRIVATE
    "SHELL:-O3"
    "SHELL:-sWASM=1"
    "SHELL:-sMODULARIZE=1"
    "SHELL:-sEXPORT_NAME=createSherpaOnnxBridgeModule"
    "SHELL:-sEXPORT_ES6=0"
    "SHELL:-sENVIRONMENT=worker"
    "SHELL:-sALLOW_MEMORY_GROWTH=1"
    "SHELL:-sINITIAL_MEMORY=268435456"
    "SHELL:-sMAXIMUM_MEMORY=2147483648"
    "SHELL:-sFILESYSTEM=1"
    "SHELL:-sASSERTIONS=0"
    "SHELL:-sEXPORTED_FUNCTIONS=${SHERPA_ONNX_BRIDGE_WEB_EXPORTS}"
    "SHELL:-sEXPORTED_RUNTIME_METHODS=UTF8ToString,getValue,setValue,FS"
  )

  # At `-O3` Emscripten runs `wasm-metadce` and then renames Wasm imports and
  # exports to single letters. The two settings that control this
  # (`MINIFY_WASM_IMPORTS_AND_EXPORTS`, `MINIFY_WASM_EXPORT_NAMES`) live in
  # `settings_internal.js` and are rejected on the command line, so this cannot
  # be turned off from here. It also does not need to be.
  #
  # The renaming is an internal detail of the JS+Wasm pair: the pass rewrites
  # every `wasmExports["SherpaOnnxBridgeCreate"]` lookup in the generated glue
  # to match, while the glue still declares and publishes the stable
  # `Module['_SherpaOnnxBridgeCreate']` names. That glue surface — not the Wasm
  # export table — is what the worker calls and what the C ABI contract covers.
  # `scripts/build-web-emscripten.sh` verifies it.

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
