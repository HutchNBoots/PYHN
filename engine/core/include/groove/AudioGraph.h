#pragma once

#include <array>
#include <cstdint>

#include "groove/MasterBus.h"
#include "groove/SynthEngine.h"

namespace groove {

// Everything the audio callback renders, in order: synth -> master bus -> interleaved
// output. M1 has one synth slot; the mixer and FX slot in here later.
//
// Threading: render() on the audio thread only; synth()'s control side from the control
// thread (see SynthEngine); prepare() only while render() cannot run.
// render() is allocation-free for any numFrames (it works in fixed-size chunks).
class AudioGraph {
public:
    static constexpr int32_t kChunkFrames = 256;

    void prepare(float sampleRate) {
        synth_.prepare(sampleRate);
        master_.prepare(sampleRate);
    }

    SynthEngine& synth() { return synth_; }
    MasterBus& master() { return master_; }

    // Writes numFrames interleaved frames: channel 0 = left, 1 = right, any further
    // channels silent; a mono stream gets the average of left and right.
    void render(float* out, int32_t numFrames, int32_t channelCount);

private:
    SynthEngine synth_;
    MasterBus master_;
    std::array<float, kChunkFrames> left_{};
    std::array<float, kChunkFrames> right_{};
};

}  // namespace groove
