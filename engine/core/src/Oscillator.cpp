#include "groove/Oscillator.h"

#include <algorithm>
#include <cmath>

namespace groove {

namespace {

constexpr float kTwoPi = 6.28318530717958647692f;

// Polynomial band-limited step correction around a discontinuity at phase 0.
// t is the phase in [0, 1), dt the phase increment per sample.
float polyBlep(float t, float dt) {
    if (t < dt) {
        t /= dt;
        return t + t - t * t - 1.0f;
    }
    if (t > 1.0f - dt) {
        t = (t - 1.0f) / dt;
        return t * t + t + t + 1.0f;
    }
    return 0.0f;
}

}  // namespace

void Oscillator::setSampleRate(float sampleRate) {
    sampleRate_ = sampleRate;
    setFrequency(frequency_);
}

void Oscillator::setFrequency(float hz) {
    // Keep below Nyquist so the PolyBLEP correction stays well-defined.
    frequency_ = std::clamp(hz, 0.0f, 0.45f * sampleRate_);
    increment_ = frequency_ / sampleRate_;
}

float Oscillator::process() {
    const float t = phase_;
    const float dt = increment_;
    float out = 0.0f;
    switch (waveform_) {
        case Waveform::Sine:
            out = std::sin(kTwoPi * t);
            break;
        case Waveform::Saw:
            out = 2.0f * t - 1.0f - polyBlep(t, dt);
            break;
        case Waveform::Square: {
            float t2 = t + 0.5f;
            if (t2 >= 1.0f) t2 -= 1.0f;
            out = (t < 0.5f ? 1.0f : -1.0f) + polyBlep(t, dt) - polyBlep(t2, dt);
            break;
        }
    }
    phase_ += dt;
    if (phase_ >= 1.0f) phase_ -= 1.0f;
    return out;
}

}  // namespace groove
