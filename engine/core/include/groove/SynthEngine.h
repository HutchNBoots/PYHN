#pragma once

#include <array>
#include <atomic>
#include <cstdint>

#include "groove/Instrument.h"
#include "groove/Lfo.h"
#include "groove/MonoNoteStack.h"
#include "groove/SpscQueue.h"
#include "groove/SynthParams.h"
#include "groove/Voice.h"

namespace groove {

struct NoteEvent {
    enum class Type : int32_t { NoteOn, NoteOff, AllNotesOff };
    Type type = Type::NoteOn;
    int32_t note = 0;
    float velocity = 1.0f;  // 0..1, note-on only
    // CLOCK_MONOTONIC time of the touch that caused this event, or 0 if not measured.
    int64_t tapTimeNs = 0;
};

// Emitted by the audio thread for every measured note-on: the stream frame where the
// note's first sample lands, so the control thread can map it to a presentation time.
struct NoteOnRender {
    int64_t tapTimeNs = 0;
    int64_t frameIndex = 0;
};

// "Brook": the 4-voice subtractive synth (docs/feature-spec.md F1), one Instrument slot.
//
// Threading:
//  - Control thread (exactly one producer): noteOn/noteOff/allNotesOff (the UI note queue;
//    in the app the audio graph forwards the keys' notes into it), popNoteOnRender,
//    setParam/loadPreset (through the lock-free ParamStore).
//  - Audio thread: renderStereo() and the Instrument calls (seqNoteOn/Off, setMotion,
//    setTempo...). renderStereo() snapshots every parameter (plus the motion overrides)
//    and applies the queued UI and sequencer notes at the start of each call, then smooths
//    parameters per 16-sample control sub-block (F0.7). Sub-blocks follow the stream's
//    frame count, not the call boundaries, so splitting a render at sequencer events
//    doesn't change modulation timing. It never allocates, locks, logs or does I/O.
//  - prepare() and the test accessors only while renderStereo() cannot run.
//
// Note sources (keys vs sequencer): each note remembers which sources hold it. A note-on
// from either source (re)triggers the note; a note-off from one source releases it only
// when the other source isn't holding it too. So a sequencer note-off never cuts a key the
// player is holding, and releasing a key never cuts a sequencer note that is still within
// its gate. seqAllNotesOff (Stop) releases only what the sequencer holds; the UI's
// allNotesOff is a panic and releases everything.
//
// Voice mode changes (poly / mono / unison) fade every voice out (<= 2 ms, click-free) and
// start the notes still held, by either source, again under the new mode: poly the four
// most recent, mono and unison the last-held one (with the rest kept for last-note
// priority). The stereo placement ramps to the new mode's over the same fade.
class SynthEngine final : public Instrument {
public:
    static constexpr int kVoiceCount = 4;
    static constexpr std::size_t kEventCapacity = 256;
    static constexpr std::size_t kRenderRecordCapacity = 64;
    static constexpr int kSubBlock = Voice::kSubBlock;
    static constexpr float kSmoothingSeconds = 0.0018f;  // 1% residual after ~8.3 ms
    static constexpr float kUnisonSpreadCents = 10.0f;
    static constexpr float kDefaultTempoBpm = 120.0f;
    static constexpr int kSeqEventCapacity = 64;

    // Same order as the osc.voiceMode options in SynthParams.def.
    enum class VoiceMode : int32_t { Poly = 0, Mono = 1, Unison = 2 };

    static_assert(std::atomic<int64_t>::is_always_lock_free, "frame counter must be lock-free");

    SynthEngine();

    // Not real-time safe with respect to render; call with the stream stopped.
    // Restarts the frame counter at 0, matching a freshly opened stream, and clears the
    // motion overrides and held notes. Keeps the parameters and the tempo.
    void prepare(float sampleRate) override;

    // --- control thread ---
    // note 0..127 (others are rejected); velocity is clamped to 0..1.
    bool noteOn(int note, float velocity, int64_t tapTimeNs);
    bool noteOff(int note);
    bool allNotesOff();
    bool popNoteOnRender(NoteOnRender& out) { return renderRecords_.pop(out); }

    void setParam(ParamId id, float value) { params_.set(id, value); }
    float param(ParamId id) const { return params_.get(id); }
    ParamStore& params() { return params_; }
    // Returns false for an index out of range.
    bool loadPreset(int index);

