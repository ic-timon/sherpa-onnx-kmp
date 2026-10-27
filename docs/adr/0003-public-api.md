# 0003: Async Task-Oriented Public API

Status: Accepted

Date: 2026-10-27

## Context

The C API is stream- and pointer-oriented. The web implementation must cross a
Worker boundary, so a synchronous API cannot be implemented consistently across
all targets.

## Decision

The public KMP API is:

- Task-oriented rather than a C API mirror.
- Async-first: potentially blocking operations are `suspend`.
- Streaming-first where appropriate, using `Flow`.
- Explicitly closeable with idempotent `close()`.
- Capability-aware.
- Extensible through `extraOptions` and `rawJson`.

## Consequences

Native and JVM backends need a serialized coroutine execution context. The web
backend maps naturally to Worker request/response. API ergonomics are consistent
across platforms, and native handles remain hidden.
