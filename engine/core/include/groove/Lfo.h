#pragma once

#include <cstdint>

#include "groove/DspMath.h"

namespace groove {

// Low-frequency oscillator, run at the control rate (once per sub-block).
// Output is bipolar, -1..1. Real-time safe: no allocation, no libm in process().
class Lfo {
public:
    // Same order as the lfo.wave options in SynthParams.def.
    enum class Shape : int32_t { Sine = 0, Triangle = 1, Square = 2, SampleHold = 3 };

    // Tempo-sync divisions, in beats per LFO cycle (4 bars down to 1/32 triplet).
    static constexpr int kDivisionCount = 13;
    static const float kDivisionBeats[kDivisionCount];
    static const char* const kDivisionNames[kDivisionCount];

    // Index of the division whose frequency at `bpm` is closest (in octaves) to rateHz.
    // The UI can mirror this: at 120 BPM a quarter note is 2 Hz.
    static int nearestDivision(float rateHz, float bpm);
    static float divisionHz(int division, float bpm);

    // callsPerSecond: how often process() runs.
    void setUpdateRate(float callsPerSecond) { invRate_ = 1.0f / callsPerSecond; }
    void setFrequency(float hz) { increment_ = hz * invRate_; }
    void setShape(Shape shape) { shape_ = shape; }
    void seed(uint32_t seed) { rng_.reseed(seed); }
    // Restart the cycle (key retrigger). S&H takes a fresh value.
    void reset() {
        phase_ = 0.0f;
        held_ = rng_.nextBipolar();
    }

    float process();
    float phase() const { return phase_; }

private:
    float invRate_ = 1.0f / 3000.0f;
    float increment_ = 0.0f;
    float phase_ = 0.0f;
    float held_ = 0.0f;
    Shape shape_ = Shape::Sine;
    dsp::XorShift32 rng_{0x1234567u};
};

}  // namespace groove
