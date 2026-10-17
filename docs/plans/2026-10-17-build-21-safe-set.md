# Build-21: the safe set from the CX research

Owner (17 Oct 2026): start the build with only the items that are bugs or already agreed; the rest
waits for the manual comments and `docs/manual/cx-feedback.xlsx`. Decisions: Kick bottom-left in the
kit grid; Drum screen = grid + pads. Also standing (`docs/manual/owner-decisions.md`): keep the Synth
display as it is; keep the capsule (FM-1 style) keys; the pitch roll is approved.

UI only (Kotlin). No engine or audio-callback changes are planned; if one turns out to be needed it must
follow the audio thread rules and say so in its commit.

## 1. Note strip moves by an interval (bug fix)
Today the Shift-layer Note strip on Seq is absolute: with several steps selected every step's first note
is moved *to* the dragged pitch, flattening a bassline (`SeqController.kt:126-137`).
- With steps selected, the Note strip is **relative**: a drag moves every note of every selected step by
  the same number of semitones (or scale degrees when a scale is on, without dropping notes: a note that
  would collide keeps its own transposition). The strip reads `+2`, `−5`, `0` (relative to where the
  drag started); the footer shows the focused step's notes.
- Notes clamp to C1..C7 per note; a drag that would push any note past the range stops for all.
- Double-tap Note resets the drag to 0 (no change), not "everything to C4".
- Past the clamp the strip keeps showing the move actually applied; dragging back has a dead zone until the
  finger returns to that value (accepted). With a scale on the strip reads degrees (`+2°`).
- A drag belongs to the slot and pattern it started on, and ends (keeping what it wrote) when its steps change
  under it: keys or live recording into them, Undo / Redo, deselection, or another slot or pattern shown.
- One drag = one undo step. On an empty step the strip is dimmed and does nothing.
- Tests first (JUnit): multi-step relative move keeps the intervals; chords keep their shape; scale-on
  moves by degrees without dropping notes; range clamp; double-tap.

## 2. Pitch roll and note names on Seq (synth slots)
Mockup: `docs/mockups/SeqSynthProposed.dc.html` (keys stay capsules, not the mockup's boxes).
- **Display, synth slot:** the 4-row on/off overview is replaced by a **pitch roll of the current page**:
  x = 16 steps, y = pitch rows auto-fitted to the pattern's notes (at least an octave, padded by 2 semitones),
  row labels for C and the scale root in mono; out-of-key rows shaded when a scale is on. Each hit is a bar
  as long as its length (pattern gate or its own Length; tails run across later steps and wrap off the
  page edge), chords stack. Velocity shows as bar brightness. Playhead column green, selected steps' columns
  outlined cream. A thin 4-page mini-strip above it shows which pages have notes and where the playhead is.
- **Tap a note bar = select its step** (same as a long-press on its pad; tap the empty roll = deselect).
  This is the safe way to read and pick a step.
- **Step pads, synth slot:** a lit pad shows its note name (`A2`) or, for 2+ notes, the lowest note and a
  count (`C3·3`); simple triads can be named (`Am`, `C`) if it fits. 
- Drum slot unchanged except the grid (item 3).
- Tests (JUnit): roll layout model (row range fit, bar start/length incl. tails and wrap, chord stacking,
  hit → step lookup for taps); pad label formatting.

## 3. One kit grid and readable drum names
Mockups: `docs/mockups/SeqDrumProposed.dc.html`, `docs/mockups/DrumProposed.dc.html`.
- **Names:** a hand-written short-name table replaces `DrumLayout.shortName`'s cutting:
  Kick, Punch, Crack, Snare, Clap, Rim, CHat, Tick, OHat, Crash, Ride, LoTom, MdTom, HiTom, Block, Shakr
  (keys 1–16 in kit order). Used everywhere a short name shows (grid, pads, footer, "Clear Snare?").
- **One grid component** `ui/KitGrid.kt`: 4 × 4, **Kick bottom-left** — rows from the bottom:
  Kick Punch Crack Snare / Clap Rim CHat Tick / OHat Crash Ride LoTom / MdTom HiTom Block Shakr.
  Same look everywhere: selected = cream fill, used-in-pattern dot. Touch-down selects the sound and
  auditions it (never records). Used by the Seq drum slot (replacing `SoundGrid`) and the Drum screen.
- **Drum screen:** the display shows the kit grid on the left (replacing the untouchable map), the selected
  sound's picture on the right, and under it a one-row "this beat" strip of the sound's 16 steps on the
  current page. The **bottom zone is two 4 × 2 pad blocks** (left: Kick Punch Crack Snare / Clap Rim CHat
  Tick on the lower/upper rows; right: OHat Crash Ride LoTom / MdTom HiTom Block Shakr), same order as the
  grid, one block under each thumb. Pads play (and record when armed), set velocity by touch height over the
  full pad, select the sound, and light while held. The 8 dead keys are gone. Tape with slot 1 selected uses
  the same pad blocks.
- Tests (JUnit): name table; grid index ↔ key mapping (Kick bottom-left); pad block mapping; velocity by
  touch height.

## 4. Presets can be auditioned
- Touching a key no longer closes the preset strip. It closes on a tap outside the strip and keys (display,
  rail, strips, top bar) or on its own × at the right end.
- The current preset chip is cream (not coral); coral stays for the selected slot.

## 5. Undo never turns into Redo by accident
- Shift no longer changes the Undo chip. **Long-press Undo = Redo** (the chip briefly reads "Redo").
  Applies to the Seq Undo chip and the Tape Undo button.

## 6. Tape lane clear asks first
- Holding a lane no longer clears at once: it shows "Clear T3?" on that lane (coral); a tap on that lane
  within 3 s clears it; anything else cancels. Undo still restores.

## Split (one module per agent)
- **Agent A (Seq):** items 1, 2, 5 (Seq chip) — SeqScreen, SeqController, SeqStrips, new roll model, and
  the call site of `KitGrid` in SeqScreen (signature: `@Composable fun KitGrid(state: GrooveState,
  modifier: Modifier)`).
- **Agent B (Drum, Synth, Tape):** items 3 (KitGrid.kt, DrumLayout names, DrumScreen, pad blocks, Keyboard
  call sites), 4 (GrooveScreen/SynthScreen preset strip), 5 (Tape Undo) and 6 (TapeScreen).
- Shared files (GrooveState.kt): keep edits to separate functions.

## Acceptance (owner, on the S25+)
1. Select four bass steps, Shift, drag Note up: the line moves up together.
2. On Seq with a synth slot, the display shows the notes as a roll; tap a note to select its step.
3. Drum and Seq show the same grid, Kick bottom-left, readable names; Drum has two pad blocks.
4. Open presets, play keys while tapping through them.
5. Undo on Seq and Tape never acts as Redo unless long-pressed.
6. Holding a tape lane asks before clearing.
