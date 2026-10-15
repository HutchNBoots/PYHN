# Week-1 latency spike

Spec reference: `docs/vision-cx-spec.md`, "Reference device and technical constraints" and next step 5.

## Goal

An installable APK for the Galaxy S25+ that plays one oscillator from a full-width
two-octave keyboard and reports the measured latency. The result is a go or no-go
on the tap-to-tone target (35 ms or less; stretch goal 25 ms).

Out of scope: sequencer, strips grammar, drums, tape, FX, storage, MIDI, tilt. This
build is a measuring instrument, not the product UI.

## What gets measured

| Measurement | How | Where |
| --- | --- | --- |
| Round-trip latency | OboeTester "Round Trip Latency" test, wired headphones (with loopback adapter) and built-in speaker/mic | Owner, on device (no code) |
| Estimated tap-to-tone (minus touch-panel scan) | Touch event kernel timestamp (`MotionEvent.getEventTimeNanos`, `CLOCK_MONOTONIC`) compared with the presentation time of the note's first frame, worked out from `AudioStream::getTimestamp(CLOCK_MONOTONIC)` | App, live, per tap |
| Stream configuration | Audio API, sharing mode, performance mode, sample rate, burst size, buffer size, Oboe's output latency estimate, xrun count | App, live |

Tap-to-tone as felt = the app figure + touch-panel scan time (a few ms, not visible to software).
Compare the app figure with the round trip from OboeTester to cross-check it.

## Design

- `engine/core`: plain C++17 with no Android dependencies, so it builds and tests on the host.
  - `SpscQueue`: fixed-capacity lock-free queue, used UI→audio (note events) and audio→UI (note-on render records).
  - `Oscillator`: sine, PolyBLEP saw and square.
  - `Envelope`: linear attack/release gate, retriggers from the current level (no clicks).
  - `MonoNoteStack`: last-note priority, fixed capacity, no allocation.
  - `SynthCore::render()`: drains events, renders, reports for each note-on the stream frame where it starts.
- `engine/android`: Oboe stream (AAudio, exclusive, low-latency, float), JNI bridge. Reopens the stream on disconnect (headphones plugged in or out).
- `app`: one Compose screen with stats on top, waveform and buffer-size (1–4 bursts) toggles, and the keyboard across the bottom.

### Audio thread rules check

- The callback only calls `SynthCore::render()`, which touches fixed-size members and atomics.
- A host test replaces global `operator new` and fails if `render()` allocates.
- Every `getTimestamp`, `getXRunCount`, buffer-size and reopen call runs on the UI or Oboe error thread and is guarded by a mutex the callback never takes.
- All JNI calls come from the main thread, so each queue has exactly one producer and one consumer.

## Owner test script (S25+)

1. Install the APK from the latest GitHub Release (`build-N`).
2. Wired headphones: tap keys 20+ times; note the min/median tap-to-tone and the stream line (it should say AAudio, Exclusive, LowLatency).
3. Try 1, 2 and 3 bursts. Note the lowest setting with no crackle while tapping fast.
4. Repeat on the built-in speaker.
5. Install OboeTester (Play Store) and run Round Trip Latency on the same outputs.
6. Report the numbers and whether it *feels* instant.

## Done when

- Host tests green (including ASan/UBSan and TSan runs) and the APK builds in CI.
- The owner has reported numbers from the S25+, and the go/no-go is written up in `docs/adr/`.
