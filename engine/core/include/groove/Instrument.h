#pragma once

#include <cstdint>

namespace groove {

// One sound slot's instrument, as the audio graph and the sequencer see it
// (docs/plans/2026-10-16-m2-sequencer-drums.md). SynthEngine and DrumMachine implement it.
//
// Every method here runs on the audio thread, except prepare(), which runs only while
// rendering cannot happen (stream stopped or closed). None of them may allocate, lock,
// log or do I/O. Each instrument keeps its own control-thread API (UI notes, parameters)
// on the concrete class, fed through its own SPSC queue and lock-free atomics.
class Instrument {
public:
    virtual ~Instrument() = default;

    virtual void prepare(float sampleRate) = 0;

    // Sequencer notes, applied at the start of the next renderStereo() call. The graph
    // splits its render at sequencer event frames, so they land sample-accurately.
    // note is MIDI 0..127; velocity is 0..1. Notes the instrument can't play are ignored.
    virtual void seqNoteOn(int note, float velocity) = 0;
    virtual void seqNoteOff(int note) = 0;
    virtual void seqAllNotesOff() = 0;

    // Motion playback: overrides one parameter's value (real units) until it is cleared.
    // The target numbering is the instrument's own (synth: ParamId; drums: see
    // DrumMachine::motionTarget). A NaN value clears that override. Overrides are smoothed
    // like any other parameter change (F0.7).
    virtual void setMotion(int target, float value) = 0;
    virtual void clearAllMotion() = 0;

    // Tempo for tempo-synced modulation (LFO sync).
    virtual void setTempo(float bpm) = 0;

    // Drains the instrument's UI note queue and parameter snapshot, then WRITES (not adds)
    // numFrames samples to left and right. Must be called every frame of the stream, even
    // when silent, so every instrument's frame counter matches the stream's.
    virtual void renderStereo(float* left, float* right, int32_t numFrames) = 0;
};

}  // namespace groove
