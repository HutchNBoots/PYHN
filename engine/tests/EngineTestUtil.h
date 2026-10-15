#pragma once

// Helpers for offline SynthEngine renders in tests.

#include <algorithm>
#include <cstdint>
#include <vector>

#include "groove/SynthEngine.h"

namespace groove::test {

struct StereoBuffer {
    std::vector<float> left;
    std::vector<float> right;
};

// Renders `frames` frames in blocks of `block` (like an audio callback would).
inline StereoBuffer renderStereo(SynthEngine& synth, int frames, int block = 96) {
    StereoBuffer b;
    b.left.resize(static_cast<std::size_t>(frames));
    b.right.resize(static_cast<std::size_t>(frames));
    for (int done = 0; done < frames; done += block) {
        const int n = std::min(block, frames - done);
        synth.renderStereo(b.left.data() + done, b.right.data() + done, n);
    }
    return b;
}

inline std::vector<float> renderLeft(SynthEngine& synth, int frames, int block = 96) {
    return renderStereo(synth, frames, block).left;
}

inline void append(std::vector<float>& to, const std::vector<float>& from) {
    to.insert(to.end(), from.begin(), from.end());
}

// A neutral measurement patch: pure sine, filter wide open and unmodulated, flat
// sustain, short attack and release, no velocity or LFO effects.
inline void setCleanSine(SynthEngine& synth) {
    synth.setParam(ParamId::OscWave, 3.0f);
    synth.setParam(ParamId::FltCutoff, 18000.0f);
    synth.setParam(ParamId::FltReso, 0.0f);
    synth.setParam(ParamId::FltEnvAmount, 0.0f);
    synth.setParam(ParamId::FltKeyTrack, 0.0f);
    synth.setParam(ParamId::FltVelocity, 0.0f);
    synth.setParam(ParamId::FltHighPass, 20.0f);
    synth.setParam(ParamId::AmpAttack, 0.001f);
    synth.setParam(ParamId::AmpDecay, 0.001f);
    synth.setParam(ParamId::AmpSustain, 1.0f);
    synth.setParam(ParamId::AmpRelease, 0.01f);
    synth.setParam(ParamId::LfoDepth, 0.0f);
}

}  // namespace groove::test
