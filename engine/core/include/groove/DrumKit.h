#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace groove {

// The built-in drum kit (docs/feature-spec.md F2.1, F2.4): 16 original sounds synthesised
// by our own code at the stream's sample rate, so the kit is licence-clean and needs no
// decoding or disk I/O. Each sound is a mono buffer, peak-normalised to kPeakDb, that
// starts and ends at (or ramps to) zero.
//
// Threading: render() allocates and must run while nothing reads the kit (DrumMachine
// calls it from prepare()). After that the buffers are read-only, so any thread may read
// them.
class DrumKit {
public:
    static constexpr int kSoundCount = 16;
    // Zero samples before and after every sound, so an interpolator reading a few
    // samples either side of the playback position never needs a bounds check.
    static constexpr int kGuard = 4;
    static constexpr float kPeakDb = -1.5f;

    // Synthesises every sound at sampleRate. Deterministic: the same rate always gives
    // bit-identical buffers.
    void render(float sampleRate);

    bool ready() const { return sampleRate_ > 0.0f; }
    float sampleRate() const { return sampleRate_; }

    // key 0..kSoundCount-1. data(key)[i] is valid for i in [-kGuard, length + kGuard).
    const float* data(int key) const { return storage_.data() + offset_[static_cast<std::size_t>(key)]; }
    int32_t length(int key) const { return length_[static_cast<std::size_t>(key)]; }

    // Original sound names, in key order; "" for an out-of-range key.
    static const char* name(int key);

private:
    float sampleRate_ = 0.0f;
    std::vector<float> storage_;
    std::array<std::size_t, kSoundCount> offset_{};
    std::array<int32_t, kSoundCount> length_{};
};

}  // namespace groove
