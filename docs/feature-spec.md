# MVP feature spec

Date: 15 Oct 2026 · Status: draft for owner approval

This is the contract every module is built and tested against (spec next step 3).
It refines `docs/vision-cx-spec.md` and never overrides it. Where the vision spec was silent,
the choice made here is marked **Decision**. Open questions are collected at the end.

Each acceptance criterion carries an ID, the module that owns it, and how it is checked:

- **[H]** host test (GoogleTest, offline render, no device)
- **[K]** Kotlin unit test (JUnit)
- **[D]** device check by the owner on the S25+, using a script in the milestone notes

Modules: **engine** (DSP, voices, FX, mixer), **seq** (sequencer and clock), **tape**,
**ui**, **storage**.

---

## F0. Platform rules (apply to every feature)

| ID | Criterion | Module | Check |
| --- | --- | --- | --- |
| F0.1 | Cold launch to first sound from a key: 2 s or less. No dialog, permission prompt or loading screen before it. | ui, storage | [D] |
| F0.2 | Median tap-to-tone stays at 25 ms or less on the S25+ with wired headphones, at every milestone. It was about 16 ms at the spike. A debug panel shows it. | engine, ui | [D] |
| F0.3 | Full load runs for 30 minutes with no audible glitch and an xrun count of 0. Full load is all 8 slots playing (synth slots at 4 voices) plus 4 tape tracks, delay and reverb. | engine | [D] |
| F0.4 | At full load the audio callback averages 35% or less of the burst period, with p99 at 70% or less. The debug panel shows both. | engine | [D] |
| F0.5 | The audio thread rules in `CLAUDE.md` hold. Every DSP block, the sequencer, tape and mixer render inside one shared test that fails on any allocation during render. | engine, seq, tape | [H] |
| F0.6 | The master output never clips. A soft limiter on the master bus keeps peaks at −0.3 dBFS or below for any patch and mixer setting. | engine | [H] |
| F0.7 | Every parameter change from the UI is smoothed on the audio side (10 ms or less), so no change makes a zipper noise or click. | engine | [H] |

**Decision:** the engine runs at the device's native rate (48 kHz on the S25+), in 32-bit float internally.

---

## F1. Subtractive synth engine ("Brook")

Polyphonic subtractive voice: oscillator, sub, noise, a 24 dB/oct resonant low-pass filter,
amp and filter envelopes, and one LFO.

**Parameters**, arranged as 4 sections × (4 main + 4 Shift) for the strip grammar:

| Section | Main layer (strips 1–4) | Shift layer (strips 1–4) |
| --- | --- | --- |
| Osc | Wave (saw → square → triangle → sine, continuous), Pulse width, Sub level (−1 oct square), Noise level | Octave (−2…+2), Fine tune (±50 c), Glide (0–2 s), Voice mode (poly / mono / unison) |
| Filter | Cutoff (20 Hz–18 kHz, exponential), Resonance (to self-oscillation), Env amount (±100%), Key tracking (0–100%) | Drive (0–24 dB), High-pass (20 Hz–2 kHz), Velocity → cutoff, LFO → cutoff |
| Env | Amp attack, Amp decay, Amp sustain, Amp release | Filter attack, Filter decay, Filter sustain, Filter release |
| Mod | LFO rate (0.05–30 Hz, or tempo-synced), LFO depth, LFO wave (sine, triangle, square, S&H), LFO destination (pitch, cutoff, PW, amp) | LFO sync (free / tempo), LFO delay (fade-in, 0–5 s), LFO key-retrigger, *(reserved: tilt, v1.1)* |

**Decision:** a section rail beside the display picks the section, one tap each. That keeps
every parameter within 1 tap of the play screen (spec loop 3).

| ID | Criterion | Module | Check |
| --- | --- | --- | --- |
| F1.1 | 4-voice polyphony. Voice stealing takes the oldest released voice first, then the oldest held one. A stolen voice fades out within 2 ms, with no click. | engine | [H] |
| F1.2 | Mono mode uses last-note priority and legato (no retrigger while a note is held). Glide applies in mono and unison. Unison stacks 4 voices, detuned by ±10 c. | engine | [H] |
| F1.3 | Pitch is correct to ±1 cent from C0 to C8 at every octave and fine setting. | engine | [H] |
| F1.4 | Band-limited oscillators: for fundamentals up to 4 kHz, aliased partials below 20 kHz stay under −40 dB relative to the fundamental. | engine | [H] |
| F1.5 | Filter: with cutoff at 1 kHz and no resonance, a 4 kHz sine is reduced by at least 40 dB. At maximum resonance, the filter self-oscillates within ±3% of the cutoff frequency. | engine | [H] |
| F1.6 | Envelope times run from 1 ms to 10 s on an exponential curve. A note-on at any envelope stage starts from the current level, with no click. | engine | [H] |
| F1.7 | Every parameter in the table is on the strips. It responds within one audio buffer of the strip moving and is smoothed per F0.7. | engine, ui | [H] [D] |
| F1.8 | 16 original factory presets ship with the app, covering bass, lead, pad, pluck and fx. Slots 2–8 start on presets the owner judges sound good within 30 seconds of playing. | engine | [D] |

