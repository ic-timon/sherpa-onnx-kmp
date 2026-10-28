# Native Desktop Transport

Status: v0.5.0

## Targets

- `linuxX64`
- `linuxArm64`
- `mingwX64` (Windows x64)

All three share the Kotlin/Native cinterop transport introduced for Apple. There
is no desktop-specific feature code: `NativePortableTransport` in `nativeMain`
is the only transport implementation for every Kotlin/Native target.

## Native pipeline

```text
Kotlin common engine
  -> NativePortableTransport        (nativeMain, shared with Apple)
  -> Kotlin/Native cinterop
     -> Linux: aggregate static archive
        Portable Bridge + sherpa-onnx + ONNX Runtime
     -> Windows: sherpa-onnx-portable-bridge.dll
        Portable Bridge + static ONNX Runtime inside the DLL
```

See [ADR 0007](adr/0007-native-desktop-linkage.md) for why Linux is static and
Windows is dynamic.

## Build

The libraries must be built on their own OS because both need the platform C++
runtime and the matching ONNX Runtime distribution.

```bash
# On Linux x64
./scripts/build-native-desktop.sh linux-x64

# On Linux arm64 (requires an aarch64 host)
./scripts/build-native-desktop.sh linux-arm64

# On Windows with MSYS2 MINGW64 in PATH
./scripts/build-native-desktop.sh windows-x64
```

Outputs:

```text
build/native-desktop/linux-x64/lib/libsherpa-onnx-kmp-native-desktop.a
build/native-desktop/linux-arm64/lib/libsherpa-onnx-kmp-native-desktop.a
build/native-desktop/windows-x64/lib/sherpa-onnx-portable-bridge.dll
```

## Runtime dependency inspection

Each platform must satisfy:

- The aggregate archive or DLL exposes exactly one
  `SherpaOnnxBridgeAbiVersion` and one `OrtGetApiBase`.
- Linux: no undefined ONNX Runtime symbol remains in the archive.
- Windows: no `onnxruntime.dll` is required next to the bridge DLL.

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

- **Linux**: link the aggregate archive once. No runtime library path is needed
  for the bridge or ONNX Runtime.
- **Windows**: ship `sherpa-onnx-portable-bridge.dll` next to the executable, or
  in a `lib/` directory reachable through the executable's directory. The
  Kotlin/Native binary already sets `$ORIGIN` and `$ORIGIN/lib` rpath entries.
