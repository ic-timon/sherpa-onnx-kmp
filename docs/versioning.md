# Versioning and Compatibility

Status: 1.0.0

This document is the compatibility contract for `sherpa-onnx-kmp` 1.x. It
describes what a version number means for each of the three artifacts a consumer
depends on: the Kotlin API, the Portable Bridge C ABI, and the sherpa-onnx core.

## Three version axes

```text
sherpa-onnx-kmp  1.0.0   Kotlin semver, what consumers declare
bridge ABI       2       integer, checked at runtime by every transport
sherpa-onnx      2.0.1   upstream core tag, pinned per release
```

They are related but not interchangeable. A bridge ABI bump does not require a
Kotlin major bump when the change is additive and both sides are upgraded
together, because transports check the ABI at startup and refuse to run against
an incompatible library.

## Kotlin API stability

Semantic versioning applies to the `com.k2fsa.sherpa.onnx.kmp` packages.

Guaranteed for the whole 1.x line:

- Existing public declarations keep their binary and source compatibility.
- Sealed and enum hierarchies are only extended in a minor release, and always
  with a default path that existing `when` branches still compile against.
  `SherpaFeature` is the case to watch: a new feature becomes a new entry, so a
  consumer's exhaustive `when` over it must add a branch. This is called out in
  every changelog entry that adds one.
- `extraOptions` and `rawJson` keep accepting values the library does not model
  yet, so a new upstream option never needs a KMP release.
- Suspending signatures do not become blocking, and `Flow` results do not become
  callbacks. Both are part of the contract.

Breaking changes require a major release or an ADR that supersedes the current
design, per `docs/adr/`.

### What minor releases may add

- New features in `SherpaFeature` and new `create*` methods on `SherpaRuntime`.
- New typed config fields with defaults, and new typed result fields with
  defaults.
- New optional parameters on existing functions.
- New capability names and providers.

### What patch releases may change

- Bug fixes that restore documented behavior.
- Dependency bumps of `kotlinx-coroutines` and `kotlinx-serialization` within
  their own semver ranges.
- Documentation, benchmark numbers, and error message wording. Error *types*
  are stable; message text is not a contract.

## Bridge ABI stability

The bridge header is `native/portable-bridge/include/sherpa-onnx-portable-bridge.h`.
`SHERPA_ONNX_BRIDGE_ABI_VERSION` is an integer that is checked at runtime by
`SherpaOnnxBridgeAbiVersion()`.

The rules:

- Adding an entry point, a resource kind, or an operation is additive: the ABI
  version is incremented, but existing entry points keep their signature and
  meaning. Old transports keep working; new transports see the higher number.
- Adding a field to a result JSON object is additive and does not change the ABI
  version. Consumers must ignore unknown fields, which the typed mappers already
  do.
- Removing or renaming an entry point, changing a parameter or return type, or
  changing the meaning of an existing operation requires a new major ABI version
  and a Kotlin major release at the same time.
- `schemaVersion` in result objects is separate. It increments only when a field
  changes meaning rather than being added.

The current values are:

```text
ABI version    2
schema version 1
```

Every transport validates the ABI at runtime. A Kotlin library paired with an
older native library fails at startup with a typed exception instead of reading
a struct incorrectly.

## sherpa-onnx core range

Each release pins one upstream tag. The 1.0.0 line is built and tested against
sherpa-onnx `v2.0.1`.

- Patch releases of the core (`2.0.x`) are expected to work and are picked up by
  a new KMP release after the bridge test suite passes.
- A minor core release (`2.1.0`) may add options that the KMP layer does not
  model. They remain usable through `extraOptions`; typed support follows in a
  KMP minor release.
- A core major release is treated as a new compatibility target and gets its own
  KMP minor release with an ADR if the bridge needs a change.

The bridge does not reimplement core behavior. It constructs upstream configs,
applies options through the registered `ParseOptions` keys, and validates. That
is what keeps a core upgrade a build-time concern rather than a rewrite.

## Deprecation policy

- A deprecated public declaration keeps working for the whole 1.x line.
- Deprecation happens in a minor release with `@Deprecated` and a `ReplaceWith`
  pointing at the replacement.
- Removal happens no earlier than the next major release.
- A feature that cannot be supported on a platform is not deprecated; it is
  reported as absent from `capabilities`, and creation fails with a typed
  exception. See `docs/platform-matrix.md`.

## Support window

- The newest minor release receives fixes.
- The previous minor release receives critical fixes for 90 days after the
  newer release ships.
- Older minors receive security fixes only, at the maintainers' discretion.

## Release evidence

A release is not complete until `docs/releases.md` is satisfied: passing
contract suites, a pinned model manifest, an artifact inventory with checksums,
an updated platform matrix, and updated `CHANGELOG.md` and `docs/status.md`.
