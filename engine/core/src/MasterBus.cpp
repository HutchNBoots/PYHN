#include "groove/MasterBus.h"

#include <cmath>

#include "groove/DspMath.h"

namespace groove {

namespace {

float dbToGain(float db) { return std::pow(10.0f, db / 20.0f); }

// Non-finite or absurd input becomes silence rather than poisoning the follower.
float sanitize(float x) { return std::fabs(x) <= 1e9f ? x : 0.0f; }

}  // namespace

void MasterBus::prepare(float sampleRate) {
    ceiling_ = dbToGain(kCeilingDb);
    knee_ = dbToGain(kKneeDb);
    release_ = std::exp(-1.0f / (kReleaseSeconds * sampleRate));
    follower_ = 0.0f;
    lastGain_ = 1.0f;
}

void MasterBus::process(float* left, float* right, int32_t numFrames) {
    const float knee = knee_;
    const float span = ceiling_ - knee_;
    const float invSpan = 1.0f / span;
    float follower = follower_;
    float gain = 1.0f;
    for (int32_t i = 0; i < numFrames; ++i) {
        const float l = sanitize(left[i]);
        const float r = sanitize(right[i]);
        const float peak = std::fmax(std::fabs(l), std::fabs(r));
        follower = peak >= follower ? peak : peak + (follower - peak) * release_;
        gain = 1.0f;
        if (follower > knee) {
            // softClip(x) -> 1 smoothly at x = 3: the curve meets the ceiling with zero slope.
            const float level = knee + span * dsp::softClip((follower - knee) * invSpan);
            gain = level / follower;
        }
        // The clamp only guards against rounding; the curve already stays below it.
        left[i] = std::fmax(-ceiling_, std::fmin(ceiling_, l * gain));
        right[i] = std::fmax(-ceiling_, std::fmin(ceiling_, r * gain));
    }
    follower_ = follower;
    lastGain_ = gain;
}

}  // namespace groove
