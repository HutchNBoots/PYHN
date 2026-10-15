#pragma once

#include <cstdint>

namespace groove {

// Master bus soft limiter (F0.6): peaks never exceed the -0.3 dBFS ceiling, whatever
// comes in (including NaN or infinity, which are replaced by silence).
//
// A stereo-linked peak follower with instant attack and smooth release drives a soft
// knee: below the knee the signal passes untouched; above it the level is mapped
// smoothly toward the ceiling. Because the follower is never below the current sample,
// |out| <= curve(follower) <= ceiling holds for every sample, with no look-ahead (no
// added latency). Audio thread only; real-time safe.
class MasterBus {
public:
    static constexpr float kCeilingDb = -0.3f;
    static constexpr float kKneeDb = -3.0f;
    static constexpr float kReleaseSeconds = 0.12f;

    MasterBus() { prepare(48000.0f); }
    void prepare(float sampleRate);
    void process(float* left, float* right, int32_t numFrames);

    float ceiling() const { return ceiling_; }
    // Current gain reduction factor (1 = none), for tests and meters.
    float gain() const { return lastGain_; }

private:
    float ceiling_ = 1.0f;
    float knee_ = 1.0f;
    float release_ = 0.0f;
    float follower_ = 0.0f;
    float lastGain_ = 1.0f;
};

}  // namespace groove
