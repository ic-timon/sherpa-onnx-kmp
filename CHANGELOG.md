# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Added

- Foundation KMP module and public API contracts.

## [0.2.0] - 2026-10-27

### Added

- Additive Portable Bridge C ABI for offline ASR.
- Flat string-valued option protocol backed by ParseOptions registration.
- Non-fatal `TrySetOption` upstream patch.
- Opaque bridge handles and internal resource registry.
- Stable structured offline ASR result schema with `rawJson` fallback.
- Host bridge CLI and native CTest suite.
- Pinned Persian ASR model integration test.
- Kotlin common option mapper for offline ASR.

### Integration evidence

- Bridge CTest passes.
- The pinned model produces a structured transcript through the bridge CLI.
- JVM common option mapping tests pass.

## [0.1.1] - 2026-10-27

### Fixed

- Replaced machine-specific npm mirror URLs in `kotlin-js-store/yarn.lock` with
  the official npm registry.
- Added a repository-level `.yarnrc` and CI lockfile portability check.

## [0.1.0] - 2026-10-27

- Initial public contract release.
- Added multi-target KMP compilation and contract tests.
- Added the end-to-end release roadmap through 1.0.0.
