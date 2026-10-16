#pragma once

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

#include "groove/SynthParams.h"  // ParamInfo, Curve

namespace groove {

enum class MixParamId : int32_t {
#define GROOVE_MIX_PARAM(id, key, label, min, max, def, curve, unit) id,
#include "groove/MixParams.def"
#undef GROOVE_MIX_PARAM
    Count
};

enum class ChannelParamId : int32_t {
#define GROOVE_CHANNEL_PARAM(id, key, label, min, max, def, curve, unit) id,
#include "groove/ChannelParams.def"
#undef GROOVE_CHANNEL_PARAM
    Count
};

inline constexpr std::size_t kMixParamCount = static_cast<std::size_t>(MixParamId::Count);
inline constexpr std::size_t kChannelParamCount = static_cast<std::size_t>(ChannelParamId::Count);
// Mixer channels: 0..7 sound slots, 8..11 tape tracks.
inline constexpr int kMixerChannelCount = 12;
inline constexpr int kFirstTapeChannel = 8;

constexpr std::size_t index(MixParamId id) { return static_cast<std::size_t>(id); }
constexpr std::size_t index(ChannelParamId id) { return static_cast<std::size_t>(id); }

inline constexpr std::array<ParamInfo, kMixParamCount> kMixParamInfo = {{
#define GROOVE_MIX_PARAM(id, key, label, min, max, def, curve, unit) \
    ParamInfo{key, label, static_cast<float>(min), static_cast<float>(max), static_cast<float>(def), Curve::curve, unit},
#include "groove/MixParams.def"
#undef GROOVE_MIX_PARAM
}};

inline constexpr std::array<ParamInfo, kChannelParamCount> kChannelParamInfo = {{
#define GROOVE_CHANNEL_PARAM(id, key, label, min, max, def, curve, unit) \
    ParamInfo{key, label, static_cast<float>(min), static_cast<float>(max), static_cast<float>(def), Curve::curve, unit},
#include "groove/ChannelParams.def"
#undef GROOVE_CHANNEL_PARAM
}};

constexpr const ParamInfo& mixParamInfo(MixParamId id) { return kMixParamInfo[index(id)]; }
constexpr const ParamInfo& channelParamInfo(ChannelParamId id) { return kChannelParamInfo[index(id)]; }

// Clamps to the range, rounds Int/Enum values, and maps NaN to the default.
inline float sanitize(const ParamInfo& p, float value) {
    if (std::isnan(value)) return p.def;
    if (value < p.min) value = p.min;
    if (value > p.max) value = p.max;
    if (p.discrete()) value = std::nearbyint(value);
    return value;
}

// A dB parameter at its minimum (-60) is silence.
inline float dbToGain(float db) { return db <= -60.0f ? 0.0f : std::pow(10.0f, db / 20.0f); }

}  // namespace groove
