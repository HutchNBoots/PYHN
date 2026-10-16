# ADR 0003: Two-thumb grip

Date: 16 Oct 2026 · Status: accepted (owner CX refinement)

## Decision

The app is designed to be played holding the phone in both hands, landscape, with two thumbs:

- **Right thumb: the strips.** Dragging a colour strip sets values on every screen.
- **Left thumb: selection.** Choosing what the strips edit, such as the synth section rail,
  drum keys, step pads and mixer channels, should be reachable from the left half of the
  screen without stretching to the top bar.
- **Nothing important under a working thumb.** Feedback a thumb would cover (a strip's
  value) sits at the top of the control, under its label, not at the bottom.

## Applied in the next build

- A strip's value now sits right under its label (top of the strip), not at the bottom.
- Mixer: each level bar in the centre display is a selection target (tap = select that
  channel, Shift + tap = mute), so the left thumb never has to reach the pads or top bar.

## Check for every new screen

Ask: what is the right thumb setting, what is the left thumb choosing, and can both reach
without shifting the grip? Anything only reachable from the top bar needs a second,
thumb-reachable route.

## Review: Synth, Seq and Tape (16 Oct 2026, v1 close-out)

**Reach model.** The phone is held in both hands, landscape. In the 1040 × 480 design frame:
the left thumb covers x < 416 (left 40%) and y > 120 (lower 75%); the right thumb covers
x > 624 and y > 120. The top bar (y 10–58) is above both zones: its two ends are a
**stretch** (reachable by lifting the thumb a little), its centre (x 416–624) is **no**.
"Yes" below means inside a thumb's zone without changing grip.

### Top bar (shared)

| Control | Where (x) | Thumb | Reachable? | Fix done / proposal |
| --- | --- | --- | --- | --- |
| Screen tabs | 14–350 | Left | Stretch (top-left) | None needed: navigation, not played live. |
| Slot buttons 1–8 | 360–708 | Either | **No** (top centre) | **Done:** swipe the Synth or Seq display sideways to step slots (left thumb). **Done (P1):** long-press the Synth display for the presets. |
| Play / Stop | 838–886 | Right | Stretch (top-right) | Proposal P2. |
| Record (Synth, Seq) | 896–944 | Right | Stretch | Tap-to-arm is fine. "Hold Record + move a strip" needs Record and a strip at once, both under the right thumb: proposal P3. |
| Record (Tape) | 896–944 | Right | Stretch | **Done:** double-tap a tape lane to arm it, or stop its take (left thumb). |
| Shift | 954–1026 | Right | Stretch | A tap latches, so Shift + strip and Shift + pad work after one stretch. A hold (momentary) plus a strip needs two right-hand fingers: proposal P4. |
| Undo / Redo (Tape) | 360–448 | Either | **No** (top centre) | Proposal P5. |
| Export WAV (Tape) | 458–586 | Either | **No** (top centre) | Left as is: a deliberate one-off action, not played live; 3 taps still hold. |

### Synth

| Control | Thumb | Reachable? | Fix done / proposal |
| --- | --- | --- | --- |
| Section rail (Osc, Filter, Env, Mod) | Left | Yes (Osc sits at the top edge of the zone) | None. |
| Display | Left | Yes | **Done:** horizontal swipe steps the synth slots (finger left = next, ~58 dp per slot, clamped 2–8; never jumps to the Drum screen). Title tap (debug) unchanged. |
| Four strips | Right | Yes | None (value under the label, as above). |
| Keys | Both | Yes | None. |
| Preset strip (long-press a slot) | Left | Yes, via the display | **Done (P1):** long-press the Synth display opens the strip for the selected slot. |

### Seq

| Control | Thumb | Reachable? | Fix done / proposal |
| --- | --- | --- | --- |
| Page rail 1–4 | Left | Yes | None. |
| Clear (two taps) | Left | Yes | None. |
| Pattern display | Left | Yes | **Done:** horizontal swipe steps slots 1–8 (the kit included), so the left thumb picks whose pattern to edit. |
| Four strips (and Shift layer) | Right | Yes | Shift layer needs Shift (P4). |
| Step pads 1–6 / 11–16 | Left / right | Yes | None; a loop (two pads held) works one thumb per pad. |
| Step pads 7–10 | Either | Stretch (bottom centre) | None: the bottom edge, a short reach. |
| Step select (Shift + pad) | Right then left | Stretch for Shift, then yes | Works with Shift latched; P4 would remove the stretch. |
| Step-edit keys | Both | Yes | None. |

### Tape

| Control | Thumb | Reachable? | Fix done / proposal |
| --- | --- | --- | --- |
| Lanes T1–T4 (tap select, hold clear) | Left | Yes (T1 at the top edge of the zone) | **Done:** double-tap a lane = Record on that lane. Footer and accessibility text say so. |
| Four strips; Commit (Shift layer) | Right | Yes | Commit needs Shift (P4). |
| Keys | Both | Yes | None. |
| Slot choice (what the keys play, what Commit takes) | — | **No route on this screen** (no slot buttons on Tape) | Proposal P6. |

**Double-tap rules** (`TapeView.laneDoubleTap`, tested): idle → arm that lane (only at 1×,
forward); armed or recording on that lane → stop / cancel; busy on another lane → nothing,
so a stray double-tap never ends a take. A single tap still selects on touch-down and a
hold still clears.

### Proposals for the owner (not built)

- **P1 Presets without the top bar. Built (owner: "do P1").** Long-press the Synth display (or Shift + display tap)
  opens the preset strip for the selected slot. Small, but it takes a gesture from the
  display, so it's the owner's call.
- **P2 + P3 Transport in reach.** Move Play and Record into a small cluster in the
  bottom-left corner (left thumb), so "hold Record + move a strip" is one thumb each. This
  narrows the keyboard by about one key per octave, which changes the approved layout.
- **P4 A second Shift by the strips.** A Shift pad under the strip row (right thumb), or let
  the bottom-right key area double as Shift. Same layout cost as P2; could share its space
  on the right.
- **P5 Undo in reach on Tape.** An Undo chip at the left end of the Tape footer, or Shift +
  double-tap a lane = undo.
- **P6 Slot choice on Tape.** A sideways swipe on the lanes would clash with a later tape
  scrub, so instead: a slot chip in the Tape footer (tap to step), or let Commit's strip be
  dragged to choose the source slot.