---

## F2. Drum sampler with one built-in kit

| ID | Criterion | Module | Check |
| --- | --- | --- | --- |
| F2.1 | The kit has 16 sounds, mapped one per key from the lowest C upward: kick ×2, snare ×2, clap, rim, closed hat ×2, open hat, crash, ride, toms ×3, perc ×2. | engine | [H] [D] |
| F2.2 | Main layer per key: Pitch (±12 st), Decay, Tone (low-pass), Level. Shift layer: Pan, Start offset, Reverse, Choke group (none, A or B). The last key played is the one being edited. | engine, ui | [H] |
| F2.3 | 8 drum voices. Retriggering a key restarts it. Within a choke group, a new hit fades the previous one out in 5 ms (closed hat chokes open hat by default). | engine | [H] |
| F2.4 | Samples play from memory. Nothing is decoded or read from disk on the audio thread. | engine | [H] |

**Decision:** the kit is synthesised by our own code (offline renders of original drum
models), not recorded samples, so it is licence-clean with no third-party assets. It
renders on a background thread at launch. The synth makes sound before the kit is ready,
and the kit is ready within 2 s.

---

## F3. Sequencer (64 steps, 8 slots × 16 patterns)

**Decision:** a *slot* is one instrument instance, a synth or the drum kit. Slot 1 is
drums; slots 2–8 are synths on different presets. Each slot has 16 patterns of up to
64 steps (4 pages of 16), and all slots play in sync.

Step data: on/off, up to 4 notes (chords on synth slots), velocity, gate length,
probability, and motion values.

| ID | Criterion | Module | Check |
| --- | --- | --- | --- |
| F3.1 | Clock: the tempo runs from 40.0 to 240.0 BPM in 0.1 steps. Every step event lands on its exact computed sample frame: zero drift over 10 minutes of offline render at any tempo. | seq | [H] |
| F3.2 | Step rates are 1/8, 1/16 (the default), 1/32 and 1/16 triplet. Pattern length is 1–64 steps. | seq | [H] |
| F3.3 | Shuffle (0–100%) delays every second step by up to 2/3 of a step, exact to the sample. | seq | [H] |
| F3.4 | Probability (0–100% per step) uses a seeded random number generator, so offline renders repeat exactly. Over 10,000 plays, the observed rate is within ±2% of the setting. | seq | [H] |
| F3.5 | Step entry: tapping a pad toggles the step. Shift + pad selects a step, and its note, velocity, probability and motion move onto the strips. Playing a key while a step is selected sets that step's note. | seq, ui | [K] [D] |
| F3.6 | Live record: while recording, notes played on the keys are written to the nearest step, quantised by default. This stays sample-accurate against the clock. | seq | [H] |
| F3.7 | Motion recording: holding Record and moving a strip records that parameter's value per step, with up to 8 lanes per pattern. On playback, the value applies at the step's start, smoothed per F0.7. | seq, engine | [H] |
| F3.8 | Step loop: holding two step pads loops that range of steps. Releasing them returns to where the pattern would have been, still in time. | seq, ui | [H] [D] |
| F3.9 | A pattern switch made during play takes effect at the end of the current pattern, never mid-pattern. | seq | [H] |
| F3.10 | The UI only reads the playhead and never drives timing. The playhead on screen is within one display frame of the audio. | seq, ui | [D] |

Main strips on the Sequencer screen: Shuffle, Loop length, Gate, Rate. With Shift and a
selected step: Note, Velocity, Probability, Motion.

---

## F4. Four colour strips and the Shift layer

| ID | Criterion | Module | Check |
| --- | --- | --- | --- |
| F4.1 | A vertical drag changes the value, and the full range takes 1.5 strip heights of travel. A second finger held on the same strip gives a fine mode (¼ speed). | ui | [K] [D] |
| F4.2 | Double-tap resets a strip to its parameter's default. Long-press (500 ms) locks it: a locked strip ignores touches until long-pressed again, and shows a lock mark. | ui | [K] [D] |
| F4.3 | All four strips and the keys work under simultaneous multi-touch, with no dropped touches using five fingers. | ui | [D] |
| F4.4 | Shift: a tap latches it on or off, and holding it is momentary. The strip labels, values and display corner label change in the same frame. | ui | [K] [D] |
| F4.5 | The strip colour and position mean the same slot on every screen (ADR 0002). | ui | [D] |

---

## F5. 4-track tape (loop, overdub, undo)

