#include "groove/Oscillator.h"

#include <algorithm>
#include <cmath>

#include "groove/DspMath.h"

namespace groove {

namespace {
constexpr double kPhaseScale = 4294967296.0;  // 2^32
constexpr float kInvPhaseScale = 1.0f / 4294967296.0f;
}  // namespace

Oscillator::Weights Oscillator::morphWeights(float wave) {
    Weights w{0.0f, 0.0f, 0.0f, 0.0f};
    wave = std::clamp(wave, 0.0f, 3.0f);
    int lower = static_cast<int>(wave);
    if (lower > 2) lower = 2;
    const float frac = wave - static_cast<float>(lower);
    w[static_cast<std::size_t>(lower)] = 1.0f - frac;
    w[static_cast<std::size_t>(lower + 1)] = frac;
    return w;
}

void Oscillator::setSampleRate(float sampleRate) {
    sampleRate_ = sampleRate;
    setFrequency(frequency_);
}

void Oscillator::setFrequency(float hz) {
    frequency_ = std::clamp(hz, 0.0f, 0.45f * sampleRate_);
    const double cycles = static_cast<double>(frequency_) / static_cast<double>(sampleRate_);
    increment_ = static_cast<uint32_t>(cycles * kPhaseScale + 0.5);
    dt_ = static_cast<float>(increment_) * kInvPhaseScale;
}

void Oscillator::setPulseWidth(float pw) { pulseWidth_ = std::clamp(pw, 0.05f, 0.95f); }

void Oscillator::resetPhase(float phase) {
    phase = std::clamp(phase, 0.0f, 0.999999f);
    phase_ = static_cast<uint32_t>(static_cast<double>(phase) * kPhaseScale);
    subSecondHalf_ = false;
}

float Oscillator::process(const Weights& w) {
    using dsp::blampResidual;
    using dsp::blepResidual;
    using dsp::edgeCorrection;

    const float t = static_cast<float>(phase_) * kInvPhaseScale;
    const float dt = dt_;
    float out = 0.0f;

    if (w[kSaw] != 0.0f) {
        // Falling ramp, +2 jump at the wrap.
        const float saw = 1.0f - 2.0f * t + 2.0f * edgeCorrection<blepResidual>(t, 0.0f, dt);
        out += w[kSaw] * saw;
    }
    if (w[kSquare] != 0.0f) {
        const float pw = pulseWidth_;
        float sq = t < pw ? 1.0f : -1.0f;
        sq += 2.0f * edgeCorrection<blepResidual>(t, 0.0f, dt);
        sq -= 2.0f * edgeCorrection<blepResidual>(t, pw, dt);
        out += w[kSquare] * (sq - (2.0f * pw - 1.0f));
    }
    if (w[kTri] != 0.0f) {
        float tri;
        if (t < 0.25f) {
            tri = 4.0f * t;
        } else if (t < 0.75f) {
            tri = 2.0f - 4.0f * t;
        } else {
            tri = 4.0f * t - 4.0f;
        }
        // Slope changes by -8 dt per sample at 1/4 cycle and +8 dt at 3/4.
        const float slope = 8.0f * dt;
        tri += slope * (edgeCorrection<blampResidual>(t, 0.75f, dt) -
                        edgeCorrection<blampResidual>(t, 0.25f, dt));
        out += w[kTri] * tri;
    }
    if (w[kSine] != 0.0f) out += w[kSine] * dsp::sinCycle(t);

    // Sub: a square on a half-rate phase that flips at every main-phase wrap.
    {
        const float s = (subSecondHalf_ ? 0.5f : 0.0f) + 0.5f * t;
        const float sdt = 0.5f * dt;
        float sub = subSecondHalf_ ? -1.0f : 1.0f;
        sub += 2.0f * edgeCorrection<blepResidual>(s, 0.0f, sdt);
        sub -= 2.0f * edgeCorrection<blepResidual>(s, 0.5f, sdt);
        sub_ = sub;
    }

    const uint32_t next = phase_ + increment_;  // wraps modulo 2^32 by design
    if (next < phase_) subSecondHalf_ = !subSecondHalf_;
    phase_ = next;
    return out;
}

}  // namespace groove
