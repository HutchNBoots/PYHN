# Manual-led CX research: scope

Owner request (17 Oct 2026): before more large code changes, write the user manual for
the key activities, list the exact steps each one takes today, and optimise those steps.
The manual is the research tool: if a task is awkward to write down, it is awkward to do.

## Method (per task)
Each task in the manual is written as it works **today** (build-20), then scored, then
given an ideal flow:

1. **Goal:** what the player wants, in their words ("make the bass darker").
2. **Start:** where they are (screen, slot, playing or stopped).
3. **Steps:** one line per touch: action, which thumb, screen zone, Shift or not.
4. **Score:** touches, screen switches, reaches outside the thumb zones (ADR 0003), Shift
   uses, hidden gestures (long-press, double-tap, swipe), moments the sound stops or
   something can be lost.
5. **Friction:** what is slow, unclear, inconsistent or risky.
6. **Ideal flow:** the fewest-step version that keeps one grammar, and what it would change.

Findings from all tasks are collected into one ranked CX list before any build is planned.

## Manual structure

**0. Getting started**
- 0.1 The screen: top bar, display, four strips, keys/pads; slots 1–8.
- 0.2 The grip: left thumb selects, right thumb edits; Shift's second layer.
- 0.3 Play, Stop, Record; tempo.
- 0.4 Gestures at a glance (tap, hold, double-tap, swipe, lock).

**1. Synth sounds (patches)**
- 1.1 Pick a sound: choose a slot, browse presets, audition.
- 1.2 Shape it: Osc, Filter, Env, Mod; the Shift layer; lock and reset a strip.
- 1.3 Play it: key and scale, voice mode (poly, mono, unison), octave.
- 1.4 Keep it: what is saved, how to get back to a preset.

**2. Drum kit**
- 2.1 Play the kit; which key is which sound.
- 2.2 Edit one sound: pick it, pitch, decay, tone, level; Shift (pan, start, reverse, choke).

**3. Sequencing**
- 3.1 A drum beat: kick, snare, hats by lane; live record; velocity, chance, nudge;
  shuffle; fix a mistake (undo, clear a sound).
- 3.2 A synth part: notes and chords by step; long notes; live record into a scale;
  motion recording.
- 3.3 Patterns: longer patterns (pages, length, rate), 16 patterns per slot, switching,
  copying (gap today).
- 3.4 Putting parts together: several slots playing, mutes.

**4. Tape**
- 4.1 Record the first take; how length is set.
- 4.2 Overdub and replace; the loop region.
- 4.3 Commit a pattern (one slot, all slots).
- 4.4 Speed, reverse, tempo changes.
- 4.5 Undo, clear a track, export a WAV.

**5. Mix and effects**
- 5.1 Levels, pan, mute; reading the meters.
- 5.2 Delay and reverb sends and returns; master.

**6. Performing live**
- Switching patterns in time, step loop, mutes, motion, tape speed, tempo moves.

**7. Session**
- Autosave, starting fresh (gap today), backing up and exporting.

**Appendix:** gesture reference, glossary (slot, sound, track, pattern, step, motion).

## Focus order
The Noodler's core loop first, as one connected story:
1. **3.1 Drum beat** (most changed in build-20; owner feedback on the grid).
2. **1.1–1.2 Synth sound** (near beta; confirm and polish).
3. **3.2 Synth part** (bass or chords over the beat).
4. **4.1–4.3 Tape** (layering the loop; least tested by the owner).
5. **2 Drum kit** and **5 Mix**.
6. 0, 6, 7 and the appendix last; they fall out of the others.

## Output
- One markdown file per section in `docs/manual/` (`01-synth.md`, `03-1-drum-beat.md`, …),
  written from the code and checked against renders.
- `docs/manual/cx-findings.md`: the ranked list across all sections, merged with
  `docs/reviews/2026-10-17-consistency-review.md`.
- No app changes until the owner has read the findings and chosen what to build.
