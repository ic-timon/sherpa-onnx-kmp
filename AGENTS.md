# Project Instructions

This repository is the home of the Kotlin Multiplatform support for
sherpa-onnx. Read the project documents before planning or changing code.

## Required reading

1. `docs/design.md`
2. `docs/platform-matrix.md`
3. `docs/status.md`
4. The accepted records under `docs/adr/`

## Rules

- Keep the terminal architecture coherent; do not reintroduce per-platform
  feature implementations when a transport or bridge abstraction is required.
- Do not put Android or Apple framework types in `commonMain`.
- Do not change an accepted ADR silently. Add a new ADR that supersedes it.
- Update `docs/status.md` when implementation state or open decisions change.
- Prefer one shared option mapper and one shared result mapper in common code.
- Native handles and pointers must never appear in the public API.
- Public operations that can block or cross a browser worker must be suspendable.
- Model resources must go through the ModelStore abstraction, not ad-hoc
  absolute paths in public configuration.
- Do not edit the upstream sherpa-onnx repository from this repository unless
  the task explicitly asks for an upstream change.
