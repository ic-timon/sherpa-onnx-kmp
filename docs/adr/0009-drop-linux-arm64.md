# 0009: Drop the linuxArm64 Kotlin/Native Target

Status: Accepted

Date: 2026-10-29

Supersedes: ADR 0002, platform list; ADR 0007, decision "`linuxArm64` requires
an aarch64 host"; ADR 0008, consequence "Linux arm64 is still built by
cross-compiling with the aarch64 toolchain and executed under qemu"

## Context

1.0.0 shipped a `sherpa-onnx-kmp-linux-arm64-1.0.0.zip` and a `linuxArm64`
Kotlin/Native target. Neither could be verified, and the 1.0.0 notes claimed
eight real-model features on every desktop, which was false for this slice.

The target cannot be executed on real hardware inside this project's CI:

- Kotlin/Native does not support `linux-aarch64` as a **build host**. Its
  supported hosts are Linux x86_64, Windows x86_64, macOS arm64, and macOS
  x86_64. On `ubuntu-24.04-arm`, `compileTestKotlinLinuxArm64` and
  `linkDebugTestLinuxArm64` are silently `SKIPPED`, and the job fails at
  "test binary was not produced". This was attempted and confirmed on
  commit `5c4e54c`.
- An Android arm64 emulator on a hosted runner is a different target and does
  not help here; `ubuntu-24.04-arm` has no `/dev/kvm`.

The only execution path is `qemu-aarch64-static` on an x86_64 runner, and there
the suite does not pass. On run 40855664206 the whole suite took roughly one
hour and three of eight real-model feature cases failed:

```text
FAILED  streamingAsrTranscribesChunkedAudio
        AssertionError: Streaming ASR produced no text for the pinned sample
FAILED  punctuationAndKeywordSpottingUsePinnedFixtures
        AssertionError: No keyword was detected in the pinned KWS sample
FAILED  speakerDiarizationSeparatesTwoSpeakers
        UncompletedCoroutinesError: waiting for 1m, test body did not complete
        (reported wall time 3293 s)
```

VAD, both TTS paths, speech enhancement, and offline ASR passed, and the
contract suite passed except for the diarization timeout. So this is not an ABI,
packaging, or symbol-resolution defect: the library loads and most inference
works. It is specifically fbank-based feature extraction or the pieces built on
it, under emulation.

That leaves an expensive red job whose failure mode is unattributable. It cannot
distinguish "arm64 is broken" from "qemu mis-emulates something", and no path in
this repository can produce the native run that would settle it. A published
artifact whose correctness is unknown is worse than an absent one: consumers
cannot tell whether a failure is theirs.

## Decision

Remove the `linuxArm64` Kotlin/Native target and its artifact.

- `kotlin { linuxArm64() }` and its cinterop configuration are deleted from
  `sherpa-onnx-kmp/build.gradle.kts`.
- `build_linux_arm64` and the `aarch64-linux-toolchain.cmake` file are deleted
  from `scripts/build-native-desktop.sh`.
- The `linux-arm64` jobs are deleted from `platform-native.yml` and
  `release.yml`.
- `sherpa-onnx-kmp-linux-arm64-*.zip` is no longer produced.

The JNI slice `sherpa-onnx-kmp-jvm-native-linux-aarch64-*.jar` is **retained**.
It does not go through Kotlin/Native: it is a plain CMake build of the bridge and
the JNI wrapper, which compiles and links natively on an arm64 runner. Its
constraint is different and it is not blocked by this ADR. It is documented as
build-verified and runtime-unverified rather than silently claimed as tested.

## Consequences

- Linux arm64 consumers of the Kotlin/Native transport have no artifact. They
  can use the JVM/Android path, where `linux-aarch64` and `arm64-v8a` JNI
  libraries still ship.
- The release manifest drops one archive. Consumers who pinned
  `sherpa-onnx-kmp-linux-arm64` must not upgrade to 2.0.0 without changing their
  dependency set; that is why this is a major release rather than a patch.
- `platform-native.yml` loses a job that took an hour and could not pass, so a
  red `platform-native` run now means something is actually wrong.
- ADR 0002's target list, ADR 0007's `linuxArm64` bullet, and ADR 0008's
  cross-compile consequence are superseded by this record. The Windows and
  Linux x64 decisions in those ADRs stand unchanged.
- Reintroducing the target requires a native linux-aarch64 Kotlin/Native build
  host, or upstream support for one. Until then the qemu result stays the only
  evidence, and it is not good enough to ship.
