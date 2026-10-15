#pragma once

#include <cstdint>

#include "groove/Adsr.h"
#include "groove/DspMath.h"
#include "groove/Filters.h"
#include "groove/Lfo.h"
#include "groove/Oscillator.h"

namespace groove {

// Same order as the lfo.dest options in SynthParams.def.
enum class LfoDest : int32_t { Pitch = 0, Cutoff = 1, PulseWidth = 2, Amp = 3 };

// Values an engine computes once per control sub-block (from its smoothed parameters)
// and hands to every voice. Plain data, no pointers.
struct VoiceControl {
    Oscillator::Weights waveWeights{1.0f, 0.0f, 0.0f, 0.0f};
    float pulseWidth = 0.5f;
    float subLevel = 0.0f;
    float noiseLevel = 0.0f;
    float pitchOffsetSemis = 0.0f;  // octave * 12 + fine / 100
    float glideSeconds = 0.0f;      // 0 in poly mode

    float cutoffOctaves = 11.2f;  // log2(Hz)
    float resonance = 0.0f;       // 0..1
    float envOctaves = 0.0f;      // filter env amount, in octaves at full envelope
    float keyTrack = 0.0f;        // 1 = cutoff follows the keyboard 1:1
    float drive = 1.0f;           // linear gain into the ladder's saturator
    float velocityOctaves = 0.0f; // cutoff drop at velocity 0 (none at velocity 1)
    float lfoCutoffOctaves = 0.0f;
    float hpfCoef = 0.0f;         // onePoleCoefficient() of the high-pass

    float lfoHz = 2.0f;
    Lfo::Shape lfoShape = Lfo::Shape::Sine;
    LfoDest lfoDest = LfoDest::Cutoff;
    float lfoDepth = 0.0f;
    float lfoFadeStep = 1.0f;  // per sub-block, from the LFO delay
    bool lfoRetrigger = true;
    float freeLfo = 0.0f;  // the engine's free-running LFO, used when retrigger is off

    AdsrShape ampShape;     // at the sample rate
    AdsrShape filterShape;  // at the control rate
};

// One synth voice: oscillator + sub + noise -> high-pass -> ladder low-pass -> amp.
//
// Audio thread only (owned by SynthEngine). render() handles one control sub-block:
// modulation, pitch and filter coefficients are updated at its start, and every gain
// that could click (wave mix, sub, noise, amp, steal fade) ramps per sample across it.
// Real-time safe: no allocation, locks or I/O.
class Voice {
public:
    static constexpr int kSubBlock = 16;
    static constexpr float kStealFadeSeconds = 0.001f;  // F1.1: within 2 ms
    static constexpr float kOutputGain = 0.5f;

    void prepare(float sampleRate, uint32_t seed);
    void setDetuneCents(float cents) { detuneSemis_ = cents * 0.01f; }
    void setStartPhase(float phase) { startPhase_ = phase; }

    // Starts or moves to a note. retrigger: restart both envelopes from their current
    // level (and the LFO if key-retrigger is on); otherwise legato, envelopes continue.
    // Glides from the current pitch when the voice is sounding and glideSeconds > 0.
    void trigger(int note, float velocity, bool retrigger, const VoiceControl& c);
    void release();
    // Fades the current sound out within kStealFadeSeconds, then starts the note.
    // Calling it again while fading just replaces the pending note.
    void steal(int note, float velocity);
    // Fades out within kStealFadeSeconds and goes idle.
    void fadeOut();
    void cancelPending() { hasPending_ = false; }
    void reset();

    // Writes n (<= kSubBlock) samples. Call only when !isIdle().
    void render(float* out, int n, const VoiceControl& c);

    bool isIdle() const { return idle_; }
    bool isGateOn() const { return !idle_ && !fading_ && gate_; }
    bool isFading() const { return fading_; }
    bool hasPending() const { return hasPending_; }
    int pendingNote() const { return pendingNote_; }
    // True once the current note has produced at least one sample.
    bool hasSounded() const { return sounded_; }
    int note() const { return note_; }
    float frequency() const { return osc_.frequency(); }
    float ampLevel() const { return amp_.level(); }

    // Bookkeeping for voice allocation (written by the engine).
    uint64_t startStamp = 0;
    uint64_t releaseStamp = 0;

private:
    void start(int note, float velocity, const VoiceControl& c);
    void controlUpdate(const VoiceControl& c, int n);

    float sampleRate_ = 48000.0f;
    float controlRate_ = 3000.0f;
    float fadeStep_ = 1.0f / 48.0f;

    Oscillator osc_;
    OnePoleHighPass hpf_;
    LadderFilter ladder_;
    Adsr amp_;
    Adsr filterEnv_;
    Lfo lfo_;
    dsp::XorShift32 noise_;

    bool idle_ = true;
    bool gate_ = false;
    bool sounded_ = false;
    int note_ = 0;
    float velocity_ = 1.0f;
    float pitch_ = 60.0f;  // current (gliding) note
    float targetPitch_ = 60.0f;
    float glideStep_ = 0.0f;  // semitones per sub-block
    float detuneSemis_ = 0.0f;
    float startPhase_ = 0.0f;
    float lfoFade_ = 1.0f;
    bool snapRamps_ = true;  // first sub-block of a note: no ramp from stale values

    // Steal / fade-out state.
    bool fading_ = false;
    float fadeGain_ = 1.0f;
    bool hasPending_ = false;
    int pendingNote_ = 0;
    float pendingVelocity_ = 1.0f;

    // Per-sample ramps: value at the end of the last sub-block.
    Oscillator::Weights weights_{1.0f, 0.0f, 0.0f, 0.0f};
    float sub_ = 0.0f;
    float noiseLevel_ = 0.0f;
    float gain_ = 0.0f;
    // Targets for the current sub-block.
    Oscillator::Weights weightsTarget_{1.0f, 0.0f, 0.0f, 0.0f};
    float subTarget_ = 0.0f;
    float noiseTarget_ = 0.0f;
    float gainTarget_ = 0.0f;
};

}  // namespace groove
