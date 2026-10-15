#include "groove/Lfo.h"

#include <cmath>

namespace groove {

const float Lfo::kDivisionBeats[kDivisionCount] = {
    16.0f, 8.0f, 4.0f, 2.0f, 4.0f / 3.0f, 1.0f, 2.0f / 3.0f, 0.5f, 1.0f / 3.0f, 0.25f, 1.0f / 6.0f, 0.125f, 1.0f / 12.0f,
};

const char* const Lfo::kDivisionNames[kDivisionCount] = {
    "4 bars", "2 bars", "1 bar", "1/2", "1/2T", "1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T", "1/32", "1/32T",
};

float Lfo::divisionHz(int division, float bpm) {
    if (division < 0) division = 0;
    if (division >= kDivisionCount) division = kDivisionCount - 1;
    return bpm / (60.0f * kDivisionBeats[division]);
}

int Lfo::nearestDivision(float rateHz, float bpm) {
    int best = 0;
    float bestDistance = 1e30f;
    const float target = std::log2(rateHz > 1e-6f ? rateHz : 1e-6f);
    for (int i = 0; i < kDivisionCount; ++i) {
        const float d = std::fabs(std::log2(divisionHz(i, bpm)) - target);
        if (d < bestDistance) {
            bestDistance = d;
            best = i;
        }
    }
    return best;
}

float Lfo::process() {
    const float t = phase_;
    float out = 0.0f;
    switch (shape_) {
        case Shape::Sine:
            out = dsp::sinCycle(t);
            break;
        case Shape::Triangle:
            out = t < 0.25f ? 4.0f * t : (t < 0.75f ? 2.0f - 4.0f * t : 4.0f * t - 4.0f);
            break;
        case Shape::Square:
            out = t < 0.5f ? 1.0f : -1.0f;
            break;
        case Shape::SampleHold:
            out = held_;
            break;
    }
    phase_ += increment_;
    if (phase_ >= 1.0f) {
        phase_ -= std::floor(phase_);
        held_ = rng_.nextBipolar();
    }
    return out;
}

}  // namespace groove
