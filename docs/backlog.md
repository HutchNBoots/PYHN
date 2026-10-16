# Backlog

Last updated 16 Oct 2026. Feature IDs refer to `docs/feature-spec.md`.

## Next build

- [x] CX: strip values move to the top of the strip, under the label (ADR 0003).
- [x] CX: Mixer level bars in the centre display select a channel (Shift + tap mutes) (ADR 0003).

## v1 close-out

- [ ] Owner 30-minute soak on the S25+: xruns 0, callback load ≤ 35% average and ≤ 70% peak (F0.3, F0.4).
- [ ] Bundle the Space Grotesk and IBM Plex Mono fonts (ADR 0002); the system fonts are used now.
- [ ] Changing voice mode cuts held notes.
- [ ] Mixer level bars show fader settings, not audio levels (needs a metering API).
- [ ] Mockups for the Drum and Mixer screens, and the Synth section rail.
- [ ] Two-thumb review (ADR 0003) of the Seq, Tape and Synth screens.
- [ ] Tape tempo lock: the tape keeps the tempo of its first take.
- [ ] A commit of a pattern that isn't a whole number of bars leaves silence.

## v1.1

- [x] F8 Key and scale (build-13).
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
