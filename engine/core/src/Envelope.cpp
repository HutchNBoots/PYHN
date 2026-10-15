#include "groove/Envelope.h"

#include <algorithm>

namespace groove {

namespace {
// Snap to the end of a ramp when float accumulation lands just short of it.
constexpr float kSnap = 1e-5f;
}  // namespace

void Envelope::setSampleRate(float sampleRate) {
    sampleRate_ = sampleRate;
    updateSteps();
}

void Envelope::setAttackMs(float ms) {
    attackMs_ = ms;
    updateSteps();
}

void Envelope::setReleaseMs(float ms) {
    releaseMs_ = ms;
    updateSteps();
}

void Envelope::updateSteps() {
    const float attackSamples = std::max(1.0f, attackMs_ * 0.001f * sampleRate_);
    const float releaseSamples = std::max(1.0f, releaseMs_ * 0.001f * sampleRate_);
    attackStep_ = 1.0f / attackSamples;
    releaseStep_ = 1.0f / releaseSamples;
}

float Envelope::process() {
    switch (stage_) {
        case Stage::Idle:
            level_ = 0.0f;
            break;
        case Stage::Attack:
            level_ += attackStep_;
            if (level_ >= 1.0f - kSnap) {
                level_ = 1.0f;
                stage_ = Stage::Sustain;
            }
            break;
        case Stage::Sustain:
            level_ = 1.0f;
            break;
        case Stage::Release:
            level_ -= releaseStep_;
            if (level_ <= kSnap) {
                level_ = 0.0f;
                stage_ = Stage::Idle;
            }
            break;
    }
    return level_;
}

}  // namespace groove
