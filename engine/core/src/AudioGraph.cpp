#include "groove/AudioGraph.h"

#include <algorithm>

namespace groove {

void AudioGraph::render(float* out, int32_t numFrames, int32_t channelCount) {
    if (channelCount <= 0) return;
    for (int32_t done = 0; done < numFrames;) {
        const int32_t n = std::min(kChunkFrames, numFrames - done);
        synth_.renderStereo(left_.data(), right_.data(), n);
        master_.process(left_.data(), right_.data(), n);
        for (int32_t i = 0; i < n; ++i) {
            const float l = left_[static_cast<std::size_t>(i)];
            const float r = right_[static_cast<std::size_t>(i)];
            if (channelCount == 1) {
                *out++ = 0.5f * (l + r);
                continue;
            }
            *out++ = l;
            *out++ = r;
            for (int32_t ch = 2; ch < channelCount; ++ch) *out++ = 0.0f;
        }
        done += n;
    }
}

}  // namespace groove
