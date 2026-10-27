# Release Process

## Version gates

Every release must satisfy the acceptance criteria in `roadmap.md`.

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
