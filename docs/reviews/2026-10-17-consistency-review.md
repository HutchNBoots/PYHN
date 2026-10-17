# Consistency review after build-20 (17 Oct 2026)

Two review-only passes (visual; interaction and naming) over build-20, at the owner's request.
No app changes were made. Evidence renders were made with the offscreen harness.

## Owner feedback that prompted it
- Synth is close to a beta target; the keys' rounding could be reduced to match the rest.
- On drums, the 4 × 4 grid may be obsolete and doesn't line up with the Seq view.

## Findings, ranked

| # | Finding | Recommendation | Effort |
|---|---|---|---|
| 1 | Drum screen's 4 × 4 map can't be touched, has no labels, sits out of thumb reach, and is upside down relative to the Seq sound grid (`DrumScreen.kt:131` `row = 3 - k/4` vs `SeqScreen.kt` `row*4+col`). Neither grid matches the keyboard, whose kit zig-zags across two rows. | Replace it with the Seq `SoundGrid` (same place, order, labels, dots, selection; selects and auditions, never records), or remove it. Keep a lasting mark on the selected key. **Owner decision:** Kick 1 at bottom-left (drum-rack convention, near the thumb) or top-left (reading order, like steps 1–16). | S–M |
| 2 | On the phone the kit shows as DK, PK, CS, TS, CH, TH, LT, Bloc, Shak (`DrumLayout.shortName` on the real names); renders used test names, so this was missed. "Crash" is cut to "Cras". | A hand-written short-name table (e.g. Kick, Punch, Crack, Snare, Clap, Rim, CHat, Tick, OHat, Crash, Ride, LoTom, MdTom, HiTom, Block, Shakr); render harness uses the real names. | S |
| 3 | Keys are full capsules (`Keyboard.kt:139` radius = half the width, ~18.6 u); everything else uses fixed radii 8–16. Short Seq keys become sideways pills. | Fixed radius 10 u (as the slot buttons, which are almost the key size); tray 18 → 16; stripe inset ~10 u; optionally pad width 0.56 → 0.64 (judge by feel). | S |
| 4 | Tape: holding a lane clears the track at once; long-press means select/lock/presets elsewhere, Seq Clear needs two taps, and Tape Undo is out of reach. | Hold arms "Clear T2?", tap confirms; Undo chip in the Tape footer (P5). | S |
| 5 | A strip's 3 u edge line crosses its label/value when the value is high (Volume at 0 dB, Velocity 90%, Sustain). | Keep the edge below a ~52 u header band. | S/M |
| 6 | "Selected" is shown six ways; amber means Shift, held key, root, loop, queued pattern, selected step and the Drum map's key. | Cream fill = current choice; cream outline = selected item (step, tape lane); coral = slot and record; amber = Shift and held key only. | M |
| 7 | 11 radii for 5 roles; 13 type sizes; 5 dimming alphas; display padding 12 vs 14 and row gaps 8 vs 10 make titles shift between screens. | Tokens below. | M |
| 8 | Layer labels are worded five ways (Mixer's is an instruction). | One form, `MAIN · osc` / `STEP · step 5`; hints go to footers. | S |
| 9 | Mixer pad tap doesn't move the top-bar slot; tape track and mixer T1–T4 aren't synced. | Mixer pads select the slot / tape track. | S |
| 10 | Words: "lane" means drum sound, motion lane and tape lane; "Length" twice on Seq; "pad" for keys too. | Sound, motion, track, keys, pads; rename the step Length strip (e.g. "Hold") and the pattern one "Steps". | S |
| 11 | Drum display has no swipe or long-press (dead end for the left thumb); choosing a sound on Drum means playing it (records when armed). | Fixed by 1; optionally swipe Drum ↔ Synth slots (owner call). | S |
| 12 | Preset strip covers only part of the keys' top row (half-capsules show). Key labels 9 u (smallest text). Mixer and step pad labels differ. | Full-row backdrop; labels 10 u; one pad label style. | S |
| 13 | Docs out of date: vision spec ("Shift + key selects a step", "display is not a control"), F3 footer line, F4.5 wording, F6.5/ADR 0002 channel count, ADR 0003 Seq table, Drum mockup order. | Doc pass. | S |

## Token proposal
- **Radius (fixed):** panel 16 (display, keyboard tray) · strip 14 · pad 12 (step, mixer) · control 10 (buttons, rails, preset strip, **keys**) · chip 8 (sound cells, tape lane, debug) · xs 4 (cells, badges; 2 for hairline bars) · full for dots and stripes only.
- **Spacing:** 4 in a group · 6 between pads · 8 between strips and display rows · 10 between zones · display padding 14 / 18 everywhere.
- **Type:** title L20 Bold · button/strip label L14 · body L13 · BPM V22 · value V13 · meta V11 · caption 10 (L = Space Grotesk, V = IBM Plex Mono).
- **Colour roles:** strip colours only for strip-bound things · amber = Shift, held key · coral = selected slot, record · cream = selection · green = playhead/transport · alpha 0.4 inert, 0.25 blink.

## Verdict per screen
Synth: close to beta (keys, strip edge, label wording). Drum: weakest (map, names, small labels).
Seq: consistent family; selected-step amber. Tape: consistent; destructive hold. Mixer:
consistent; label style and slot sync. Top bar: solid. Preset strip: backdrop. Debug: fine.
