# 0001: Portable Bridge ABI

Status: Accepted

Date: 2026-10-27

## Context

Directly wrapping the sherpa-onnx C API from KMP would require separate config
marshalling for JNI, Kotlin/Native, and JavaScript. Every new config field would
need changes in several bindings.

## Decision

Add an additive Portable Bridge C ABI.

- Control plane: flat string-valued JSON options.
- Data plane: raw Float32 buffers.
- Result plane: versioned JSON plus raw buffer handles where needed.
- Handles: opaque `uint64_t`.
- Config application: use the registered `ParseOptions` keys and a non-fatal
  setter.
- The bridge is a new CMake target; it does not replace the existing C API.

## Consequences

Benefits:

- One config implementation for all transports.
- One result schema.
- New upstream options can be consumed through `extraOptions` without immediate
  KMP changes.
- Platform transports remain feature-independent.

Costs:

- A new native ABI and schema must be versioned and tested.
- The bridge requires careful handle and buffer ownership.
- A non-fatal parser path must be added.
