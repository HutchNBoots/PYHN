# Handoff (17 Oct 2026)

Start here in a new session. Read `CLAUDE.md` first, then this file.

## Where things stand
- **Latest build:** build-20, green, installed by the owner
  (https://github.com/HutchNBoots/PYHN/releases/tag/build-20). Branch `ccr-8d023090-x5f91s`; CI publishes
  `build-N` (N = run number) on every push without `[skip ci]`.
- **Shipped in build-20** (plan `docs/plans/2026-10-16-build-20-sequencer-lanes.md`): per-hit steps (up to 8,
  each with velocity, length, chance, nudge), drum lanes with the Seq sound grid, long-press latched step
  selection, step strips, sequencer undo (32 deep), Shift + Clear lane, session format v3, voice-mode fix,
  tape varispeed with tempo, whole-bar commits, mixer return markers, build number in the debug panel.
- **Owner feedback on build-20:** the Synth screen is close to a beta target; the keys' rounding should be
  reduced to match the rest of the app; the Drum screen's 4 × 4 grid looks obsolete and doesn't line up
  with the Seq view.
- **Phase now: manual-led CX research. No app code changes until the owner returns the feedback sheet.**

## The research (all in `docs/`)
| File | What it is |
| --- | --- |
| `manual/00-scope.md` | Method (goal, start, steps, score, friction, ideal flow) and the manual structure. |
| `manual/manual.html` | The manual draft, built by `python3 docs/manual/build_manual.py` (content in `build_manual.py`, `manual_more.py`, `screens.py`; screenshots in `manual/img/`). Published for the owner at https://claude.ai/artifact/6F8nSf4oCy8tZ9Aysj6op9 (republish with the Artifact tool, same URL). |
| `manual/cx-feedback.xlsx` | The owner's feedback sheet: Top findings, Consistency review, Tasks (60), Ideas (32), Questions (6). Yellow columns: Decision (Leave as is / Take suggested change / Different change (see comment) / Not sure), Your comment, Priority (Must/Should/Could/Won't). |
| `reviews/2026-10-17-consistency-review.md` | Visual and interaction consistency review (radius/type/colour tokens, drum grid, names, tape clear). |

Manual sections written: 0 Screen map + glossary, 1 Synth sounds, 2 Drum kit, 3.1 Drum beat,
3.2 Synth part (T1–T35: viewing, entering, bassline, chords, melody, arranging, feel, performance),
8 Ideas from other grooveboxes (incl. worked proposals for "double my beat" (×2 chip) and
"quantise my beat" (keep feel on record + Q chip)). **Paused at the owner's request: 4 Tape.**
Not written: 0 Getting started (grip, Shift, gestures), 3.3 Patterns, 3.4 Parts together, 5 Mix, 6 Performing, 7 Session.

## Biggest findings so far
1. Note strip flattens a multi-step selection to one pitch (bug, `SeqController.kt:126-137`).
2. Pitch, chords, velocity, length, nudge invisible without long-pressing each step; a wobbly long-press deletes.
3. No copy of a pattern, page or step; no ×2.
4. Chords need 3–4 fingers; no add/remove of one note.
5. Keys close the preset strip, so presets can't be auditioned.
6. No undo for synth or drum sound edits; double-tap resets to the engine default, not the preset.
7. Latched Shift turns the Undo chip into Redo.
8. Drum names on the phone are initials (DK, CS, Cras…); Drum map upside down vs the Seq grid.
9. Live record always snaps (no feel), drops held length, no count-in; drums can't be recorded from Seq.

## Open owner decisions
Kick bottom-left or top-left in the drum grid · keep feel on live record by default · room for
Undo · ×2 · Dup · Q chips · Capture in v1 · sound mute via Shift + grid tap · Drum ↔ Synth swipe ·
two-thumb proposals P2–P6 (ADR 0003). Also still owed: the 30-minute soak numbers.

## Next steps
1. Owner fills in `docs/manual/cx-feedback.xlsx` (download from the repo) and sends it back.
2. Read the decisions; write `docs/manual/cx-findings.md` (ranked, decided items only).
3. Plan the next build with `writing-plans` in `docs/plans/`; likely first: Note strip fix, drum grid +
   names, key rounding, ×2 / page copy, Q + keep-feel record, Undo/Redo under Shift, preset audition.
4. Continue the manual for the unwritten sections when the owner asks.

## Working notes for Claude
- Cloud sessions can't build Android (no SDK); CI is the Android check. Host checks that work:
  engine `cmake -S engine -B build/x -DGROOVE_BUILD_TESTS=ON` (+ GCC sanitizers), and scratch Kotlin
  harnesses (host tests, compose type-check, offscreen renders). The scratch harnesses live in the old
  session's scratchpad and won't exist in a new one; recreate them if needed (see git history of this file's
  predecessor plans for the approach: kotlin jvm + compose-desktop 1.8.2 against `app/src/main/java`).
- Renders use a fake engine; set its drum names to the real ones (`DrumKit.cpp`) or reviews miss the phone's labels.
- Commits: end with the Co-Authored-By and Claude-Session trailers; `[skip ci]` for docs-only snapshots.
