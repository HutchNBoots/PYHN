#pragma once

#include <cstdint>

#include "groove/DrumParams.h"
#include "groove/Instrument.h"
#include "groove/SynthEngine.h"  // NoteEvent, NoteOnRender

namespace groove {

// The built-in drum kit and its sampler (docs/feature-spec.md F2).
//
// PUBLIC API IS A CONTRACT shared by the drums and sequencer modules: don't change it
// without the lead. The private section is the drums module's to design.
//
// Keys: 16 sounds, mapped to MIDI notes kLowestNote .. kLowestNote + 15 (C3 upward, so
// the lowest 16 keys of the on-screen keyboard). Other notes are ignored.
// Motion targets: key * kDrumParamCount + DrumParamId.
//
// Threading:
//  - Control thread (exactly one): noteOn/noteOff/allNotesOff, popNoteOnRender,
//    setParam/param, soundName.
//  - Audio thread: the Instrument overrides.
//  - prepare(): only while rendering cannot happen. It never waits for the kit.
//
// The kit (F2 decision, F0.1) is synthesised on the machine's own background thread, at
// construction (48 kHz) and again whenever prepare() asks for another rate, into a new
// DrumKit that is published to the audio thread with a release store of an atomic pointer.
// Until it is there the slot renders silence and ignores hits; afterwards it is read-only.
// A published kit is only freed by prepare() or the destructor, when nothing renders. (F2.4:
// no decoding or disk I/O ever happens on the audio thread.)
class DrumMachine final : public Instrument {
public:
    static constexpr int kSoundCount = 16;
    static constexpr int kVoiceCount = 8;
    static constexpr int kLowestNote = 48;

    static constexpr int motionTarget(int key, DrumParamId id) {
        return key * static_cast<int>(kDrumParamCount) + static_cast<int>(id);
    }

    DrumMachine();
    ~DrumMachine() override;

    // --- control thread ---
    bool noteOn(int note, float velocity, int64_t tapTimeNs);
    bool noteOff(int note);
    bool allNotesOff();
    bool popNoteOnRender(NoteOnRender& out);

    // key 0..15; out-of-range keys are ignored (param returns 0).
    void setParam(int key, DrumParamId id, float value);
    float param(int key, DrumParamId id) const;
    // Original sound name, e.g. "Kick 1"; "" for an out-of-range key.
    static const char* soundName(int key);

    // --- Instrument (audio thread, except prepare) ---
    void prepare(float sampleRate) override;
    void seqNoteOn(int note, float velocity) override;
    void seqNoteOff(int note) override;
    void seqAllNotesOff() override;
    void setMotion(int target, float value) override;
    void clearAllMotion() override;
    void setTempo(float bpm) override;
    void renderStereo(float* left, float* right, int32_t numFrames) override;

    // Frames rendered since prepare(). Readable from any thread.
    int64_t framesRendered() const;

    // Any thread: the kit for the prepared rate is ready (hits play).
    bool kitReady() const;
    // Control thread / tests: waits until kitReady(), at most timeoutMs.
    bool waitForKit(int timeoutMs) const;

private:
    struct Impl;
    Impl* impl_;  // allocated in the constructor, never on the audio thread
};

}  // namespace groove
