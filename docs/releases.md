# Release Process

## Version gates

Every release must satisfy the acceptance criteria in `roadmap.md`.

The compatibility contract for the 1.x line is in
[`versioning.md`](versioning.md). It states what each version axis means: the
Kotlin API, the bridge ABI integer, and the pinned sherpa-onnx core.

## Required evidence

- Passing contract tests for every target family claimed by the release.
- A pinned test model checksum manifest when real inference is required.
- Artifact inventory with sizes and checksums.
- Platform support matrix updated for any capability changes.
- `CHANGELOG.md` and `docs/status.md` updated.

## Tagging

- Tags use `vMAJOR.MINOR.PATCH`.
- Tags must point at commits on `main`.
- Release notes link to the roadmap section and the relevant ADR.
- Apple releases attach the signed XCFramework ZIP and SHA-256 checksum.
- A release with an unmet physical-device gate is documented as preview and must
  not be presented as stable.

## Automated gates

These scripts are the release evidence. `portable-bridge.yml` and `web.yml` run
them on every relevant change; a release re-runs them on the tagged commit.

| Script | What it proves |
| --- | --- |
| `scripts/test-bridge-integration.sh` | the pinned ASR model transcribes through the C ABI |
| `scripts/test-bridge-abi.sh` | ABI integer, 10 resources, 35 operations, typed failures, real TTS synthesis |
| `scripts/check-release-hardening.sh` | header/library ABI agreement, dependency audit, bounded memory growth |
| `scripts/benchmark-release.sh` | versioned performance snapshot |
| `scripts/check-android-aar.sh` | AAR ABI inventory plus exported bridge symbols per ABI |
| `scripts/check-web-capabilities.cjs` | the web module advertises the same resources as native |
| `scripts/test-web-browser.sh` | the pinned model transcribes in a real browser |
| `scripts/check-lockfiles.sh` | the JS lockfile resolves from the official npm registry, not a mirror |
