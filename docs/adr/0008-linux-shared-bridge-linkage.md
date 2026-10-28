# 0008: Linux Ships a Shared Bridge Library

Status: Accepted

Date: 2026-10-28

Supersedes: ADR 0007, decision "Linux uses one aggregate static archive"

## Context

ADR 0007 chose a single aggregate static archive for Linux, merging
`sherpa-onnx-portable-bridge-static`, `sherpa-onnx-core`, its support
libraries, and the static ONNX Runtime into
`libsherpa-onnx-kmp-native-desktop.a`. The rationale was that statically
linking removes a runtime `.so` search-path problem.

The `platform-native` CI run on 2026-10-28 disproved that rationale. The
archive merge itself worked: the linker resolved members such as
`tensor_allocator.cc.o` out of the aggregate, so the merge was not the defect.
The link still failed, with every unresolved symbol coming from the C/C++
runtime rather than from sherpa-onnx or ONNX Runtime:

```text
ld.lld: error: undefined symbol: std::__throw_bad_array_new_length()
ld.lld: error: undefined symbol: std::filesystem::path::_List::_List()
ld.lld: error: undefined symbol: std::__cxx11::basic_ostringstream<...>::basic_ostringstream()
ld.lld: error: undefined symbol: __isoc23_strtol
ld.lld: error: undefined symbol: __isoc23_strtoull
ld.lld: error: undefined symbol: __libc_single_threaded
ld.lld: error: undefined symbol: _ZGVbN2v_cos
ld.lld: error: undefined symbol: _ZGVbN2v_sin
```

These are glibc 2.38+ (`__isoc23_*`, `__libc_single_threaded`) and
libmvec/libstdc++ symbols. Kotlin/Native links with the konan sysroot, which is
glibc 2.19, while the C++ graph was compiled by the build host's GCC against
its own newer glibc. The two cannot be reconciled by linker flags: the archive
was built against an ABI the Kotlin/Native linker does not have. Adding
`-lstdc++`, `-Wl,--start-group`, or a larger merge cannot fix an ABI mismatch.

The Windows decision in ADR 0007 already avoids this class of defect by using a
DLL, and it works for a different but related reason: C++ runtime ownership and
mangling do not cross a static archive boundary safely.

## Decision

Linux builds and ships a shared bridge library, matching the Windows model:

```text
build/native-desktop/linux-x64/lib/libsherpa-onnx-kmp-native-desktop.so
build/native-desktop/linux-arm64/lib/libsherpa-onnx-kmp-native-desktop.so
```

Configuration:

- `BUILD_SHARED_LIBS=ON` and target `sherpa-onnx-portable-bridge`.
- `SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF`, so upstream does not append
  `-static-libstdc++ -static-libgcc` and reintroduce the runtime mixing.
- ONNX Runtime stays static and is carried inside the `.so`. A dynamic
  dependency on a separate `libonnxruntime.so` is explicitly rejected in CI.
- The pinned ONNX Runtime package changed from `static_lib` to the
  `glibc2_17-Release` shared-ABI build, which is the lowest glibc the project
  targets.

Kotlin/Native links only the bridge C ABI:

```text
-Wl,-rpath,$ORIGIN -Wl,-rpath,$ORIGIN/lib -lsherpa-onnx-kmp-native-desktop
```

Only `SherpaOnnxBridge*` symbols cross the boundary. Every C++ and glibc
reference is resolved inside the `.so` at runtime from the host's system
libraries, which is exactly the ABI the library was built against.

## Consequences

- Linux and Windows now share one linkage model: a shared bridge with a plain C
  ABI and a static ONNX Runtime inside it. Only the file format differs.
- The static archive merge (`merge_linux_archive`, `sherpa_static_archives`)
  is deleted. It encoded an approach that cannot work, and keeping it would
  invite reintroduction.
- Host applications must ship `libsherpa-onnx-kmp-native-desktop.so` next to
  the executable or in a sibling `lib/` directory. The rpath entries cover both.
- CI asserts the exported C ABI, that `OrtGetApiBase` is present inside the
  library, and that `readelf -d` reports no `libonnxruntime` dependency.
- Linux arm64 is still built by cross-compiling with the aarch64 toolchain and
  executed under qemu. Cross-compiling the full dependency graph worked and is
  retained; only the linkage model changed.
- The glibc floor for a Linux consumer is the glibc the bridge was built
  against. The `glibc2_17` ONNX Runtime package keeps the ONNX Runtime side at
  2.17, but the C++ graph is compiled by the build host, so the effective floor
  is the runner's glibc. Packaging documentation must state this.
