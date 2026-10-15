# ADR 0002: Screen layout and visual language

Date: 15 Oct 2026 · Status: accepted (owner approved the mockups)

Mockups: https://claude.ai/artifact/Kxv4uKUgtzHJt5Tn9x6tPP (private to the owner).
Sources are copied in `docs/mockups/` for reference. They are design files, not app code.

## Decisions

- **Frame:** landscape, designed at 1040×480 dp-equivalent for the S25+ (about 2.17:1).
  Touch targets are at least 44 dp.
- **Zones** follow the spec: top bar (screen tabs, slots, transport, Shift); display (left)
  and four colour strips (right) in the middle row; the bottom zone across the full width.
- **Bottom zone by screen:** keys on Synth, Drum and Tape; 16 step pads on Sequencer;
  channel pads on Mixer (see the feature spec, F6).
- **Strip colours** are fixed by position on every screen: 1 coral `#FF6B57`, 2 amber
  `#FFC23D`, 3 green `#3DD68C`, 4 blue `#4F8BFF`. They differ in lightness as well as hue.
  Ground `#121214`, panels `#1C1C20`, text `#ECE9E2`.
- **Type:** Space Grotesk for labels; IBM Plex Mono for values and readouts.
  Both are under the SIL Open Font License, so they are fine to bundle.
- **Strip visual:** a fill rises from the bottom to the value, with a solid edge in the
  strip colour. The label is at the top in the strip colour and the value at the bottom.
- **Shift** is shown amber while active, and the display's corner label names the active layer.
- **Tape top bar:** Undo and Export WAV replace the sound slots on the Tape screen,
  so export stays within 3 taps (spec loop 4). The owner accepted this exception to
  "slots always visible".

## Not decided by the mockups

- How the synth's sections (Osc, Filter, Env, Mod) are picked. The feature spec adds a
  section rail; the mockups will be updated before the UI is built.
- The Drum and Mixer screens, which aren't drawn yet.