**Decision:** tape records the master mix of all slots *including FX*, onto the selected
track. Tape playback goes to the mixer dry (FX are not applied twice). Tape audio is
16-bit stereo at 48 kHz, up to 64 bars or 2 minutes per track, whichever comes first.
All tape memory is allocated before playback starts.

| ID | Criterion | Module | Check |
| --- | --- | --- | --- |
| F5.1 | When the sequencer is running, record start and stop snap to bar lines. Record onto an empty track sets the length; later takes keep it. | tape, seq | [H] |
| F5.2 | Overdub mixes new input into the existing audio. Strip 4 sets the Dub level (0–100%), which is how much of the old audio is kept. | tape | [H] |
| F5.3 | The loop region (start and length in bars) is on strips 2–3. Playback loops it with a crossfade of 20 ms or less, with no click. | tape | [H] |
| F5.4 | Undo and redo cover the last 8 tape actions (record, overdub, clear) within a 256 MB tape-plus-undo budget. Older actions drop off when the budget is full. Undo takes effect within 100 ms. | tape | [H] |
| F5.5 | Speed: 0.5×, 1× or 2× with matching pitch change (tape-style), plus reverse. | tape | [H] |
| F5.6 | Commit loop: one action records the current pattern loop of one slot, or of all slots, onto the selected track for exactly the pattern's length. | tape, seq | [H] [D] |

---

## F6. Master FX and mixer

| ID | Criterion | Module | Check |
| --- | --- | --- | --- |
| F6.1 | Delay: time is tempo-synced (1/16 to 1/2, dotted and triplet values included) or free (10 ms–2 s). Feedback 0–95% never runs away. Tone has low-pass and high-pass in the feedback path. Mix. | engine | [H] |
| F6.2 | Reverb: an original algorithm (feedback delay network). Size, Decay (0.2–20 s), Damping, Mix. An impulse decays smoothly, and offline renders repeat exactly. | engine | [H] |
| F6.3 | Each slot has a delay send and a reverb send. The FX returns go to the master bus. | engine | [H] |
| F6.4 | The mixer has 8 slot channels and 4 tape channels. Each has volume (−∞ to +6 dB), pan (equal power), mute, and for slots, both sends. A mute fades in 5 ms, with no click. | engine | [H] |
| F6.5 | The Mixer screen's bottom zone is 12 channel pads (8 slots, 4 tape tracks). A tap selects a channel for the strips (Volume, Pan, Delay send, Reverb send); Shift + tap mutes it. | ui | [K] [D] |

---

## F7. WAV export and autosave

| ID | Criterion | Module | Check |
| --- | --- | --- | --- |
| F7.1 | Export takes 2 taps from the Tape screen: Export WAV opens the Android share sheet, and the user picks an app. | ui, storage | [D] |
| F7.2 | Export renders the tape mix (all 4 tracks through the mixer, loop region or whole tape) offline, faster than real time, on a background thread. It's a 24-bit, 48 kHz stereo WAV. Playback continues during export. | tape, storage | [H] |
| F7.3 | An exported file matches the tape mix exactly: an offline render compared with a captured live render (with the same seed) is bit-identical. | tape | [H] |
| F7.4 | Autosave: session state (patterns, sounds, mixer, strips) is written at most 5 s after any change, and immediately on app pause. A crash or phone call loses at most the last 5 s of work. | storage | [H] [D] |
| F7.5 | Tape audio streams to disk from a lock-free ring buffer on a background writer thread. The audio thread never touches files. | storage, tape | [H] |
| F7.6 | Relaunch restores the last session. The small state file loads before the first sound (F0.1) and tape audio loads in the background. | storage | [D] |
| F7.7 | The save format has a version number, and loading an older version upgrades it. A corrupt file falls back to the last good save, never to a crash or dialog. | storage | [H] |

---

## Out of MVP (from the vision spec, unchanged)

Tilt, USB-MIDI and clock sync, oscillator draw, the playful sequencers (endless, random,
arp), a second synth engine, sampling, and project sharing.

## Open questions for the owner

1. **Presets:** how should you pick a preset? Proposal: long-press a slot to show a thin preset strip over the keys, with no dialog.
2. **Sessions:** is one autosaved session plus WAV exports enough for v1, or do you want a sketchbook of several saved sessions? The Noodler persona hints at the second; it adds a small browser screen.
3. **MIDI gear:** still open from the vision spec. It only matters for v1.1.

## Build order (milestones, each ending in an installable build)

1. **M1 Synth:** F1 plus the strip grammar (F4) on the Synth screen, with mixer and limiter basics (F0.6).
2. **M2 Sequencer:** F3 with slots and patterns, F2 drums.
3. **M3 Tape:** F5, plus Mixer and FX (F6).
4. **M4 Keep it:** F7 export and autosave, then a 30-minute soak test (F0.3, F0.4).
