#pragma once

#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace groove {

// Same order as the Curve column of SynthParams.def and Kotlin's Curve enum (JNI ordinal).
enum class Curve : int32_t { Linear = 0, Exp = 1, Pow3 = 2, Int = 3, Enum = 4 };

enum class ParamId : int32_t {
#define GROOVE_PARAM(id, key, label, min, max, def, curve, unit) id,
#include "groove/SynthParams.def"
#undef GROOVE_PARAM
    Count
};

inline constexpr std::size_t kParamCount = static_cast<std::size_t>(ParamId::Count);

constexpr std::size_t index(ParamId id) { return static_cast<std::size_t>(id); }

struct ParamInfo {
    const char* key;
    const char* label;
    float min;
    float max;
    float def;
    Curve curve;
    const char* unit;  // for Enum: option labels separated by '|'

    constexpr bool discrete() const { return curve == Curve::Int || curve == Curve::Enum; }
};

inline constexpr std::array<ParamInfo, kParamCount> kParamInfo = {{
#define GROOVE_PARAM(id, key, label, min, max, def, curve, unit) \
    ParamInfo{key, label, static_cast<float>(min), static_cast<float>(max), static_cast<float>(def), Curve::curve, unit},
#include "groove/SynthParams.def"
#undef GROOVE_PARAM
}};

constexpr const ParamInfo& paramInfo(ParamId id) { return kParamInfo[index(id)]; }

// Clamps to the parameter's range and rounds Int/Enum values to the nearest index.
// Returns the default for NaN, so a bad value from the UI can never reach the DSP.
inline float sanitizeParam(ParamId id, float value) {
    const ParamInfo& p = paramInfo(id);
    if (std::isnan(value)) return p.def;
    if (value < p.min) value = p.min;
    if (value > p.max) value = p.max;
    if (p.discrete()) value = std::nearbyint(value);
    return value;
}

using ParamValues = std::array<float, kParamCount>;

constexpr ParamValues defaultParamValues() {
    ParamValues v{};
    for (std::size_t i = 0; i < kParamCount; ++i) v[i] = kParamInfo[i].def;
    return v;
}

// Current target value of every parameter, in real units.
//
// Threading: written by the control thread only (set, setAll), read by the audio thread
// once per render block (snapshot). Relaxed atomics are enough: each value is independent
// and the audio side smooths every change anyway (F0.7). Never allocates.
class ParamStore {
public:
    static_assert(std::atomic<float>::is_always_lock_free, "audio thread needs lock-free floats");

    ParamStore() { setAll(defaultParamValues()); }

    // --- control thread ---
    void set(ParamId id, float value) {
        values_[index(id)].store(sanitizeParam(id, value), std::memory_order_relaxed);
    }
    void setAll(const ParamValues& v) {
        for (std::size_t i = 0; i < kParamCount; ++i) set(static_cast<ParamId>(i), v[i]);
    }

    // --- any thread ---
    float get(ParamId id) const { return values_[index(id)].load(std::memory_order_relaxed); }
    void snapshot(ParamValues& out) const {
        for (std::size_t i = 0; i < kParamCount; ++i) out[i] = values_[i].load(std::memory_order_relaxed);
    }

private:
    std::array<std::atomic<float>, kParamCount> values_{};
};

}  // namespace groove
