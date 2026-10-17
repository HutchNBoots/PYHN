# Backlog

Last updated 17 Oct 2026. Feature IDs refer to `docs/feature-spec.md`.

## Shipped

- [x] build-21: CX safe set: Note strip moves by an interval (bug fix), pitch roll + note names on Seq, one kit grid (Kick bottom-left) with readable names, Drum screen grid + two pad blocks, presets audition with keys, Undo long-press = Redo (Shift no longer flips it), tape lane clear asks first.
- [x] build-20: two-thumb sequencer (drum lanes, long-press step select, Velocity/Length/Chance/Nudge strips, undo, Shift+Clear lane); voice-mode fix; tape varispeed with tempo; whole-bar commits; mixer return markers; build number in debug panel.
- [x] build-19: P1, long-press the Synth display for presets.
- [x] build-18: v1 close-out: bundled fonts; live Mixer level meters with clip lights; Synth/Drum/Mixer mockups as built; two-thumb review (swipe display to change slot, double-tap a tape lane to record).
- [x] build-14: CX two-thumb fixes (ADR 0003): strip values under the label; Mixer bars select. Owner: works well.
- [x] build-13: F8 key and scale.

## Now: CX research

- [ ] Owner returns `docs/manual/cx-feedback.xlsx`; then `docs/manual/cx-findings.md` and the next build plan (see `docs/HANDOFF.md`).
- [ ] Manual sections still to write: 0 Getting started, 3.3 Patterns, 3.4 Parts together, 4 Tape (paused), 5 Mix, 6 Performing, 7 Session.

## v1 close-out

- [ ] Owner 30-minute soak on the S25+: xruns 0, callback load ≤ 35% average and ≤ 70% peak (F0.3, F0.4).
- [ ] Two-thumb proposals P2–P6 (ADR 0003): owner to pick.

## v1.1

- [ ] Tape: show varispeed in the subtitle; export at the session tempo.

- [ ] Tilt control (calibrate-and-hold, 1–2 parameters).
- [ ] USB-MIDI in/out and MIDI clock sync (still open: which MIDI gear to test with).
- [ ] Oscillator draw.
- [ ] Playful sequencers: endless, random, arp.
- [ ] Sketchbook of saved sessions (owner decision 2).

## Later

- [ ] Second synth engine (FM or wavetable).
- [ ] Sampling from the mic and USB audio.
- [ ] Project sharing and community loops.

## Before the Play Store

- [ ] Real release signing key (the committed dev key is public).
- [ ] Licence review, store listing, pricing decision.
