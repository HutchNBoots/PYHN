# Android Groovebox — Vision & CX Spec

Last updated 15 Oct 2026

## Vision

A pocket groovebox for Android that goes from an idea to a finished loop in under a minute, with the S-1's immediacy and the OP-1's creative world. Working title only; the name and visual identity will be original.

**Design principles**

- **One grammar everywhere.** Every screen is driven by the same four colour-coded controls, so learning one screen teaches them all.
- **Sound first, menus never.** Any change you can hear is at most one tap away from where you are playing.
- **Happy accidents.** Probability, randomise and odd sequencers are built in, not hidden.
- **The phone is the instrument.** Tilt, touch pressure and the speaker are features, not compromises.
- **Finish things.** Every session can end as an exported track or a shareable loop.

**What we borrow, and what we leave**

| From | We take | We leave |
| --- | --- | --- |
| OP-1 | Four-encoder control grammar; swappable synth engines; playful sequencers; 4-track tape as the arranger; master FX and mixer | Its names, engine names, graphics and look |
| S-1 | Subtractive voice (OSC, filter, amp, env, LFO); 64-step sequencer with motion recording, probability and step loop; tilt control; oscillator draw | Roland naming, panel layout and the SH-101 identity |

Sources: [OP-1 field guide](https://teenage.engineering/guides/op-1) · [S-1 owner's manual](https://static.roland.com/manuals/s-1_manual_v102/eng/index.html)

## Personas

Three personas, in priority order. v1 is designed for the Noodler and must not block the other two.

| Persona | Who | Where and when | Wants | Frustrated by |
| --- | --- | --- | --- | --- |
| **The Noodler** (primary) | Hobbyist, may own an OP-1 or Pocket Operator | Sofa, commute, headphones, 10–30 min bursts | Capture an idea fast, get surprised, keep a sketchbook of loops | DAW-style apps on phones; losing an idea while menu-diving |
| **The Performer** | Live electronic musician with Aira Compact or similar gear | Jam with other gear via USB-MIDI, small gigs | Tight clock sync, motion and step loop for live variation, hands-on control | Jitter, latency, controls that move when touched by accident |
| **The Beginner** | Curious, no synth knowledge | Phone speaker, short sessions | Sound good in 30 seconds, learn by playing | Jargon, blank screens, nothing happening when they tap |

All three share one rule: the app should never ask a question before it makes a sound.

## Core loops and success metrics

Four loops define the product; each has a target we can test with real users on the S25+.

| Loop | What the user does | Target |
| --- | --- | --- |
| 1. First sound | Open app → tap a key | Sound within 2 s of launch; zero dialogs before it |
| 2. Idea to loop | Pick a sound → record or step-enter a pattern → it loops | 8-bar loop in under 60 s for a first-time Noodler |
| 3. Tweak | Change a sound while the pattern plays | Any parameter reachable in at most 1 tap from the play screen |
| 4. Keep it | Commit loops to tape, arrange, export | Export a WAV or share a loop in under 3 taps from tape |

**Quality targets**

- Tap-to-sound latency feels instant (budget in the technical constraints section).
- No audible glitches during a 30-minute session with 4 tracks and FX running.
- Every session autosaves; a crash or call never loses more than the last 5 s of work.
- A Beginner can describe what the four colour controls do after 5 minutes, with no tutorial.

## Interaction grammar

Landscape, one hand on the keys, one on the controls: four colour strips always sit to the right of the display, above the keys, and always mean "the four things that matter on this screen."

**Screen zones (top to bottom)**

1. **Top bar:** Synth · Drum · Sequencer · Tape · Mixer and the 8 sound slots on the left; transport (play, record, tempo, shift) on the right, always in reach.
2. **Display:** left of the middle row. The playful visual for the current engine or sequencer; it shows what the four controls are doing and is not itself a control.
3. **Four colour strips:** right of the middle row, side by side, each with its own fixed colour. Drag vertically on a strip to change its value; double-tap to reset. Long-press locks a strip during live play.
4. **Keys / steps:** the full width of the bottom. Two octaves of keys, which flip to a single row of 16 step pads in Sequencer mode (four pages for 64 steps), like the S-1.

_Layout diagram: see the live doc (landscape, 4 zones: top bar, display + four strips, full-width keys/steps)._

Landscape gives a full-width keyboard and all 16 steps in one row; the strips sit under the right thumb while the left hand plays.

**Gestures and modifiers**

- **Shift + strip:** the second layer of parameters on that screen.
- **Shift + key:** select a step to edit its note, velocity, probability and motion.
- **Hold record + move a strip:** records motion for that parameter into the pattern.
- **Tilt:** assignable to any one or two parameters, with a calibrate-and-hold button so a phone on a table doesn't drift.

**External control**

- USB-MIDI class-compliant controllers map their first four knobs to the four strips by default.
- MIDI clock in and out for syncing with Aira Compact, Pocket Operators and DAWs.

## Feature scope

The MVP is one great synth, one drum kit, the S-1-style sequencer and a simple tape: enough to prove loops 1–4 on the S25+. Change any release value as we decide.

| Feature | Release | Notes |
| --- | --- | --- |
| Subtractive synth engine (OSC, sub, noise, filter, amp, env, LFO) | MVP | 4 voices; S-1-style section layout behind the four strips |
| Drum sampler with one built-in kit | MVP | One sample per key, pitch and envelope per key |
| 64-step sequencer, 8 slots × 16 patterns | MVP | Probability, shuffle, motion recording, step loop |
| Four colour strips + shift layer | MVP | The core interaction grammar |
| 4-track tape with loop, overdub, undo | MVP | Arrangement and commit model |
| Master FX (delay, reverb) + simple mixer | MVP | Volume, pan, mute per track |
| WAV export + autosave | MVP | Share sheet for export |
| Tilt control (gyro) | v1.1 | Calibrate-and-hold; assign 1–2 parameters |
| USB-MIDI in/out + MIDI clock sync | v1.1 | Performer persona |
| Oscillator draw | v1.1 | Draw a waveform on the display |
| Playful sequencers (endless, random, arp) | v1.1 | Each one a separate small module |
| Second synth engine (FM or wavetable) | Later | Proves the engine plug-in architecture |
| Sampling from mic and USB audio | Later | Permissions and latency work |
| Project sharing / community loops | Later | Needs a backend; out of scope until the instrument is loved |

## Reference device and technical constraints

The Galaxy S25+ is the only v1 target; we measure its real latency in week 1 rather than trusting published figures, since no S25-specific numbers were found.

**Latency budget**

- Google sets 20 ms round-trip as the bar for professional audio apps on Android, with 10 ms as the long-term goal ([Android Developers Blog](https://android-developers.googleblog.com/2021/03/an-update-on-androids-audio-latency.html)).
- Tap-to-tone is roughly round-trip plus 15 ms, because touch adds about 20 ms and we skip about 5 ms of audio input (same source).
- **Our target:** tap-to-tone of 35 ms or less on the S25+, stretch goal 25 ms. MIDI-in-to-sound of 20 ms or less.
- **Week-1 check:** measure round-trip with OboeTester on the S25+, wired headphones and the built-in speaker. Bluetooth audio is out of scope for live play.

**Architecture rules**

- Audio engine in C++ on Oboe (AAudio, low-latency exclusive mode where available); UI in Kotlin and Jetpack Compose.
- The audio thread never allocates memory, takes a lock, logs or touches Java.
- UI and audio talk only through lock-free single-producer, single-consumer queues.
- The sequencer clock lives on the audio thread and is sample-accurate; the UI only displays it.
- Every DSP block can render offline to a buffer, so tests can check its output without a device.

## Decisions and open questions

Five decisions made on 15 Oct; one question stays open.

| Question | Decision | What it means for v1 |
| --- | --- | --- |
| Orientation | Landscape | Full-width keyboard; 16 steps in one row |
| Release | Personal first, Play Store later | Skip store polish now; keep code, licences and assets Play-ready |
| Pricing | Decide later | No billing or licence checks in v1 |
| Beginner persona | Noodler only for v1 | No tutorial or onboarding; good presets still matter |
| Your role in the code | Test builds only | Claude writes and reviews all code; agent code review replaces human review, so the review step stays strict |

- [ ] Which MIDI gear will you test with? Until you have some, MIDI stays in v1.1 and is tested against a desktop DAW.

## Next steps

This doc is the top of the stack; each layer below is approved before the next starts.

1. **Approve this doc** and settle the MIDI gear question.
2. **Screen mockups** of the play screen, sequencer and tape, so you react to the look before any code.
3. **Feature spec** with acceptance criteria per MVP feature. This is the contract every agent tests against.
4. **Architecture decisions:** engine and plugin interface, sequencer data model, threading and the project file format.
5. **Week-1 spike in Claude Code:** an Oboe audio engine that plays one oscillator on the S25+, with a latency measurement. Go or no-go on the latency target.
6. **Build with Superpowers:** brainstorming on each feature, then writing-plans, then subagent-driven development with TDD on the sequencer and DSP. One agent per module (engine, sequencer, UI, storage), each working against the spec.
7. **Device check at every milestone:** you play it on the S25+ and file what feels wrong; tests can't judge feel.
