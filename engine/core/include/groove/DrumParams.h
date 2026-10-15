#pragma once

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

#include "groove/SynthParams.h"  // ParamInfo, Curve

namespace groove {

enum class DrumParamId : int32_t {
#define GROOVE_DRUM_PARAM(id, key, label, min, max, def, curve, unit) id,
#include "groove/DrumParams.def"
#undef GROOVE_DRUM_PARAM
    Count
};

inline constexpr std::size_t kDrumParamCount = static_cast<std::size_t>(DrumParamId::Count);

constexpr std::size_t index(DrumParamId id) { return static_cast<std::size_t>(id); }

inline constexpr std::array<ParamInfo, kDrumParamCount> kDrumParamInfo = {{
#define GROOVE_DRUM_PARAM(id, key, label, min, max, def, curve, unit) \
    ParamInfo{key, label, static_cast<float>(min), static_cast<float>(max), static_cast<float>(def), Curve::curve, unit},
#include "groove/DrumParams.def"
#undef GROOVE_DRUM_PARAM
}};

constexpr const ParamInfo& drumParamInfo(DrumParamId id) { return kDrumParamInfo[index(id)]; }

// Clamps to the range, rounds Enum values, and maps NaN to the default.
inline float sanitizeDrumParam(DrumParamId id, float value) {
    const ParamInfo& p = drumParamInfo(id);
    if (std::isnan(value)) return p.def;
    if (value < p.min) value = p.min;
    if (value > p.max) value = p.max;
    if (p.discrete()) value = std::nearbyint(value);
    return value;
}

}  // namespace groove
