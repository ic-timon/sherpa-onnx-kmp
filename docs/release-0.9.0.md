# Release 0.9.0: Release Hardening

Status: complete. Shipped in v1.0.0. No 0.9.0 tag or release was cut; this
document records the scope as it was designed and delivered on `main`.

## Scope

Everything that a green test suite cannot prove. A functional test opens a
handle, checks a result, and closes it; it says nothing about whether the handle
registry leaks, whether the header and the library agree on the ABI number, or
whether the shipped library still points at the machine that built it.

## What shipped

`scripts/check-release-hardening.sh` verifies three invariants:

1. **Header/library ABI agreement.** The runtime reports the same integer the
   header declares, so a mismatched transport fails fast instead of misreading a
   struct.
2. **Dependency audit.** No release library records a path outside the bundle.
3. **Bounded memory growth.** Repeated create/synthesize/destroy is measured by
   peak RSS in a fresh process per data point, comparing two warm points deep
   enough into the run that ONNX Runtime's arena has plateaued. On the
   development host the peaks were 288576 KiB at 384 iterations and 301344 KiB
   at 768, and 336528 KiB at 1536 versus 350208 KiB at 3072: the total delta
   stays near 13 MB while the iteration count grows eightfold, which is an arena
   saturating rather than a leak. Budgets are 512 KiB per iteration and 98304 KiB
   total.

`scripts/benchmark-release.sh` writes a versioned JSON snapshot with cold
start, core version, ONNX Runtime version, host, and the audio checksum.

`scripts/test-bridge-abi.sh` compiles a C probe — not C++, because the header
must be a valid C interface — and checks the ABI integer, the advertised
resources and operations, typed failures for an unknown kind and an unknown
option, a no-op destroy of an unknown handle, and real audio synthesis.

`scripts/check-android-aar.sh` inspects the packaged
`libsherpa-onnx-portable-bridge.so` in every ABI for the bridge entry points, so
a stale AAR from an earlier feature set cannot ship under a version that claims
the feature.

## Verification

- Every platform contract suite passes.
- No unresolved P0/P1 defects.
- Repeated create/close shows no unbounded native memory growth.
- Existing upstream bindings are unaffected.

## Recorded deviations

Two acceptance items are evidence rather than tooling, and are recorded instead
of quietly dropped:

- The API compatibility check is the frozen public surface in
  [`versioning.md`](versioning.md) plus the unchanged upstream bindings; the
  project does not have a separate binary API-diff tool.
- "Reproducible or documented inputs" is satisfied by pinned dependencies with
  checksums in `scripts/fetch-native-deps.sh`. The C++ graph itself is not
  bit-reproducible across build hosts, because the compiler and the glibc
  differ.

## Fixed during hardening

- The memory check originally compared one iteration against many and reported
  ONNX Runtime's arena as growth. It also treated `ru_maxrss` as one unit,
  though it is bytes on macOS and kilobytes on Linux, which inflated growth by
  1024x on macOS.
- Android native compilation could not find `onnxruntime_cxx_api.h`: the Android
  ONNX Runtime archive unpacks headers into `headers/`, not `include/`. The CMake
  lookup now accepts both.
