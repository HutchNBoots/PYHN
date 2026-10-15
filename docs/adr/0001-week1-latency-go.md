# ADR 0001: Week-1 latency spike — go

Date: 15 Oct 2026 · Status: accepted

## Context

The spec sets a tap-to-tone target of 35 ms or less on the Galaxy S25+, with 25 ms as the
stretch goal, and asks for a go/no-go before any feature work
(`docs/vision-cx-spec.md`, "Reference device and technical constraints").
The spike (`docs/plans/2026-10-15-week1-latency-spike.md`, build-1) plays one oscillator
through Oboe (AAudio, exclusive, low latency) and measures tap-to-tone on the device.

## Result

- The owner reported a tap-to-tone of **about 16 ms** on the S25+ (build-1, in-app figure).
- That beats the stretch goal by about 9 ms.
- The in-app figure leaves out touch-panel scan time (a few ms), so felt latency is
  likely around 20 ms. That still meets the stretch goal.

Not recorded in this round: the figures for each output (headphones and speaker), the
lowest crackle-free burst setting, the stream line, and an OboeTester round trip.
Get them at the first milestone build that has real DSP load (4 voices plus FX), where
latency headroom matters most.

## Decision

**Go.** Keep the architecture as built: a C++ engine on Oboe AAudio in exclusive,
low-latency mode; a callback that only renders; and SPSC queues between the UI and audio.
Feature work can start, beginning with screen mockups (spec next step 2).

## Consequences

- The buffer size stays user-selectable in debug builds until a later build shows which
  setting survives full DSP load without xruns.
- Each milestone build keeps the tap-to-tone readout (on a debug panel) so regressions show up.
