# Native Desktop Transport

Status: 2.0.0

## Targets

- `linuxX64`
- `mingwX64` (Windows x64)

`linuxArm64` was removed in 2.0.0. Kotlin/Native has no linux-aarch64 build host,
so the target could only be executed under qemu, where three of eight feature
suites fail. See [ADR 0009](adr/0009-drop-linux-arm64.md). The JNI
`linux-aarch64` JAR is unaffected: it does not use Kotlin/Native.

Both remaining targets share the Kotlin/Native cinterop transport introduced for
Apple. There is no desktop-specific feature code: `NativePortableTransport` in
`nativeMain` is the only transport implementation for every Kotlin/Native target.

## Native pipeline

```text
Kotlin common engine
  -> NativePortableTransport        (nativeMain, shared with Apple)
     -> Kotlin/Native cinterop
     -> Linux: libsherpa-onnx-kmp-native-desktop.so
        Portable Bridge + sherpa-onnx, with libonnxruntime.so alongside
     -> Windows: sherpa-onnx-portable-bridge.dll
        Portable Bridge + static ONNX Runtime inside the DLL
```

See [ADR 0008](adr/0008-linux-shared-bridge-linkage.md) for why Linux is shared
and [ADR 0007](adr/0007-native-desktop-linkage.md) for the Windows decision.

## Build

The libraries must be built on their own OS because both need the platform C++
runtime and the matching ONNX Runtime distribution.

```bash
# On Linux x64
./scripts/build-native-desktop.sh linux-x64

# On Windows with MSYS2 MINGW64 in PATH
./scripts/build-native-desktop.sh windows-x64

# aarch64 libraries, for the JNI JAR only (not a Kotlin/Native target)
./scripts/build-native-desktop.sh linux-arm64
```

Outputs:

```text
build/native-desktop/linux-x64/lib/libsherpa-onnx-kmp-native-desktop.so
build/native-desktop/linux-x64/lib/libsherpa-onnx-portable-bridge.so
build/native-desktop/linux-x64/lib/libonnxruntime.so
build/native-desktop/windows-x64/lib/sherpa-onnx-portable-bridge.dll
```

`linux-arm64` produces the same three `.so` files under
`build/native-desktop/linux-arm64/lib/`, but they are only consumed by the JNI
packaging step. Nothing links them into a Kotlin/Native binary since ADR 0009.

The two Linux bridge files are byte-identical copies. Kotlin/Native links
`-lsherpa-onnx-kmp-native-desktop`, but the library's `SONAME` — and therefore
the `DT_NEEDED` entry recorded in the test or application binary — is
`libsherpa-onnx-portable-bridge.so`, because that is CMake's output name. The
dynamic loader resolves `DT_NEEDED`, not the `-l` name, so a packaging step that
shipped only the renamed file produced an executable that loaded fine up to the
bridge and then exited with code 127:

```text
error while loading shared libraries: libsherpa-onnx-portable-bridge.so:
cannot open shared object file: No such file or directory
```

Both names are shipped to keep the CMake output name, the exported symbol set,
and the loader contract in agreement. `inspect_linux_shared_library` asserts that
the packaged directory contains the exact `SONAME` of the bridge it just built,
so the pair cannot drift apart again.

## Runtime dependency inspection

Each platform must satisfy:

- The shared library or DLL exports the full `SherpaOnnxBridge*` C ABI.
- Linux: the bridge records a `libonnxruntime` `NEEDED` entry and the runtime
  file ships beside it.
- Linux: the packaged directory contains the bridge's exact `SONAME` file.
- Windows: `OrtGetApiBase` is defined inside the DLL, and no `onnxruntime.dll`
  is required next to it.

CI runs these checks in `.github/workflows/platform-native.yml`.

## Model stores

```kotlin
public class FileModelStore(rootPath: String) : ModelStore
```

`FileModelStore` requires the root to be an existing directory and rejects a
model path containing `..`, so a caller cannot escape the configured root.
`BundleModelStore` is Apple-only because it depends on `NSBundle`.

## Tests

```bash
SHERPA_ONNX_KMP_TEST_MODEL_DIR=<pinned model dir> \
  ./gradlew :sherpa-onnx-kmp:linuxX64Test
SHERPA_ONNX_KMP_TEST_MODEL_DIR=<pinned model dir> \
  ./gradlew :sherpa-onnx-kmp:mingwX64Test
```

When `SHERPA_ONNX_KMP_TEST_MODEL_DIR` is unset, the real-model test is skipped
and the shared contract suite still runs.

## Packaging notes for consumers

- **Linux**: ship `libsherpa-onnx-kmp-native-desktop.so`,
  `libsherpa-onnx-portable-bridge.so`, and `libonnxruntime.so` next to the
  executable, or in a sibling `lib/` directory. The Kotlin/Native binary sets
  `$ORIGIN` and `$ORIGIN/lib` rpath entries. Both bridge names are required; see
  the `SONAME` note above. The effective glibc floor is the glibc the bridge was
  compiled against.
- **Windows**: ship `sherpa-onnx-portable-bridge.dll` next to the executable.
  Windows has no rpath; the loader searches the executable's directory and
  `PATH`. No MinGW C++ runtime DLL is needed because the bridge is a C ABI and
  the Kotlin/Native binary does not link `-lstdc++`.
