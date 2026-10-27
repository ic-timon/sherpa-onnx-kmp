# Platform Matrix

## Supported target families

### Android

- KMP target: `androidTarget`
- Transport: JNI
- Minimum SDK: 21
- ABI artifacts: arm64-v8a, armeabi-v7a, x86_64, x86
- Primary tested ABI: arm64-v8a
- Android 16 KB page compatibility is verified by 0x4000 ELF segment alignment
  for the 64-bit native libraries.

### iOS

- KMP targets: `iosArm64`, `iosSimulatorArm64`
- Optional: `iosX64` for Intel Mac simulator use
- Transport: Native cinterop
- Deployment target: to be finalized; align with upstream Apple artifacts

### macOS

- KMP target: `macosArm64`
- Transport: Native cinterop
- Intel macOS: JVM target, not Kotlin/Native `macosX64`

### Linux

- KMP targets: `linuxX64`, `linuxArm64`
- Transport: Native cinterop
- Library packaging remains an open decision.

### Windows

- KMP target: `mingwX64`
- Transport: Native cinterop
- Windows ARM64: JVM target
- Native Windows may use dynamic linking if MinGW/MSVC static linkage is unsafe.

### JVM

- KMP target: `jvm`
- Transport: JNI, shared with Android
- Use cases: Compose Desktop, Java interoperability, Windows ARM64, Intel macOS,
  server-side tools

### Web

- KMP target: Kotlin/JS
- Inference: Emscripten WebAssembly inside a Web Worker
- Not a Kotlin/Wasm wrapper target

## Explicitly unsupported

- Kotlin/Native Android targets as public KMP targets
- `wasmWasi`
- Mac Catalyst
- watchOS
- tvOS
- visionOS
- Windows ARM64 Kotlin/Native
- Linux ARM32
- Kotlin/Native `macosX64` as a core release target

## Provider policy

Provider availability is platform-specific. The common API exposes provider
names only as capability-checked strings. Platform-specific NPU configuration
must not leak into `commonMain`.
