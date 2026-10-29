# 0002: Platform Target Boundaries

Status: Accepted; the `linuxArm64` entry is superseded by ADR 0009

Date: 2026-10-27

## Context

Kotlin Multiplatform supports JVM, JavaScript, and Kotlin/Native targets, but it
does not make every native target appropriate for every deployment model.

## Decision

- Android uses `androidTarget` and JNI.
- Android Kotlin/Native targets are not public KMP targets.
- iOS uses Kotlin/Native.
- Native desktop uses Kotlin/Native for macOS arm64, Linux x64/arm64, and
  Windows x64.
- JVM is retained for Android, Compose Desktop, Windows ARM64, Intel macOS, and
  Java interoperability.
- Web uses Kotlin/JS with an Emscripten Wasm inference engine.
- `wasmWasi`, Mac Catalyst, watchOS, tvOS, visionOS, Windows ARM64 Kotlin/Native,
  and Linux ARM32 are out of scope.

## Consequences

Android and JVM share a JNI transport. Apple and native desktop share a cinterop
transport. Web has one JS transport. No platform gets its own feature
implementation.
