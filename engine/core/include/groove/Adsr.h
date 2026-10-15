#pragma once

namespace groove {

// Coefficients for an exponential ADSR, shared by every voice that uses the same times.
// Computed off the per-sample path (a few exp() calls), only when a time changes.
struct AdsrShape {
    // rate: how many times per second Adsr::process() is called (the sample rate for an
    // audio-rate envelope, or the control rate). Times in seconds, clamped to 1 ms..10 s;
    // sustain clamped to 0..1.
    static AdsrShape compute(float attack, float decay, float sustain, float release, float rate);

    double attackCoef = 0.0;
    double decayCoef = 0.0;
    double releaseCoef = 0.0;
    double sustainFollow = 1.0;  // one-pole step toward a changing sustain level
    double sustain = 1.0;
};

// Exponential ADSR envelope (F1.6).
//  - Attack rises toward an overshoot target (an analog-style convex curve) and reaches
//    1.0 exactly at the attack time.
//  - Decay and release fall exponentially toward a target just past their end point,
//    so they reach sustain / zero exactly at the set time (about -60 dB at the end of an
//    exponential that would otherwise never arrive).
//  - gateOn() at any stage restarts the attack from the current level, so retriggering
//    never jumps (no click). The sustain stage glides to a new sustain value.
// Double precision state keeps 10 s segments accurate at 48 kHz.
// Real-time safe: no allocation, no libm in process().
class Adsr {
public:
    enum class Stage { Idle, Attack, Decay, Sustain, Release };

    void gateOn() { stage_ = Stage::Attack; }
    void gateOff() {
        if (stage_ != Stage::Idle) stage_ = Stage::Release;
    }
    void reset() {
        stage_ = Stage::Idle;
        level_ = 0.0;
    }

    float process(const AdsrShape& shape);

    float level() const { return static_cast<float>(level_); }
    Stage stage() const { return stage_; }
    bool isIdle() const { return stage_ == Stage::Idle; }

    static constexpr double kAttackOvershoot = 0.3;
    static constexpr double kDecayUndershoot = 0.001;

private:
    double level_ = 0.0;
    Stage stage_ = Stage::Idle;
};

}  // namespace groove
