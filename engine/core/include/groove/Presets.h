#pragma once

#include "groove/SynthParams.h"

namespace groove {

// A factory preset: a complete value for every parameter (F1.8, owner decision 1).
struct Preset {
    const char* name;
    const char* category;  // bass, lead, pad, pluck, fx or init
    ParamValues values;
};

// 16 original presets; index 0 is the default sound. Read-only constant data, safe to
// read from any thread.
int presetCount();
// index must be in [0, presetCount()); out-of-range indices return preset 0.
const Preset& preset(int index);

}  // namespace groove
