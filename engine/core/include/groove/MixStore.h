#pragma once

#include <array>
#include <atomic>

#include "groove/MixParams.h"

namespace groove {

// Lock-free stores for the global mixer/FX/tape parameters (MixParams.def) and the per-channel
// mixer parameters (ChannelParams.def). Same rules as ParamStore: written by the control
// thread only (values sanitized: clamped, Int/Enum rounded, NaN -> default), read by the
// audio thread once per render chunk with relaxed loads; the audio side smooths every change.
class MixParamStore {
public:
    static_assert(std::atomic<float>::is_always_lock_free, "audio thread needs lock-free floats");

    MixParamStore() {
        for (std::size_t i = 0; i < kMixParamCount; ++i) values_[i].store(kMixParamInfo[i].def, std::memory_order_relaxed);
    }
    void set(MixParamId id, float value) {
        values_[index(id)].store(sanitize(mixParamInfo(id), value), std::memory_order_relaxed);
    }
    float get(MixParamId id) const { return values_[index(id)].load(std::memory_order_relaxed); }

private:
    std::array<std::atomic<float>, kMixParamCount> values_;
};

class ChannelParamStore {
public:
    ChannelParamStore() {
        for (auto& ch : values_) {
            for (std::size_t i = 0; i < kChannelParamCount; ++i) ch[i].store(kChannelParamInfo[i].def, std::memory_order_relaxed);
        }
    }
    static bool validChannel(int channel) { return channel >= 0 && channel < kMixerChannelCount; }
    // Out-of-range channels are ignored (get returns 0).
    void set(int channel, ChannelParamId id, float value) {
        if (!validChannel(channel)) return;
        values_[static_cast<std::size_t>(channel)][index(id)].store(sanitize(channelParamInfo(id), value),
                                                                     std::memory_order_relaxed);
    }
    float get(int channel, ChannelParamId id) const {
        if (!validChannel(channel)) return 0.0f;
        return values_[static_cast<std::size_t>(channel)][index(id)].load(std::memory_order_relaxed);
    }

private:
    std::array<std::array<std::atomic<float>, kChannelParamCount>, kMixerChannelCount> values_;
};

}  // namespace groove
