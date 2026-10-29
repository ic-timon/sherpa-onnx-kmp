# Architecture Decision Records

Accepted ADRs describe decisions that implementation must preserve.

To change a decision:

1. Add a new ADR.
2. Mark the previous ADR as superseded by the new one.
3. Update the status document.

Records:

- [0001](0001-portable-bridge.md): Portable Bridge ABI
- [0002](0002-platform-targets.md): Platform target boundaries
- [0003](0003-public-api.md): Async task-oriented public API
- [0004](0004-web-runtime.md): Kotlin/JS plus Emscripten worker
- [0005](0005-model-store.md): ModelStore resource abstraction
- [0006](0006-apple-distribution.md): Self-contained Apple XCFramework
- [0007](0007-native-desktop-linkage.md): Native desktop linking and packaging
- [0008](0008-linux-shared-bridge-linkage.md): Linux ships a shared bridge library
- [0009](0009-drop-linux-arm64.md): Drop the `linuxArm64` Kotlin/Native target
