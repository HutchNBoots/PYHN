# CX programme: builds 22–24 (all suggested changes incl. v1.1 ideas)

Owner (17 Oct 2026): take all the remaining default (suggested) changes from the manual, the
consistency review and the ideas list, including the v1.1 ideas, and build. Standing decisions
(`docs/manual/owner-decisions.md`) win over any suggestion: the Synth display stays as it is; the
capsule (FM-1 style) keys stay; strip lock is a 1 s still hold with no Shift; Kick bottom-left; Drum =
grid + pads. Ideas tagged "later" stay out: sampling (mic, resample), tape lift/drop/splice, tape scrub
(needs an owner call), MIDI and clock (needs gear).

Sources: `docs/manual/manual.html` (tasks' ideal flows), `docs/manual/manual_more.py` (ideas table and
worked proposals), `docs/reviews/2026-10-17-consistency-review.md`, mockups in `docs/mockups/*Proposed*`.

## Build-22: interface (UI only, no audio-callback change)

### Look and grammar (every screen)
- Tokens: radius panel 16 / strip 14 / pad 12 / control 10 / chip 8 / cell 4 (keys stay capsules);
  spacing 4 / 6 / 8 / 10, display padding 14 × 18 everywhere; seven text sizes; alpha 0.4 inert / 0.25 blink.
- Selection: cream fill = current choice, cream 3 u outline = selected item (steps, tape lane);
  coral = selected slot and record; amber = Shift and held key only.
- Strip edge line kept below the label band. One layer-label form (`MAIN · osc`, `STEP · step 5`);
  hints move to footers. Words: sound, motion, track, keys, pads; step "Length" → **Hold**,
  pattern "Length" → **Steps**. Key labels 10 u; one pad label style (mono 13 medium).
- Preset strip backdrop covers the whole top key row.

### Synth
- Swipe on the display flashes the slot number and preset name large (1-1a, approved).
- Preset strip: group labels Start · Bass · Lead · Pad · Pluck · FX (1-1b, approved; keys-stay-open is in build-21).
- "Edited" mark on the preset name (`Glass Wire*`), cleared by reloading the preset.
- Sound undo: one strip drag = one undo step; Undo chip bottom-left of the Synth display (tap undo, hold redo).
  Double-tap a strip returns to the **preset's** value.
- Strip lock: 1 s still hold, filling ring + haptic; movement before then cancels (1-2c, decided).
  Preset loads respect locks.
- LFO: Rate snaps to beat divisions below a detent (sync without Shift); a small LFO trace when Mod is
  the section, without removing the existing three pictures (1-2b, approved).
- Play rail item (5th): Key, Scale, Octave (keyboard octave, labels follow, pattern untouched), Mode (1-3a, approved).
- Glide dimmed in Poly; mode label "Unison (mono)" (1-3c, approved). Renames: Filter Env → EnvAmt,
  Key → KeyTrk, Mod Delay → Fade.
- User presets: Shift + long-press the display saves the slot as a user preset (auto-named, no dialog);
  hold one slot button and tap another copies a sound.

### Drum
- Decay / Start / Rev drags replay the sound on release. Locks per sound. Rev and Choke as tap cells on the
  Shift layer. Drum edits undoable (shares the Synth sound-undo history).
- Swipe on the Drum display goes to slot 2; swipe right on Synth slot 2 goes to Drum (review R11).

### Seq (both slot types)
- Footer chips **Undo · ×2 · Dup · Q** (Q arrives in build-23; until then hidden).
  ×2 doubles Steps with every hit and motion value copied (dimmed above 32); Shift + ×2 halves.
  Dup copies into the next empty pattern and queues it; Shift + Dup copies without switching.
- Shift + tap a page number copies the viewed page there. With steps selected, Shift + tap a pad pastes
  them there keeping spacing. Hold a lit pad and drag onto another = move with all values; Shift + drag = copy.
- Fill: Shift + tap pad A then pad B fills the lane / pen note every (B − A) steps.
- Rotate: Shift + swipe across the pads (no selection) rotates one step per pad width.
  With a step selected, Shift + swipe inserts / deletes a step from there on.
- Relative multi-select edits: Velocity, Hold, Chance, Nudge move every selected step by the drag
  (not one absolute value); Shift + Nudge on a selection = random spread ("Spread"), seeded and undoable.
- A strip moved on a selected step with no hit no longer writes the last chord there (it stays dimmed;
  first key or pad tap creates the hit).
