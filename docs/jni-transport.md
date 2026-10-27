# JNI Transport

Status: v0.3.0

## Layout

The JNI transport is shared by the JVM and Android targets:

```text
jvmSharedMain
  JniPortableTransport
  NativeLoader
  FileModelStore
  createSherpaRuntime()
```

Android adds `AssetModelStore` for APK assets.

## Native pipeline

```text
Kotlin common engine
  -> JniPortableTransport
  -> sherpa-onnx-kmp-jni
  -> sherpa-onnx-portable-bridge
  -> sherpa-onnx core
```

The JNI library is intentionally thin. It maps Kotlin strings and Float32
arrays to the Portable Bridge and throws `IllegalStateException` on bridge
failure. The Kotlin layer translates that to `SherpaOnnxException`.

## Native library discovery

`NativeLoader` supports:

1. `sherpa.onnx.kmp.native.path` system property.
2. `SHERPA_ONNX_KMP_NATIVE_PATH` environment variable.
3. A platform native JAR on the classpath.
4. `System.loadLibrary("sherpa-onnx-kmp-jni")`.

Platform native JAR layout:

```text
sherpa-onnx/native/osx-aarch64/libsherpa-onnx-kmp-jni.dylib
sherpa-onnx/native/osx-aarch64/libsherpa-onnx-portable-bridge.dylib
```

Android loads from `jni/<abi>` inside the AAR.

## Android packaging

The AAR contains, for each supported ABI:

- `libsherpa-onnx-kmp-jni.so`
- `libsherpa-onnx-portable-bridge.so`
- `libonnxruntime.so`
- `libc++_shared.so`

Supported ABIs:

- arm64-v8a
- armeabi-v7a
- x86
- x86_64

All 64-bit native libraries are linked with 16 KB-compatible ELF segment
alignment.

## JVM packaging

The JVM class artifact and native artifacts are separate:

- `sherpa-onnx-kmp-jvm-<version>.jar`
- `sherpa-onnx-kmp-jvm-native-<platform>-<version>.jar`

The native JAR is selected by OS and architecture. JVM desktop users should add
the matching native JAR or configure one of the explicit native paths.
