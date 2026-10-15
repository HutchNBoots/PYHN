#pragma once

#include <array>
#include <cstdint>

namespace groove {

// Band-limited morphing oscillator plus a sub oscillator one octave down.
//
// Waves, all peak +-1 and in phase with each other (fundamental ~ +sin), so crossfades
// between neighbours never cancel:
//   saw (falling ramp), square with pulse width (DC removed), triangle, sine.
// Saw, square and sub use a cubic B-spline PolyBLEP, the triangle a matching PolyBLAMP
// (see DspMath.h). The phase is a 32-bit fixed-point accumulator, so pitch is exact to
// well under 0.01 cent at any frequency (F1.3).
//
// Real-time safe: no allocation, no libm in process().
class Oscillator {
public:
    enum Wave : int { kSaw = 0, kSquare = 1, kTri = 2, kSine = 3, kWaveCount = 4 };
    using Weights = std::array<float, kWaveCount>;

    // Crossfade weights for the continuous Wave parameter: 0 = saw, 1 = square,
    // 2 = triangle, 3 = sine; values between blend the two neighbours.
    static Weights morphWeights(float wave);

    void setSampleRate(float sampleRate);
    // Clamped to [0, 0.45 * sampleRate].
    void setFrequency(float hz);
    float frequency() const { return frequency_; }
    // Clamped to [0.05, 0.95].
    void setPulseWidth(float pw);
    // Sets the weights used by the no-argument process().
    void setWave(float wave) { weights_ = morphWeights(wave); }
    // phase in [0, 1). Also resets the sub oscillator to its first half-cycle.
    void resetPhase(float phase = 0.0f);

    // Next sample of the weighted wave mix. Waves with zero weight cost nothing.
    // Also computes sub() for the same sample.
    float process(const Weights& w);
    float process() { return process(weights_); }

    // Sub oscillator (square, one octave below) for the sample last returned by process().
    float sub() const { return sub_; }

private:
    float sampleRate_ = 48000.0f;
    float frequency_ = 440.0f;
    uint32_t phase_ = 0;
    uint32_t increment_ = 0;
    float dt_ = 0.0f;  // increment in cycles per sample
    float pulseWidth_ = 0.5f;
    bool subSecondHalf_ = false;
    float sub_ = 0.0f;
    Weights weights_{1.0f, 0.0f, 0.0f, 0.0f};
};

}  // namespace groove
