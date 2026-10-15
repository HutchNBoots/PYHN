# Groovebox (working title)

Android groovebox loosely inspired by the OP-1 and Roland S-1. Landscape, phone-first.
The product spec is `docs/vision-cx-spec.md`. Read it before planning any feature; it wins over assumptions.

## Status

- Phase: **week-1 spike** — prove low-latency audio on the reference device before any feature work.
- Reference device: **Samsung Galaxy S25+**. It is the only v1 target.
- Owner tests builds only and does not review code. Claude writes and reviews all code, so reviews must be strict.

## Stack

- Audio engine: C++17, Oboe (AAudio, low-latency exclusive mode), built with CMake via the NDK.
- App and UI: Kotlin, Jetpack Compose, single activity, landscape only.
- Build: Gradle (Kotlin DSL). Min SDK 31, target latest stable.
- Tests: GoogleTest for C++ (runs on host, no device), JUnit for Kotlin.

## Commands

The owner installs the **release** build (optimised, signed with the committed dev key in `keystore/`).
CI (`.github/workflows/build.yml`) runs every check below on each push and publishes the APK as a GitHub Release (`build-N`), which the owner installs from the phone.

- Build APK: `./gradlew assembleRelease` (output `app/build/outputs/apk/release/app-release.apk`)
- Install on device (with adb): `./gradlew installRelease`
- Kotlin tests: `./gradlew test`
- C++ tests (host): `cmake -S engine -B build/engine-host -DGROOVE_BUILD_TESTS=ON && cmake --build build/engine-host && ctest --test-dir build/engine-host --output-on-failure`
- C++ tests with sanitizers: add `-DGROOVE_SANITIZE=address,undefined` or `-DGROOVE_SANITIZE=thread` (use GCC; this container's clang has no sanitizer runtimes)

Cloud sessions can't download the Android SDK, NDK or Google Maven (`dl.google.com` is blocked), so Android builds only run in CI. The host C++ tests do run in the cloud session.

## Audio thread rules (non-negotiable)

- The audio callback never allocates, frees, locks, logs, does file or network I/O, or calls into the JVM.
- UI ↔ audio communication only through lock-free SPSC queues or atomics.
- The sequencer clock runs on the audio thread and is sample-accurate. The UI only reads it.
- Every DSP block can render offline into a buffer so it can be unit-tested on the host.
- Any change that touches the audio callback must say in its PR/commit how these rules were checked.

## Workflow

Use the Superpowers plugin for all non-trivial work:

1. `brainstorming` before any feature; check it against the spec.
2. `writing-plans`; save plans to `docs/plans/YYYY-MM-DD-<feature>.md`.
3. `subagent-driven-development`, one module per agent (engine, sequencer, ui, storage).
4. `test-driven-development` for sequencer logic and DSP: write the failing test first.
5. `requesting-code-review` before merging; review against the spec and the audio thread rules.
6. Finish each milestone with a build the owner can install and play on the S25+. Tests can't judge feel or latency.

## Design constraints

- One interaction grammar: four colour strips drive every screen; Shift gives a second layer.
- Never block sound: no dialogs or setup before the first note.
- Original names, graphics and engine names only. Do not copy Teenage Engineering or Roland branding, layouts or assets.

## Decisions (15 Oct 2026)

- Landscape only. Personal build first, Play Store later; keep code and assets licence-clean.
- Pricing undecided; no billing code in v1.
- Primary persona: the Noodler. No onboarding or tutorial in v1.
- MIDI and tilt control are v1.1. MIDI gear for testing is still an open question.

## Repo layout

```
app/            Kotlin + Compose UI
engine/core/    platform-independent C++ DSP (host-testable, no Android/Oboe)
engine/android/ Oboe stream + JNI bridge
engine/tests/   GoogleTest suite for engine/core
docs/           spec, plans, architecture decisions (docs/adr/)
keystore/       public dev signing key (not for the Play Store)
```
