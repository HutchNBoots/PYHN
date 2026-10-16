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
