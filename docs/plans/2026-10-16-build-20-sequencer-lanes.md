# Build-20: two-thumb sequencer (drum lanes, step edit) + v1 fixes

Owner asks (16 Oct 2026): "how do I put a kick and snare on the same beat", "review the
Ableton Move sequencer and see if we could be closer", then "select a step [with the left
thumb], the right thumb edits, including length", then "do it". Workflow ideas only: no
names, layouts or assets copied (CLAUDE.md design constraints).

The arpeggiator and other playful sequencers stay in v1.1. This plan only keeps room for
them: the step model below is per-hit, so an arp can later write or generate hits the same
way.

## Behaviour (what the owner sees)

### Drum slot on the Seq screen: one sound at a time (lanes)
- The pattern display shows the kit's 16 sounds as a 4 × 4 grid on its **left** side
  (left-thumb reach, ADR 0003). Tapping a sound selects it as the **lane** and auditions it.
  The lane starts as the drum key last played or selected (Drum screen), else Kick 1.
- Step pads show the selected lane: **bright** where that sound plays, a **small dot** where
  only other sounds play, dark where the step is empty.
- **Tap** a pad: adds or removes the selected sound on that step. Other sounds on the step
  stay. Up to 8 sounds per step.
- The drum keys (Drum screen) still choose the lane too: playing a key selects that sound.

### Every slot: select a step with the left thumb, edit with the right
- **Long-press** a pad (the platform long-press time, finger still): selects that step. It
  stays selected after letting go (latched), so the right thumb is free. Holding more pads
  while one is held adds them to the selection (edit several at once, Move-style).
- Long-press a selected step again to deselect it; tapping the display (outside the sound
  grid) deselects all. Changing slot or pattern deselects.
- While steps are selected, **without Shift**, the strips are the step's:
  | Strip | Range | Shown as |
  | --- | --- | --- |
  | Velocity | 1–100% | `80%` |
  | Length | 1/16 step … 16 steps; leftmost = "pattern gate" | `gate`, `1/2`, `1`, `2½`, `16` (steps) |
  | Chance | 0–100% | `100%` |
  | Nudge | −50% … +50% of a step | `−12%`, `0`, `+25%` |
  On the drum slot they edit the selected lane's hit on each selected step; on a synth slot
  every note of the step (the chord shares them). A selected step where the lane has no hit
  shows dimmed values; moving a strip there creates the hit (defaults, then the new value).
- Synth slot: while a step is selected the keys sit under the pads (as now); held keys set
  the step's notes (a chord, up to 4).
- **Shift** with steps selected: strips = Note (synth) / —, —, —, Motion (as the old step
  layer). Shift without a selection: Tempo, Pattern, Key, Scale (unchanged).
- **Step loop** (F3.8) moves to **Shift + hold two pads** (plain holding now selects).

### Undo, clear
- An **Undo** chip at the left end of the Seq footer (left thumb). Shift + Undo = redo.
  Covers the sequencer edits of the current session (32 steps deep, per app run): toggles,
  strip edits (one drag = one undo step), clears, and a live-record take (from Record on to
  Record off = one undo step). Undo re-sends the restored pattern to the engine.
- Clear (two taps) clears the pattern, as now. **Shift + Clear** on the drum slot clears only
  the selected sound's lane (two taps; the button reads "Clear Snar1?").

### v1 fixes riding along
1. **Voice mode** (backlog): changing poly / mono / unison while keys are held must not cut
   the held notes; they keep sounding under the new mode (mono keeps the last-held note).
2. **Tape follows tempo** (backlog "tape tempo lock"): the tape remembers the tempo of its
   first take. Changing the tempo plays it varispeed (rate = tempo / tape tempo, pitch moves,
   tape-style, multiplied with Speed). Recording needs the tempo back at the tape's tempo
   (footer: "record needs 120.0 BPM, the tape's tempo"), like the existing Speed 1× rule.
