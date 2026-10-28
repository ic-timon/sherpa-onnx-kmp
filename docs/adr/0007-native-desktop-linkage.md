# 0007: Native Desktop Linking and Packaging

Status: Superseded by ADR 0008 for the Linux decision; the Windows decision stands

Date: 2026-10-28

## Context

ADR 0002 places Linux x64/arm64 and Windows x64 on the Kotlin/Native cinterop
transport shared with Apple. The v0.4.0 Apple work proved that transport and
packaging model on Apple, where a single aggregate static archive is embedded in
an XCFramework. Native desktop has no equivalent bundle format: Linux relies on
ELF search paths and Windows relies on DLL discovery next to the executable.

Two questions had to be answered before 0.5.0 could ship:

1. Should the bridge be linked statically or dynamically on each desktop OS?
2. How does the Kotlin/Native test binary and a host application find the
   native libraries at load time?

## Decision

### Linux uses a shared bridge library

Superseded by ADR 0008. The aggregate static archive described below was
abandoned after CI showed the C++ graph is compiled against the build host's
glibc/libstdc++, which Kotlin/Native's glibc 2.19 konan sysroot cannot link.
Linux now ships `libsherpa-onnx-kmp-native-desktop.so` for the same reasons
Windows ships a DLL.

The original decision is retained below for context.

`sherpa-onnx-portable-bridge-static`, `sherpa-onnx-core`, its support libraries,
and the static ONNX Runtime were merged into one archive per architecture:

```text
build/native-desktop/linux-x64/lib/libsherpa-onnx-kmp-native-desktop.a
build/native-desktop/linux-arm64/lib/libsherpa-onnx-kmp-native-desktop.a
```

Kotlin/Native links it directly, so a consumer links exactly one archive and
inherits ONNX Runtime. This mirrors the Apple decision and keeps the Kotlin
linker line free of library-order dependencies, which are fragile for static
C++ archives.

Rationale: glibc-based Linux has no stable ABI guarantee across distributions.
Shipping a statically linked bridge removes a runtime `.so` search path problem
and keeps the artifact set to one file.

### Windows uses a shared bridge DLL with static ONNX Runtime

The bridge is built with `BUILD_SHARED_LIBS=ON`, producing:

```text
build/native-desktop/windows-x64/lib/sherpa-onnx-portable-bridge.dll
```

ONNX Runtime remains static and is therefore carried inside the DLL. Only the
bridge DLL ships alongside the application; there is no separate
`onnxruntime.dll`.

Rationale: MSVC and MinGW do not agree on C++ runtime symbol mangling and
allocation ownership across a static archive boundary. A DLL with a plain C ABI
avoids that class of defect. Windows also has no `libtool -static` equivalent,
and `ar`-based merging of MSVC-produced archives is not reliable, so DLlexport
is the lower-risk path. Kotlin/Native adds `-Wl,-rpath,$ORIGIN` for the
executable layout and the `lib/` subdirectory.

## Consequences

- The Kotlin/Native cinterop transport is identical on Apple, Linux, and
  Windows. Only linking and packaging differ, as ADR 0002 requires.
- Linux artifacts are one shared library with no external ONNX Runtime
  dependency (superseded from "single-file static archives" by ADR 0008).
- Windows artifacts are one DLL with no external ONNX Runtime dependency.
- Runtime dependency inspection is part of CI: both platforms must expose
  `SherpaOnnxBridgeAbiVersion` and `OrtGetApiBase`, and neither may depend on a
  separate ONNX Runtime binary.
- `linuxArm64` requires an aarch64 host because the Kotlin/Native and CMake
  builds both run natively; cross-compiling the full dependency graph was not
  worth the maintenance cost.
- Windows ARM64 remains a JVM target, not a Kotlin/Native target.
