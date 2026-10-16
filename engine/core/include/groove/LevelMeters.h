#pragma once

#include <array>
#include <atomic>
#include <cstdint>

namespace groove {

// Peak level meters for the Mixer screen (docs/plans/2026-10-16-v1-closeout.md, "Level
// meters"): the 12 mixer channels, the delay and reverb returns and the master.
//
// The audio thread hands in one peak (linear amplitude, max of |L| and |R|) and one clip flag
// per meter for each rendered chunk. The meter value has an instant attack and an exponential
// release with a kReleaseSeconds time constant, applied per chunk from the chunk's length and
// the sample rate, so it falls the same way whatever the chunk sizes. A clip flag stays set
// for kClipHoldSeconds after the last chunk that clipped.
//
// Threading: update() on the audio thread only (no allocation, locks or logging: it is a few
// float operations and one exp per chunk). level(), clipped() and read() from any thread:
// the values are published through relaxed atomics, each one a whole float or bool, so a
// reader may see one chunk's value for one meter and the next chunk's for another, which is
// fine for a display. prepare() and reset() only while update() cannot run.
class LevelMeters {
public:
    // Meter indices: 0..11 the mixer channels (0..7 sound slots, 8..11 tape tracks), then the
    // FX returns and the master.
    static constexpr int kChannels = 12;
    static constexpr int kDelay = 12;
    static constexpr int kReverb = 13;
    static constexpr int kMaster = 14;
    static constexpr int kCount = 15;

    static constexpr float kReleaseSeconds = 0.3f;  // time constant: 1/e after 300 ms of silence
    static constexpr float kClipHoldSeconds = 1.0f;
    // Peaks are capped here (+24 dBFS), so a runaway or infinite signal reads as a full meter.
    static constexpr float kMaxLevel = 16.0f;
    // Below this (-120 dBFS) the falling level snaps to exactly 0.
    static constexpr float kFloor = 1e-6f;

    LevelMeters() { prepare(48000.0f); }
    void prepare(float sampleRate);  // also resets
    void reset();

    // Audio thread. peaks and clips hold kCount entries; frames is the chunk's length.
    void update(const float* peaks, const bool* clips, int32_t frames);

    // Any thread. Out-of-range meters read 0 / false.
    float level(int meter) const;
    bool clipped(int meter) const;
    // Copies all kCount levels and clip flags.
    void read(float* levels, bool* clips) const;

private:
    static_assert(std::atomic<float>::is_always_lock_free, "meters need lock-free floats");
    static_assert(std::atomic<bool>::is_always_lock_free, "meters need lock-free bools");

    float framesPerTau_ = 0.3f * 48000.0f;
    int32_t holdFrames_ = 48000;

    // Audio-thread state.
    std::array<float, kCount> level_{};
    std::array<int32_t, kCount> holdLeft_{};

    // Published.
    std::array<std::atomic<float>, kCount> levelOut_{};
    std::array<std::atomic<bool>, kCount> clipOut_{};
};

}  // namespace groove
