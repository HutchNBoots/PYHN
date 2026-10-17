# Build-23 plan: recording, timing and engine

Owner said go (17 Oct 2026). All open decisions in this plan are taken as recommended. The splash screen shipped as release build-23, so this work ships as release **build-24** (the programme still calls it "build-23").

Sources: `docs/plans/2026-10-17-cx-programme.md` (Build-23 and the build-22 notes), `docs/manual/manual_more.py` (the worked quantise proposal and ideas #4, 5, 7, 8, 19, 20, 25, 30), `docs/manual/owner-decisions.md`, CLAUDE.md audio thread rules. Everything is test-first on the host. Each commit that touches the callback says how the rules were checked (AllocationCounter plus a RealtimeSafetyTest case).

## 0. Gesture conflicts (resolved)
| Gesture | Today (build-22) | Build-23 asks | Resolution |
|---|---|---|---|
| Hold Record | Motion recording while held; a tap under 300 ms toggles arm (`RecordButton`) | Long-press = Capture | Keep motion. **Capture = release after ≥600 ms with no strip moved and notes in the ring**, with a haptic tick at 600 ms. If a strip moved it is a motion take, as now. From 300 to 600 ms nothing happens. |
| Shift + tap Record | Same as a tap | Replace **and** toggle the click (clash) | **Shift + tap Record = arm Replace** (or switch overdub/replace while armed). **The click toggle moves to Shift + tap the BPM readout** (a single tap does nothing to tap tempo). The count-in always clicks. |
| Shift + long-press Record | Same as hold | Snap/Feel toggle | The same ≥600 ms no-strip release, with Shift = toggle Snap/Feel (flash "record: feel"). |
| Record on the Tape screen | Tape record | n/a | Unchanged: the gestures above apply on Synth, Drum, Seq and Mixer only. |
| Export (Tape screen only) | Tap = WAV; dimmed when the tape is empty | Shift + tap = loop; Shift + long-press = stems | With Shift held the button is live when any current pattern has notes (loop). Stems need tape audio. A plain tap is unchanged. |
| Seq StepShift strips | Note · **—** · Spread · Motion (`SeqController` line 51) | Slide on strip 2 | Strip 2 = **Slide** (an Enum strip off/on) on synth slots; on drum slots it stays "—". Build-24 Ratchet will share strip 2. |
| Shift + tap a key | Unused (no Shift branch in `keyDown`) | Transpose | Synth slot, no steps selected → transpose, no note sounds. With steps selected the keys still edit chords. Drum slot: plays normally. While Shift is latched, the layer label reads `SHIFT · transpose`. |
| Shift + tap a kit-grid cell | Unused (`KitGrid`) | Mute; Shift + double-tap = solo | A tap toggles mute at once. A second Shift + tap on the same cell within 300 ms undoes that mute and toggles solo. Step pads keep their own Shift + tap (paste/fill): a different element. |
| Clear | Two taps (arm, then confirm within 2 s) | Hold a cell + Clear erases | While armed and playing, Clear touch-down plus a held kit cell (drum) or held key (synth) starts erasing that sound. It stops when either is released, with no confirm (the take is undoable). Clear first, then the cell, is the clean order (the cell then neither sounds nor records). Otherwise Clear is unchanged. |

## 1. Live record keeps the feel, Q chip, Snap/Feel
**Engine (A), `Sequencer`:**
- New command `setRecordOptions(feel, replace, replaceSlot)`.
- `recordLiveNote` in Feel mode writes `nudge = (position − chosenStepEventPosition) / stepUnits`, clamped to ±0.5. The chosen step's event position is the shuffled `lastEventPosition` or `nextEventPosition`.
- Each slot gets `OpenNote open[128] {pattern, step, startPos, active}` in `SlotState`.
- New `recordLiveNoteOff(slot, note, frame)` and `recordLiveAllOff(frame)` (audio thread). Hold = `(pos − startPos) / stepUnits`, clamped 1/16..16 steps. Synth slots only; drums keep the pattern gate.
- Disarm and Stop close open notes at that frame before `armed_` is published (so `RecordTake` sees every Length first).
- Snap mode keeps today's behaviour exactly (existing tests stay green).
- Report first, then write (as today): a full `recorded_` queue means no write.

**RecordedEvent:**
- Kinds `{Note=0, Motion=1, Length=2, Erase=3}`.
- New `float nudge` field.
- Length: `value` = steps.
- Erase: `noteOrTarget` = note, or −1 for the whole step.

**Wiring (B), `AudioGraph::forwardNotes`:** a NoteOff calls `recordLiveNoteOff`; AllNotesOff calls `recordLiveAllOff`.

**Q chip (C):** pure Kotlin `PatternEdits.quantised(pattern, strength, scope)`.
- Nudge × (1 − s).
- Lengths move by s toward their target: under 0.75 step → pattern gate, otherwise the nearest whole step.
- Pattern-gate hits and swing are untouched.
- Scope: a tap covers the selected steps, else the current drum sound, else the whole synth pattern. Shift + Q = the whole pattern at 50%.
- One undo step. The chip glows while anything in scope is loose.
- Snap/Feel is saved (v5); the default is Feel.

**Tests first:**
- GT `SequencerFeelTest`:
  - a note N frames after a step gives nudge = N·unitsPerFrame/stepUnits (±1e-6);
  - nudge is right under shuffle (odd steps);
  - the ±0.5 clamp;
  - held time → Length event plus the pattern's hit length; the 16-step clamp;
  - Stop and disarm close open notes before `armed=false`;
  - drums get no Length;
  - Snap = the old rules;
  - a full queue → nothing written;
  - AllocationCounter = 0 over record on/off.
- JUnit `QuantiseTest` (100/50%, length rounding, gate hits kept, the three scopes, the glow predicate) and `PatternStoreTest` (applyRecorded for Note with nudge, Length, Erase).

## 2. Replace record and erase while recording
**Engine (A):**
- Replace (`replace && armed && playing`, for `replaceSlot`'s current pattern).
  - Synth: each step is cleared (hits only, motion kept) the first time the playhead or a live note reaches it in this arm. `uint64_t replacedMask` per slot.
  - Drum: per sound, starting from your first hit of that sound in the take. `uint16_t replacingLanes` plus `std::array<uint64_t,16> laneMask`.
  - Masks reset on arm, on pattern switch and on Replace off. Every clear emits Erase(−1 or note) before the write.
- Erase: `setErase(slot, note, active)` (note −1 = every note).
  - While armed and playing, `fireStep` removes matching hits before the step sounds, calls `dropPendingHits`, and reports Erase per hit.

**Kotlin (C):**
- Shift + tap Record calls `setRecordOptions(feel, true, selectedSlot)`.
- The Clear-hold plus cell/key routing per section 0. Cells and keys used for erasing never `noteOn`.

**Tests first:**
- GT `SequencerReplaceEraseTest`:
  - first pass clears and later passes overdub;
  - an early live note into the next step clears that step first and is kept;
  - drum replace leaves untouched lanes alone;
  - an erased hit never sounds, including an early-nudged one already scheduled by the look-ahead;
  - Erase events match the writes;
  - nothing happens unarmed or stopped.
- JUnit `RecordButtonTest` (Shift-tap = replace, long-press capture, Shift long-press = feel toggle, strip moved = motion only) and `ClearEraseTest` (the gesture state machine).

## 3. Capture (last 8 bars per slot)
**Audio side (A, `Sequencer`):**
- `recordLiveNote` and `recordLiveNoteOff` also push `PlayedNote{slot, note, vel, int64 position, uint32 playCount, bool on}` into a preallocated `SpscQueue<PlayedNote, 2048>`, while playing only, armed or not, never auditions.
- Full queue → dropped. Positions are musical units, so tempo changes don't matter.

**Control side (A, new `CaptureBuffer.h/.cpp`):**
- A fixed per-slot ring of 512 `{pos, lengthUnits, note, vel}`, preallocated.
- `ingest()` prunes entries older than latest − 8 bars (`barUnits` = 4·unitsPerBeat) and clears everything when playCount changes. Stop keeps the ring.
- `extract(slot, rate, shuffleMilli, unitsPerBeat, CaptureResult&)`:
  - the span runs from the bar holding the first note in the window to the bar end after the last;
  - it is capped at 64 steps at that rate (1/16 = 4 bars, 1/8 = 8, 1/32 = 2, 1/16T = 2), keeping the latest bars;
  - each note goes to the nearest shuffled step (wrapping to step 0 with a negative nudge);
  - hold is synth only (a note still held runs to the latest position);
  - a repeated note on a step and a 9th hit are dropped.

**B:**
- `AudioEngine` owns a `CaptureBuffer`, pumped in `nativePollRecorded` and `nativeCapture`.
- JNI `nativeCapture` serialises the result.

**C:** long-press writes into the slot's next empty pattern, using the slot's current settings and the result's steps, then queues it. One undo step. Flash `captured 2 bars → pattern 5`, or `nothing to capture`.

**Tests first:**
- GT `CaptureBufferTest`:
  - pruning to 8 bars;
  - the playCount reset;
  - span and cap rules per rate;
  - playback of the extracted pattern reproduces the note-on frames within 1 frame (nudge under shuffle);
  - wraps, duplicates, the 8-hit cap.
- GT `SequencerCaptureLogTest`: pushes while playing and unarmed; none when stopped or for auditions; no allocation.
- JUnit `CaptureWriteTest`: result → steps, next empty pattern, none free.

## 4. Click, count-in, click level
**Engine (A):**
- New `Click` (Click.h/.cpp, an `Instrument`): a 12 ms decaying sine, 1.6 kHz accent / 1.1 kHz beat. It renders offline into a buffer.
- `Sequencer::attachClick(Instrument*)`, `setClick(on)` and `startCountIn()`:
  - when stopped: `countIn_` with beats at `frame + framesBetween(0, k·unitsPerBeat)`, k = 0..3, always clicking;
  - `doPlay(endFrame)` at exactly `framesBetween(0, barUnits)`;
  - `advance()` splits at click and count-in frames;
  - a tempo change re-anchors the count-in;
  - Stop, disarm or Play during the count-in end it (Play starts at once);
  - while playing, beats click when the click is on, accent on the bar.
- `TransportState.countInBeat` (0 = none, 1..4).
- Should: a note in the last half step before the downbeat records into step 0 with a negative nudge.

**B:**
- `MixParams.def` appends `ClickLevel "mst.click" "Click" −60..0, default −12 dB` (it shows on the Master strip).
- `AudioGraph` renders the Click with the slots, into its own chunk buffer. In `mix()` it is added **after the tape's process and after the master gain, before the MasterBus limiter**, so it is never on tape, commit, export or the slot buses.

**C:**
- Record tap while stopped → arm + `startCountIn()`. The top bar shows `count 3`.
- Shift + tap BPM toggles the click. Click on/off is saved in v5; the level goes in the session's mix map automatically (keyed by name).

**Tests first:**
- GT `ClickTest` (deterministic, accent louder).
- GT `SequencerClickTest`:
  - beat frames are exact;
  - the count-in gives 4 clicks, then Play lands on frame0 + bar;
  - Stop cancels;
  - a tempo change mid count-in;
  - clicks only when on, while playing.
- GT `AudioGraphClickTest`:
  - the click is absent from `buses().slotPost*` and the tape input;
  - level −60 = silence;
  - the master meter includes it.
- JUnit `MixParamsContractTest` (+ `MixParamsDef.kt` mirror) and `TransportStateTest` (SIZE 28).

## 5. Drum sound mute/solo; slot mute/solo chips
**B, `DrumMachine`** (the plan is the lead's sign-off for the public API change):
- `void setSoundMutes(uint32_t muteMask, uint32_t soloMask)` (control) → two `std::atomic<uint32_t>`.
- `renderStereo` reads them once per call. Audible = solo ? solo bit : !mute bit.
- `seqNoteOn` for a silenced key is ignored. Its sounding voices take the existing 5 ms cut when it becomes silenced.
- UI `noteOn` (pads, keys) always plays.

**B, `AudioGraph`:**
- `setSoloMask(uint32_t channels)`, an atomic with bits 0..11.
- `applyMixParams`: strip mute = user `ch.mute` || (mask && !bit).
- `startExport`, loop and stem exports read user mutes only, never the mask.

**C:**
- `MixValues.toggleSolo` sends `setSoloMask` instead of mutes. Delete `sendMutes()`, and the `clearSolo()` calls in `exportPress` and `refreshFromEngine`.
- Solo persists across screens (Mixer, Seq and Synth chips share `MixValues.soloed`). It is cleared on session load and is never saved.
- A single-slot commit of a slot the solo silences clears the solo first.
- Footer chip **M** in Seq (after Q) and in Synth (beside the sound Undo): tap = mute, Shift + tap = solo; the hollow coral look = soloed.
- New `DrumSolo` model: kit-cell gestures, masks.
- Sound mutes are saved (v5); sound solo is not.

**Tests first:**
- GT `DrumMuteTest`: a muted key's seq hit is silent and a pad hit still sounds; solo wins over mute; a sounding voice fades in ≤5 ms; no allocation.
- GT `AudioGraphSoloTest`: a soloed slot is audible and others silent; user mutes are untouched; `startExport` settings ignore the solo.
- JUnit `MixerSoloTest` (the mask replaces the engine mutes) and `DrumSoloTest` (tap/double-tap state machine, masks, solo not in the snapshot).

## 6. Per-step Slide (synth)
**Data:**
- `Step` gains `int32_t flags` (bit 0 = `kStepSlide`). The `static_assert` becomes 4 + 4 + 8·20 = 168, and `Command` stays ≤ 208.
- `setStep(..., int32_t flags = 0)`. Flags are kept only while hitCount > 0.

**Engine (A):**
- `Instrument::seqSlide(int fromNote, int toNote, float vel)`, default = seqNoteOn(to) then seqNoteOff(from).
- Sequencer:
  - when the next played step has Slide and hits, the offs of this step's sounded notes, late nudged hits included (`SlotState::slideHoldStep`), become **held** (frame INT64_MAX);
  - at the slide step, held notes pair low-to-high with its chord (one chance roll) through `seqSlide`, with unpaired holds released now and unpaired new notes sounding normally;
  - a slide step ignores nudge;
  - a held off not used by the next `fireStep` (chance fail, loop, switch) is released then;
  - Stop releases everything (as today);
  - the drum slot ignores the flag.
- `SynthEngine::seqSlide`:
  - glide time = `OscGlide` if > 0, else `kDefaultSlideSeconds = 0.06`, via a copied `VoiceControl`;
  - Poly: the voice holding `from` (sequencer source only) is retargeted with legato (`trigger(to, vel, false, c)`) and `noteSources` and `held_` move from → to;
  - Mono/Unison: a legato trigger on all voices, then `from` is released (not top → no retrigger);
  - if `from` isn't found, or is also held by the keys, it is a plain note-on.

**C:**
- `Step.slide` in `PatternStore`.
- `SeqStrips.Slide` (Enum off/on) on StepShift strip 2 for synth slots.
- A tie line on the pad.
- Edits (×2, rotate, copy, move, Spread) carry the flag. Clear removes it.

**Tests first:**
- GT `SequencerSlideTest`:
  - the previous note's off is deferred to the slide step and replaced by `seqSlide` (TestInstrument records it);
  - chord pairing;
  - chance fail and a loop release the hold;
  - drums ignore the flag;
  - nudged late hit held;
  - transpose applies to both notes.
- GT `SynthSlideTest`:
  - Mono and Poly: frequency moves continuously from A to B within the glide time;
  - amp level has no dip or retrigger;
  - exactly one voice active in Poly;
  - a non-slide step retriggers.
- JUnit `SlideEditTest`, `SeqStripsTest` (strip 2 = Slide on synth, null on drum).

## 7. Live transpose (engine)
In the engine, so offs always match ons, the pattern data and undo stay untouched, and exports include it.
- `Sequencer::setTranspose(int st)`, clamped ±24.
- `sound()` sends note + t (0..127) to synth slots. Offs, holds and slides store the **sounded** note. Pending, look-ahead and suppress lists keep raw notes.
- `recordLiveNote` stores note − t, so playback matches what you played.
- Keys sound as pressed.

**C:**
- Shift + tap key (section 0) → `t = note − (60 + 12·keyOctave)`.
- Readout `+2` / `−3` on the Synth display and in the Seq header (hidden at 0).
- Saved in v5. Not undoable.

**Tests first:**
- GT `SequencerTransposeTest`:
  - synth on and off notes +t, drums untouched;
  - a change mid-note keeps the original off;
  - recording stores −t;
  - 0..127 clamp.
- JUnit `TransposeTest`: key → semitones with the octave; text.

## 8. Export loop × N bars and stems
**Pattern snapshot (A):**
- `struct SequencerSnapshot { std::array<Pattern,kSlotCount> patterns; int32 tempoTenths, transpose; std::array<std::atomic<bool>,kSlotCount> ready; }`.
- `requestSnapshot(SequencerSnapshot*)`: the control thread publishes the pointer (a release store to `snapshotDst_`), then pushes one `Snapshot` command per slot. The audio thread copies that slot's **current** pattern (about 12.6 KB) and sets `ready`.
- `loadSnapshot(const SequencerSnapshot&)` is setup-time only: patterns go to index 0, tempo and transpose are set.

**B, new `LoopExport.h/.cpp`:**
- `LoopRenderer` owns a Sequencer, DrumMachine (`waitForKit`), 7 SynthEngines, 8 ChannelStrips, delay, reverb and a MasterBus. It runs `AudioGraph`'s slot/FX/master math without the tape or the click.
- It is set from a `LoopExportJob`: the snapshot, synth params per slot (`ParamStore` gets), drum params, mix and channel params (user mutes), drum **mute** mask (no solo), and the sample rate.
- `LoopExporter`: its own thread waits for `ready` (`pumpIfStopped` like TapeStore), renders and discards one priming pass, then writes `bars × framesBetween(0, barUnits)` frames as 24-bit WAV.
- Factor the WAV writing out of `TapeStore::exportRun` into a TapeFiles `WavWriter` (B owns both).

**B, stems:**
- `TapeStore::startStemExport(prefix, settings, pump)`: one snapshot, then `<prefix>-mix.wav` (the current export) plus `<prefix>-trackN.wav` per track with audio. Each stem = that track's strip × master volume, with no limiter, over the same region rule as today's export.
- `AudioGraph::startLoopExport` and `startStemExport`. `exportProgress()` reports the running job; one export at a time.

**C:**
- `LoopExportBars.bars(currentPatterns)`: the LCM of the current pattern lengths (exact units) rounded up to whole bars, capped at 16, repeated to at least 4 bars, at most 64.
- `ExportHost.newExportPrefix()` and `shareAll(paths)` (ACTION_SEND_MULTIPLE).
- `ExportTracker` handles a list of paths.

**Tests first:**
- GT `SequencerSnapshotTest`: copies current patterns, no allocation, applied when stopped.
- GT `LoopExportTest`:
  - frames are bit-identical to a live `AudioGraph` (empty tape, same state, after the same priming pass);
  - the exact frame count;
  - solo and click are excluded and drum mutes included;
  - fails cleanly on a bad path.
- GT `StemExportTest`: track stems match the renderer with one track; files exist only for tracks with audio.
- JUnit `LoopExportBarsTest` and `ExportFlowTest` (multiple files).

## 9. Session format v5 (C, `SessionCodec`)
- `VERSION = 5`.
- In `writePattern`, after each step's hits when version ≥ 5: `writeInt(step.flags)`.
- A new tail block when ≥ 5: `transpose int, drumMuteMask int, recordFeel bool, clickOn bool`. It goes in `Session.perform: PerformSnapshot` with defaults.
- `upgrade()` for v ≤ 4: slide false, transpose 0, no mutes, **Feel on**, click off.
- `mst.click` travels in the mix map. A missing key reads as the param default (test this).
- Solo is never written.
- Tests (`SessionCodecTest`): v5 round trip; v4 file → defaults; v4 with no `mst.click` → −12; solo absent.

## 10. Interface contract (fixed before work starts; A lands headers with stubs as its first commit)
C++ (core):
```
// Pattern.h
inline constexpr int32_t kStepSlide = 1;        // Step::flags
// Sequencer.h (control)
bool setStep(int slot,int pattern,int step,const Hit*,int count,int32_t flags=0);
bool setRecordOptions(bool feel,bool replace,int replaceSlot);
bool setErase(int slot,int note,bool active);   // note -1 = all
bool setClick(bool on);  bool startCountIn();  bool setTranspose(int semitones);
bool requestSnapshot(SequencerSnapshot* dst);   void loadSnapshot(const SequencerSnapshot&);
bool popPlayed(PlayedNote& out);
// Sequencer.h (audio)
void recordLiveNoteOff(int slot,int note,int64_t frame); void recordLiveAllOff(int64_t frame);
void attachClick(Instrument*);                  // setup only
// RecordedEvent: Kind{Note,Motion,Length,Erase}; + float nudge.  TransportState: + int32_t countInBeat
// Instrument.h: virtual void seqSlide(int fromNote,int toNote,float velocity);
// CaptureBuffer.h: void ingest(const PlayedNote&); bool extract(int slot,StepRate,int32_t shuffleMilli,int64_t unitsPerBeat,CaptureResult&);
// DrumMachine.h: void setSoundMutes(uint32_t mute,uint32_t solo);
// AudioGraph.h: bool setSoloMask(uint32_t); bool startLoopExport(const std::string&,int bars,pump);
//               int startStemExport(const std::string& prefix,pump) /* file mask, 0 = none */;
```
JNI (B), `com.groovebox.engine.NativeEngine`:
- `nativeSetStep(IIIFI I)Z` gains `jint flags`.
- `nativeSetRecordOptions(ZZI)V`, `nativeSetErase(IIZ)V`, `nativeSetClick(Z)V`, `nativeStartCountIn()V`, `nativeSetTranspose(I)V`, `nativeSetDrumMutes(II)V`, `nativeSetSoloMask(I)V`.
- `nativeCapture(IIF[F)I`: returns the hit count, or −1. `out[0]` = steps, then per hit `[step, note, vel, length, prob, nudge]`. Size 1 + 512·6.
- `nativeStartLoopExport(Ljava/lang/String;I)Z`, `nativeStartStemExport(Ljava/lang/String;)I`.
- `kRecordSize` 6 → 7 (`[kind, slot, pattern, step, noteOrTarget, value, nudge]`).
- `kTransportSize` 27 → 28 (countInBeat appended at index 27).

Kotlin `NativeEngine.kt` (C):
- `setStep(slot, pattern, step, hits, hitCount, flags = 0): Boolean`
- `setRecordOptions(feel, replace, slot)`, `setErase(slot, note, active)`, `setClick(on)`, `startCountIn()`, `setTranspose(st)`
- `setDrumMutes(mute, solo)`, `setSoloMask(mask)`
- `capture(slot, rate, shuffle): CaptureResult?`
- `startLoopExport(path, bars): Boolean`, `startStemExport(prefix): Int`
- `STEP_FLAG_SLIDE = 1`, `CAPTURE_BUFFER_SIZE = 3073`
- `RecordedEvent.Kind {Note, Motion, Length, Erase}` + `nudge`; `TransportState.countInBeat`

## 11. Agents (non-overlapping files; CMake globs `core/src/*.cpp` and `tests/*Test.cpp`, so no CMake edits)
- **A, sequencer, record and synth:** `Pattern.h`, `Sequencer.h/.cpp`, `Instrument.h`, `SynthEngine.h/.cpp`, `Voice.h/.cpp`, new `Click.*`, `CaptureBuffer.*`; tests `SequencerTestUtil.h`, `SequencerTest.cpp` (size asserts), new `SequencerFeel/ReplaceErase/Slide/Click/Transpose/Snapshot/CaptureLogTest`, `CaptureBufferTest`, `ClickTest`, `SynthSlideTest`. First commit: the contract headers with no-op stubs.
- **B, drums, graph, export and JNI:** `DrumMachine.*`, `AudioGraph.*`, `MixParams.def`, `TapeStore.*`, `TapeFiles.*`, new `LoopExport.*`, `engine/android/*`; tests `DrumMuteTest`, `AudioGraphSoloTest`, `AudioGraphClickTest`, `LoopExportTest`, `StemExportTest`, `RealtimeSafetyTest` (new paths: feel/replace/erase/slide/click/count-in/mute/solo/snapshot under AllocationCounter), `EngineTestUtil.h`. B works against A's stubs and integrates last.
- **C, Kotlin:** everything under `app/` (NativeEngine, GrooveState, SeqController, MixController, TopBar, SeqScreen, SynthScreen, KitGrid, Keyboard, MainActivity, `ui/model/*`, `storage/*`) and its JUnit tests, including `MixParamsDef.kt`.
- Order: A's headers, then A, B and C in parallel, then B integrates A, then C runs against the real JNI, then a strict review against this plan and the audio rules, then the CI build for the owner.

## 12. Audio thread rules, how each item meets them
- Every new audio-side structure is a fixed array in `SlotState` or a member: open notes [128], replace masks, held-for-slide offs reusing `offs`, click schedule, mute/solo/solo-mask atomics, and the `PlayedNote` SPSC queue (2048).
- The snapshot copies into a buffer the control thread made, one slot per command, with a release/acquire flag.
- Control → audio only through the existing command SPSC queue or atomics. Audio → control only through `recorded_` and the `PlayedNote` SPSC queue (drop on full, report-then-write).
- Capture pruning and extraction, loop rendering, WAV writing and kit waits run on the control or exporter threads only.
- Every clock event (count-in end, clicks, held slides) is an exact integer position landed by `frameOf`, and `advance()` splits there.
- Every new block (Click, LoopRenderer, slide) renders offline on the host. Each commit that touches the callback names the AllocationCounter or RealtimeSafetyTest cases that cover it.

### Critical files
- engine/core/src/Sequencer.cpp (+ include/groove/Sequencer.h, Pattern.h)
- engine/core/src/AudioGraph.cpp (+ AudioGraph.h)
- engine/core/src/SynthEngine.cpp
- engine/android/JniBridge.cpp
- app/src/main/java/com/groovebox/ui/GrooveState.kt (+ engine/NativeEngine.kt, storage/SessionCodec.kt)
