# M2: Sequencer, drums and slots

Spec: `docs/feature-spec.md` F2, F3 (and F0, F4 still apply). Layout:
`docs/adr/0002-screen-layout.md`, `docs/mockups/Sequencer.dc.html`.

Milestone build: 8 working slots (slot 1 drums, slots 2–8 synths on different presets),
a 64-step sequencer per slot with 16 patterns each, all slots playing in sync, live and
step recording, motion recording, probability, shuffle, step loop and queued pattern
switching. It starts with a short demo beat and bassline, so pressing Play makes music at once.

Not in M2: tape, mixer screen and FX (M3); autosave (M4). Per-slot level is fixed (equal).

## Shared contracts (written by the lead first)

- `engine/core/include/groove/Instrument.h`: the audio-thread interface every slot
  implements (sequencer notes, motion overrides, tempo, render). SynthEngine and
  DrumMachine both implement it.
- `engine/core/include/groove/DrumParams.def` / `DrumParams.h`: the per-key drum
  parameter table (F2.2).
- `engine/core/include/groove/DrumMachine.h`: the drum machine's public API (pimpl; a
  silent stub `.cpp` exists until the drums module replaces it).
- `app/.../engine/NativeEngine.kt`: the full JNI API: slot-indexed notes and params, drum
  params, transport, pattern editing, step loop, recording readback.
- Both CMake files glob their sources, so no module edits them.

## Data model (sequencer module owns the C++ form; the UI mirrors it)

- Per slot: 16 patterns. Per pattern: length 1–64, rate (1/8, 1/16, 1/32, 1/16T),
  shuffle 0–1, default gate 0.05–1, 64 steps, up to 8 motion lanes.
- Step: on, 0–4 notes, velocity, gate (negative = the pattern's gate), probability.
- Motion lane: a target (synth ParamId, or drum key × param count + id) plus one optional
  value per step.
- The audio thread owns the engine's copy. The UI changes it only through queued
  commands, and recording reports its writes back. The UI's mirror is the authority for
  display (and for saving, in M4).

## Engine timing rules (sequencer module, all host-tested)

- One global clock and tempo (40–240 BPM). Each slot steps through its own current pattern
  at that pattern's rate, from a shared musical position. All slots start together on Play.
- Every event is placed on an exact sample frame computed from the tempo and position, with
  no accumulated drift (F3.1). The graph splits its render at event frames.
- Shuffle delays every odd-numbered step (0-based) by shuffle × 2/3 of a step (F3.3).
- Probability uses a per-slot xorshift generator, seeded on Play, so offline renders
  repeat exactly (F3.4).
- A note-off comes at gate × step length (at least 1 ms), or at Stop.
- Live record (armed and playing): a UI note-on is quantised to the nearest step of the
  slot's current pattern by its exact frame (F3.6). It still plays immediately.
- Motion record: while active and playing, parameter changes write the current step of
  the lane (F3.7). Playback applies the lane value at each step start through
  `Instrument::setMotion`. A pattern with no lane for a target clears that override.
- Step loop: while set, the slot plays start..end repeatedly. Clearing it jumps to where
  the pattern would have been had it never looped (F3.8).
- A pattern switch queued while playing starts on the slot's next pattern boundary (F3.9).

## Modules and owners

1. **Sequencer + integration (engine)**: `Sequencer`, the pattern store and command
   queue, an 8-slot `AudioGraph` (one DrumMachine + 7 SynthEngines, summed at equal gain
   into the MasterBus), SynthEngine's `Instrument` implementation (sequencer notes,
   motion overrides, tempo-synced LFO from the clock), `AudioEngine` and `JniBridge` for
   the whole contract, and latency records from every slot.
2. **Drums (engine)**: replaces `DrumMachine.cpp`. Synthesises 16 original sounds at
   `prepare()` and implements an 8-voice sampler with the per-key parameters, choke groups,
   reverse, start offset and pitch (F2.1–F2.4).
3. **UI**: slot selection (top bar), Drum screen, Sequencer screen per the mockup,
   transport (play/stop, record arm, hold-record motion), a tempo control, per-slot synth
   screens, the pattern mirror applying `pollRecorded`, and the demo content.

## Review gate

Same as M1: a strict review against the spec and the audio thread rules; the commit says
how the rules were checked; CI builds the APK.