- Clear removes notes only (keeps Steps, Rate, Gate, Shuffle, motion); Shift + Clear on a synth slot resets settings.
- Page follow: Shift + tap the current page toggles follow-the-playhead.
- Beat edges follow the rate (every 6 at 1/16T, every 2 at 1/8; the engine plays 1/16T at 6 steps a beat,
  24 a bar); choosing 1/16T offers Steps 24 (16 → 24, 32 → 48, keeping the bars).
- Pattern row on the display: 16 cells with content dots; tap = switch (queued), Shift + tap = copy the
  current pattern there; long-press = switch all slots at the next bar; hold A + tap B chains A → B.
- Editing a non-playing pattern: selecting a pattern cell while playing edits it without switching (the
  queued/edited pattern is outlined).
- Session swing: Shift + double-tap Shuffle copies it to all slots; new patterns inherit the slot's last settings.

### Seq, synth slot
- **Pen keys**: a strip of capsule keys always visible under the pads (pads ~45%), with ‹ › octave and a
  "pen: A2" readout. A key sets the pen; a pad tap writes the pen note/chord; a lit pad tap removes.
- Chords: while steps are selected, the first key replaces and each further key toggles a note; the keys
  light the selected step's notes. Footer sorts low → high and names simple chords (`C maj · C4 E4 G4`).
- Read without risk: a long-press that drifts past the slop cancels (never toggles); tapping a roll note
  selects (build-21).
- Accent toggle: Shift + tap a lit pad toggles a fixed velocity / filter boost; velocity as fill height.
- Slide: Shift-layer strip 2 = Slide (per step, glide only into that step, legato) — **engine part in build-23**.
- Recording armed on Seq shows the pen keys; selecting a step while armed stops note recording.
- Mute / solo chips in the Seq and Synth footers (slot mute; solo = Shift + Mute).

### Seq, drum slot
- Pads: velocity as fill height, other-sound ticks, nudge tick (mockup `SeqDrumProposed`).
- Sound mute / solo: Shift + tap a grid cell mutes the sound, Shift + double-tap solos (hollow coral cell)
  — **engine part in build-23**.
- When Record is armed, the bottom zone shows the drum pad blocks (record drums from Seq).

### Tape
- Footer Undo (tap undo, hold redo) and a slot chip in the top bar where the slot buttons were (P5, P6);
  subtitle shows varispeed (`0.92× · tape 130.0`).

### Mixer and top bar
- Mixer pads select the slot / tape track (synced with the top bar). Mute and Solo chips in the footer.
- Tap tempo: tap the BPM readout 3+ times (×2 / ½ snap); long-press = 120.

## Build-23: recording, timing and engine
Each item follows the audio thread rules, test-first on the host, and says how in its commit.
- Live record keeps the feel: offset → nudge, held time → Hold; **Q chip** (100 %, Shift + Q = 50 %);
  Shift + long-press Record toggles Snap / Feel; default Feel.
- Replace record (Shift + Record) and erase-while-recording (hold a grid cell + Clear).
- Capture: the engine keeps the last 8 bars of played notes per slot in a fixed ring; long-press Record keeps them.
- Click and count-in: Shift + tap Record toggles the click; 1-bar count-in when armed while stopped; click level on Master.
- Per-sound mute / solo in the drum engine; slot solo.
- Per-step Slide (synth).
- Live transpose: Shift + tap a key transposes every synth slot relative to C (display "+2").
- Export the current loop × N bars offline (Shift + Export); export stems (Shift + long-press Export).

## Build-24: v1.1 ideas
- Arpeggiator (Play rail · Shift: mode, rate, octaves, latch; records steps when armed).
- Chord mode (Play rail: Chord 1 / 3 / 4 notes in key, Spread).
- Ratchet (step Shift strip 2 alongside Slide → step Shift layer becomes Note · Slide · Ratchet · Motion).
- Conditions + fill (step Shift strip 3: Always, 1:2…4:4, First, Fill; hold the overview for Fill).
- Dice chip: re-roll the lane / notes in key; undoable.
- Master performance channel on the Mixer: DJ filter, Repeat, Drive, Click level.
- Note repeat: Shift + hold a kit pad repeats at the Seq rate (strip 1 = rate while held).
- Per-step parameter locks from any screen (selected steps stay selected; a Synth/Drum strip drag writes motion).
- Sketchbook of sessions: long-press the Tape tab (New, Duplicate, load).

## Two-thumb proposals
P1 (built), P5 and P6 in build-22. **P2 / P3 (Play and Record bottom-left) and P4 (Shift by the strips)
change the approved layout and narrow the keyboard: confirm with the owner before building.**

## Process
Plan per build → agents per module (theme+mixer+tape+top bar · synth+drum · seq) → strict review →
fix → CI build → owner tests on the S25+. The manual is updated after each build.
