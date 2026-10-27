# 0005: ModelStore Resource Abstraction

Status: Accepted

Date: 2026-10-27

## Context

Android assets, iOS bundles, desktop files, and browser virtual filesystems do
not share a path model. Absolute paths in public configuration would force
platform-specific APIs and break web support.

## Decision

Public configuration refers to logical model files. A `ModelStore` resolves and
materializes them before a bridge call.

Platform implementations include:

- `AssetModelStore` for Android
- `BundleModelStore` for Apple
- `FileModelStore` for JVM and native desktop
- `HttpModelStore` or an in-memory store for web
- `InMemoryModelStore` for tests

## Consequences

The common API remains path-scheme independent. Android assets may be copied to
cache once. Web models are written to Emscripten MEMFS. Model validation and
checksums can be centralized in the ModelStore.
