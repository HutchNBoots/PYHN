#include "groove/Filters.h"

#include <algorithm>
#include <cmath>

namespace groove {

float onePoleCoefficient(float cutoffHz, float sampleRate) {
    const float fc = std::clamp(cutoffHz, 1.0f, 0.45f * sampleRate);
    const float g = std::tan(dsp::kPi * fc / sampleRate);
    return g / (1.0f + g);
}

void LadderFilter::setCoefficient(float g) {
    g_ = g;
    update(0);
}

void LadderFilter::setResonance(float resonance) {
    k_ = kMaxFeedback * std::clamp(resonance, 0.0f, 1.0f);
    update(0);
}

void LadderFilter::setDrive(float gain) {
    drive_ = std::max(gain, 1.0f);
    update(0);
}

void LadderFilter::configure(float g, float resonance, float driveGain, int rampSamples) {
    g_ = g;
    k_ = kMaxFeedback * std::clamp(resonance, 0.0f, 1.0f);
    drive_ = std::max(driveGain, 1.0f);
    update(rampSamples);
}

void LadderFilter::reset() {
    for (float& s : s_) s = 0.0f;
}

void LadderFilter::update(int rampSamples) {
    g2_ = g_ * g_;
    g3_ = g2_ * g_;
    oneMinusG_ = 1.0f - g_;
    feedbackNorm_ = 1.0f / (1.0f + k_ * g3_ * g_);
    // Half the resonance's bass loss (DC gain 1 / (1 + k)) is made up at the input.
    inputTarget_ = drive_ * (1.0f + 0.5f * k_);
    outputTarget_ = 1.0f / std::sqrt(drive_);
    if (rampSamples <= 0) {
        inputGain_ = inputTarget_;
        outputGain_ = outputTarget_;
        rampLeft_ = 0;
        return;
    }
    // Continue from where the previous ramp got to.
    const float inv = 1.0f / static_cast<float>(rampSamples);
    inputStep_ = (inputTarget_ - inputGain_) * inv;
    outputStep_ = (outputTarget_ - outputGain_) * inv;
    rampLeft_ = rampSamples;
}

}  // namespace groove
