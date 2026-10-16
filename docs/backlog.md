# Backlog

Last updated 16 Oct 2026. Feature IDs refer to `docs/feature-spec.md`.

## Shipped

- [x] build-15: v1 close-out: bundled fonts; live Mixer level meters with clip lights; Synth/Drum/Mixer mockups as built; two-thumb review (swipe display to change slot, double-tap a tape lane to record).
- [x] build-14: CX two-thumb fixes (ADR 0003): strip values under the label; Mixer bars select. Owner: works well.
- [x] build-13: F8 key and scale.

## v1 close-out

- [ ] Owner 30-minute soak on the S25+: xruns 0, callback load ≤ 35% average and ≤ 70% peak (F0.3, F0.4).
- [ ] Changing voice mode cuts held notes.
- [ ] Tape tempo lock: the tape keeps the tempo of its first take.
- [ ] A commit of a pattern that isn't a whole number of bars leaves silence.
- [ ] Two-thumb proposals P1–P6 (ADR 0003): owner to pick.
- [ ] Mixer DLY/REV columns: the setting marker uses the 0–100% return scale, the meter dB.

## v1.1

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
