# M3: Tape, mixer and master FX

Spec: `docs/feature-spec.md` F5 and F6 (F0 still applies). Layout: `docs/adr/0002-screen-layout.md`,
`docs/mockups/Tape.dc.html`. The Mixer screen isn't mocked up; F6.5 defines it.

Milestone build: a 4-track looping tape that records the mix (with FX), overdubs, undoes and
redoes, and commits a pattern loop in one tap; a mixer for 8 slots and 4 tape tracks; tempo-synced
delay and reverb on sends.

Not in M3: WAV export and autosave (M4).

## Signal flow

```
slot 0..7 -> ChannelStrip (vol, pan, mute, sends) --+--> slot mix --+--> [tape input: full mix]
                       |  delay send  -> StereoDelay -> x return --+
                       +  reverb send -> Reverb      -> x return --+
tape track 1..4 playback -> ChannelStrip (vol, pan, mute) ----------+--> master volume -> MasterBus limiter -> out
```

- The slots keep the fixed headroom gain from M2 (0.5) before their channel strip.
- The tape records the slot mix plus the FX returns (post-FX, pre-master). Tape playback
  goes to the master dry (F5 decision). A commit of one slot records that slot's
  post-fader channel output only, with no FX.

## Tape behaviour (integration module; all host-tested)

- It follows the sequencer transport: on Play the head starts at the loop start; on Stop it
  returns there.
- **First take on an empty tape:** recording starts at a bar line (an armed record starts at
  Play or at the next bar line). The take's length, in whole bars, becomes the tape length,
  up to 64 bars or 2 minutes, where it stops itself. The tape keeps the frames-per-bar of
  the tempo it was recorded at; changing the tempo afterwards doesn't stretch it (a known
  v1 limit).
- **Later takes:** start and stop on bar lines and write at the head, looping over the loop
  region for as long as recording lasts. Each sample becomes `old × Dub + input` (Dub 1 =
  pure overdub, 0 = replace). Playback of the track being recorded plays the old audio.
- **Loop region:** `tape.loopStart` and `tape.loopLen` in bars; length 0 means the whole tape.
  Playback wraps with a crossfade of 20 ms or less, with no click (F5.3).
- **Speed** 0.5×/1×/2× and reverse change playback pitch and speed, tape-style (F5.5).
  Recording is only allowed at 1× forward, so an arm is ignored otherwise.
- **Undo and redo:** the last 8 actions (a record pass, a commit, a clear track). Each one
  stores a copy of the region it changed in a preallocated pool, and the tape plus the undo
  pool stays within 256 MB (F5.4). When the pool is full, the oldest entry is dropped.
- When every track is cleared, the tape is empty again (its length is 0).
- **Storage:** 16-bit stereo at the stream rate, allocated in `prepare()` for 2 minutes × 4
  tracks, never on the audio thread. Peak overviews (256 bins per track, atomics) are updated
  by the audio thread as it writes.

## Modules and owners

1. **FX** (`FxBlocks.cpp`, plus any `Fx*` files): `StereoDelay`, `Reverb` and `ChannelStrip`,
   implementing `FxBlocks.h` exactly. Test-first for F6.1, F6.2 and F6.4.
2. **Tape + integration** (the rest of engine/): the tape, the new graph signal flow, the
   mix/channel parameter stores (lock-free atomics) and the tape command queue, the transport
   link, and AudioEngine and JNI for the new `NativeEngine.kt` calls. Test-first for F5 and F6.3.
3. **UI** (app/): the Tape screen per the mockup and the Mixer screen per F6.5, both on the
   four-strip grammar.

## Review gate

As before: a strict review against the spec and the audio thread rules, a commit that says
how the rules were checked, and CI building the APK.
