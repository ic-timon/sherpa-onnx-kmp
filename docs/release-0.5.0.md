# Release 0.5.0: Native Desktop

Status: released

## Scope

Linux x64, Linux arm64, and Windows x64 run through the same Kotlin/Native
cinterop transport that Apple uses. Only linking and packaging differ.

## Artifacts

- `libsherpa-onnx-kmp-native-desktop.so`
  - `linux-x64`: shared bridge with sherpa-onnx. Ships with `libonnxruntime.so`
    from the pinned `glibc2_17` shared build.
  - `linux-arm64`: same pair, built by cross-compiling with the aarch64
    toolchain.
- `libonnxruntime.so`
  - Pinned ONNX Runtime for both Linux architectures. It is a sibling of the
    bridge, not embedded; see ADR 0008.
- `sherpa-onnx-portable-bridge.dll`
  - `windows-x64`: shared bridge with static ONNX Runtime embedded; no
    `onnxruntime.dll` is required.

See [ADR 0008](../docs/adr/0008-linux-shared-bridge-linkage.md) for the Linux
linkage decision, [ADR 0007](../docs/adr/0007-native-desktop-linkage.md) for
Windows, and [native desktop transport](../docs/native-desktop-transport.md) for
consumer packaging notes.

## Verification

- `linuxX64`, `linuxArm64`, and `mingwX64` compile and link in CI.
- The Linux shared library exports the full `SherpaOnnxBridge*` C ABI and
  records a `libonnxruntime` `NEEDED` entry.
- The Windows bridge DLL exposes the same ABI and needs no external ONNX
  Runtime.
- The shared contract suite passes on all three desktop targets.
- Real-model inference runs when `SHERPA_ONNX_KMP_TEST_MODEL_DIR` is set.
- Apple, JVM, and JS targets continue to compile and pass their tests.

## Reproduction

```bash
# Linux x64 host
./scripts/build-native-desktop.sh linux-x64
./gradlew :sherpa-onnx-kmp:linuxX64Test

# Linux arm64 host
./scripts/build-native-desktop.sh linux-arm64
./gradlew :sherpa-onnx-kmp:linuxArm64Test

# Windows host with MSYS2 MINGW64 in PATH
./scripts/build-native-desktop.sh windows-x64
./gradlew :sherpa-onnx-kmp:mingwX64Test
```

## Known limitations

- The Linux glibc floor is whatever the build host compiled against. The ONNX
  Runtime side is pinned to `glibc2_17`, but the C++ graph is compiled by the
  build host, so a distribution newer than the consumer's glibc is possible.
- `linuxArm64` cross-compiles on an x86_64 host and is tested under qemu. A
  native aarch64 host also works.
- Desktop real-model inference is exercised in CI on native runners. The
  development host is Apple Silicon and cannot link Linux or Windows binaries.
