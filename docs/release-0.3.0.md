# Release 0.3.0 Artifacts

Date: 2026-10-27

## Artifact inventory

| Artifact | Size | SHA256 |
|---|---:|---|
| `sherpa-onnx-kmp-android-0.3.0.aar` | 81.6 MiB | `7984b02f0c5c9f5ba30cdcf12d60e412b3c4182bbf1c4a72df54dcf476054f95` |
| `sherpa-onnx-kmp-jvm-0.3.0.jar` | 155 KiB | `79b87e8aedfa5df5c42e6d7c4fb9fac1fad9e47c7d4b4bf35262120131dc6dbd` |
| `sherpa-onnx-kmp-jvm-native-osx-aarch64-0.3.0.jar` | 8.5 MiB | `52c01782420c7a70cbbdb7f140a53154fe699de0bd5bd9ddc538ff91bc3e8733` |

## Android AAR

Supported ABIs:

- arm64-v8a
- armeabi-v7a
- x86
- x86_64

Each ABI contains exactly one copy of:

- `libsherpa-onnx-kmp-jni.so`
- `libsherpa-onnx-portable-bridge.so`
- `libonnxruntime.so`
- `libc++_shared.so`

## Verification

- JVM JNI offline ASR uses the pinned test model.
- Android arm64 emulator offline ASR uses the same model.
- Android x86_64 native cross-build passes.
- Android armv7 and x86 native cross-builds pass.
- 64-bit Android ELF segments are 16 KB aligned.
- Android sample app and JVM CLI build.
- Portable bridge CTest and pinned-model integration test pass.