    // --- audio thread (Instrument) ---
    void seqNoteOn(int note, float velocity) override;
    void seqNoteOff(int note) override;
    void seqAllNotesOff() override;
    // target is a ParamId; the value is clamped to its range; NaN clears the override.
    void setMotion(int target, float value) override;
    void clearAllMotion() override;
    // Drives tempo-synced LFO rates (lfo.sync = tempo). Clamped to 1..1000 BPM.
    void setTempo(float bpm) override;
    // Writes numFrames samples to each channel (any length; no allocation).
    void renderStereo(float* left, float* right, int32_t numFrames) override;

    // Frames rendered since prepare(). Readable from any thread.
    int64_t framesRendered() const { return framesRendered_.load(std::memory_order_relaxed); }

    // --- tests only (call while render cannot run) ---
    struct VoiceInfo {
        bool active = false;
        bool gateOn = false;
        int note = 0;
        float frequencyHz = 0.0f;
        float ampLevel = 0.0f;
    };
    VoiceInfo voiceInfo(int voice) const;
    // Smoothed value the DSP currently uses, in real units.
    float smoothedParam(ParamId id) const;
    VoiceMode voiceMode() const { return mode_; }
    float tempo() const { return tempoBpm_; }

private:
    enum NoteSource : uint8_t { kKeys = 1, kSequencer = 2 };

    void handle(const NoteEvent& e, int64_t blockStartFrame, uint8_t source);
    void releaseNote(int note);
    void pushSeqEvent(const NoteEvent& e);
    void notePoly(int note, float velocity, int64_t tapTimeNs, int64_t blockStartFrame);
    void noteOffPoly(int note);
    void setMode(VoiceMode mode);
    void restartHeld();
    void readTargets();
    void advanceControls();
    float smoothed(ParamId id) const { return smoothed_[index(id)]; }

    SpscQueue<NoteEvent, kEventCapacity> events_;
    SpscQueue<NoteOnRender, kRenderRecordCapacity> renderRecords_;
    ParamStore params_;
    std::atomic<int64_t> framesRendered_{0};

    // --- audio-thread state ---
    float sampleRate_ = 48000.0f;
    float controlRate_ = 3000.0f;
    float smoothingCoef_ = 0.0f;
    ParamValues raw_{};       // last snapshot, real units
    ParamValues target_{};    // smoothing domain (log2 for Exp curves)
    ParamValues smoothed_{};  // smoothing domain
    bool snapSmoothing_ = true;

    std::array<Voice, kVoiceCount> voices_;
    std::array<float, kVoiceCount> panLeft_{};
    std::array<float, kVoiceCount> panRight_{};
    std::array<float, kVoiceCount> panLeftTarget_{};
    std::array<float, kVoiceCount> panRightTarget_{};
    std::array<float, kVoiceCount> panStepLeft_{};
    std::array<float, kVoiceCount> panStepRight_{};
    int panRampLeft_ = 0;  // samples until the pans reach their targets
    std::array<float, kSubBlock> scratch_{};
    MonoNoteStack held_;  // every held note (any source, any mode), oldest first
    std::array<float, 128> heldVelocities_{};  // the velocity each held note was played with
    VoiceMode mode_ = VoiceMode::Poly;
    uint64_t stamp_ = 0;

    VoiceControl control_;
    Lfo freeLfo_;
    // Caches so per-sub-block work only recomputes what changed.
    std::array<float, 4> ampTimes_{-1.0f, -1.0f, -1.0f, -1.0f};
    std::array<float, 4> filterTimes_{-1.0f, -1.0f, -1.0f, -1.0f};
    float hpfCached_ = -1.0f;
    float driveCached_ = -1.0f;
    float lfoRateCached_ = -1.0f;
    float lfoDelayCached_ = -1.0f;
    float lfoSyncedHz_ = 2.0f;
    float lfoTempoCached_ = -1.0f;
    float tempoBpm_ = kDefaultTempoBpm;
    int subBlockPos_ = 0;  // stream position within the current control sub-block

    std::array<uint8_t, 128> noteSources_{};  // NoteSource bits per MIDI note
    std::array<NoteEvent, kSeqEventCapacity> seqEvents_{};
    int seqEventCount_ = 0;
    ParamValues motion_{};  // override per parameter, NaN = none
};

}  // namespace groove