3. **Commit fills whole bars** (backlog): a commit of a pattern whose length isn't a whole
   number of bars records ceil(length / bar) whole bars while the pattern keeps looping, so
   there is no silent tail. "All slots" uses the longest pattern.
4. **Mixer DLY/REV columns**: the setting marker uses the meter's dB scale.
5. **Build number** in the debug panel (top line, `build 20`), from versionCode.

## Data model (engine and the UI mirror agree exactly)

```
Hit  { note int; velocity 0.01..1; length; probability 0..1; nudge -0.5..0.5 }
     length: kPatternGate (-1) = use the pattern's gate, else 1/16 .. 16 (steps)
Step { hitCount 0..8; hits[8] }      // a step is on when it has a hit
```
- `kMaxHitsPerStep = 8`. The UI caps a synth chord at 4 (`SeqLimits.CHORD`).
- Live record adds a hit (note, played velocity, pattern gate, 1, 0) unless that note is
  there; when 8 hits are there, the last is replaced (same rule as now, at 8).
- Session format **v3**: v2 steps migrate as one hit per note with the step's velocity,
  gate (gate 0.05..1 kept as length; −1 stays pattern gate), probability, nudge 0. Steps that
  were off are dropped.

### JNI contract (fixed now so engine and UI can work in parallel)
```kotlin
// NativeEngine.kt
const val MAX_HITS_PER_STEP = 8
const val HIT_FIELDS = 5          // note, velocity, length, probability, nudge
fun setStep(slot: Int, pattern: Int, step: Int, hits: FloatArray, hitCount: Int): Boolean
```
`hits` holds `hitCount * HIT_FIELDS` floats in that order (note as a float, exact for MIDI
numbers). hitCount 0 clears the step. The engine clamps every field. `RecordedEvent` keeps
its shape (slot, pattern, step, note, velocity); the UI applies the live-record rule above.

## Engine timing rules (Sequencer, audio thread)
- A hit fires at its step's frame (shuffle applied first) + nudge × step length in frames,
  exact to the sample. Negative nudge fires before the step: when step k fires, the
  sequencer also schedules step k+1's negative-nudge hits (k+1 = the step that will really
  come next: loop, length wrap, a pattern switch due at the end). Stop, a loop change or a
  pattern switch made after that look-ahead doesn't recall them (documented).
- Length L steps: note-off at fire + L × step duration (at the tempo when it fired), at
  least kMinNoteSeconds. A hit retriggering a note that is still held sends its note-off
  first. Pending offs per slot: 128; when full, the oldest is released early.
- Chance per hit, from the seeded generator (renders repeat exactly, F3.4).
- Every timing rule gets a failing host test first (TDD), including nudge across shuffle,
  loops and pattern switches, long notes across the pattern end, and zero drift (F3.1).
- Audio thread rules unchanged: no allocation, locks, logging, I/O or JNI; pending events
  are fixed arrays.

## Work split (one module per agent)
- **Engine sequencer:** Pattern.h, Sequencer.*, its tests, JniBridge `nativeSetStep`,
  NativeEngine.kt `setStep` + constants only.
- **UI + storage:** ui/model (PatternStore, StepPads, SeqStrips, NoteMemory, undo),
  SeqController, SeqScreen, GrooveState, storage (SessionCodec v3), debug panel build
  number, Mixer marker scale. Uses the contract above.
- **v1 engine fixes:** voice mode (Synth), tape varispeed + record rule (Tape, TapeStore,
  AudioGraph commit), plus the Tape footer text and NativeEngine readouts it needs.

## Acceptance (owner, on the S25+)
1. Drums: pick Kick, tap 1 5 9 13; pick Snare, tap 5 13; both play on 5 and 13.
2. Long-press step 3 with the left thumb, let go, drag Length / Nudge / Chance with the
   right; hear it.
3. Undo brings back a step you removed.
4. Changing voice mode with a key held keeps it sounding; tape at a new tempo plays faster
   or slower; a 12-step commit leaves no gap.
