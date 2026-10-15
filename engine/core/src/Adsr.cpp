#include "groove/Adsr.h"

#include <algorithm>
#include <cmath>

namespace groove {

namespace {

constexpr float kMinTime = 0.001f;
constexpr float kMaxTime = 10.0f;
constexpr double kSustainFollowSeconds = 0.002;

// Per-step coefficient of a one-pole curve that covers its segment in `seconds` when it
// aims `ratio` past the end point (relative to the segment height).
double segmentCoef(float seconds, double ratio, float rate) {
    const double steps = std::max(1.0, static_cast<double>(std::clamp(seconds, kMinTime, kMaxTime)) * rate);
    return std::exp(-std::log((1.0 + ratio) / ratio) / steps);
}

}  // namespace

AdsrShape AdsrShape::compute(float attack, float decay, float sustain, float release, float rate) {
    AdsrShape s;
    s.attackCoef = segmentCoef(attack, Adsr::kAttackOvershoot, rate);
    s.decayCoef = segmentCoef(decay, Adsr::kDecayUndershoot, rate);
    s.releaseCoef = segmentCoef(release, Adsr::kDecayUndershoot, rate);
    s.sustain = static_cast<double>(std::clamp(sustain, 0.0f, 1.0f));
    s.sustainFollow = 1.0 - std::exp(-1.0 / std::max(1.0, kSustainFollowSeconds * rate));
    return s;
}

float Adsr::process(const AdsrShape& shape) {
    switch (stage_) {
        case Stage::Idle:
            level_ = 0.0;
            break;
        case Stage::Attack: {
            constexpr double target = 1.0 + kAttackOvershoot;
            level_ = target + (level_ - target) * shape.attackCoef;
            if (level_ >= 1.0) {
                level_ = 1.0;
                stage_ = Stage::Decay;
            }
            break;
        }
        case Stage::Decay: {
            const double s = shape.sustain;
            const double target = s - kDecayUndershoot * (1.0 - s);
            level_ = target + (level_ - target) * shape.decayCoef;
            if (level_ <= s) {
                level_ = s;
                stage_ = Stage::Sustain;
            }
            break;
        }
        case Stage::Sustain:
            level_ += (shape.sustain - level_) * shape.sustainFollow;
            break;
        case Stage::Release: {
            constexpr double target = -kDecayUndershoot;
            level_ = target + (level_ - target) * shape.releaseCoef;
            if (level_ <= 0.0) {
                level_ = 0.0;
                stage_ = Stage::Idle;
            }
            break;
        }
    }
    return static_cast<float>(level_);
}

}  // namespace groove
