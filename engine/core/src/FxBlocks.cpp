// STUB: placeholder so the rest of the engine builds while the FX module is written:
// the FX blocks output silence and the channel strip passes audio through at unity.
// The FX module replaces this file entirely.

#include "groove/FxBlocks.h"

namespace groove {

struct StereoDelay::Impl {};
StereoDelay::StereoDelay() : impl_(new Impl()) {}
StereoDelay::~StereoDelay() { delete impl_; }
void StereoDelay::prepare(float) {}
void StereoDelay::reset() {}
void StereoDelay::setTempo(float) {}
void StereoDelay::setTime(bool, int, float) {}
void StereoDelay::setFeedback(float) {}
void StereoDelay::setTone(float, float) {}
void StereoDelay::process(const float*, const float*, float* outL, float* outR, int32_t n) {
    for (int32_t i = 0; i < n; ++i) outL[i] = outR[i] = 0.0f;
}

struct Reverb::Impl {};
Reverb::Reverb() : impl_(new Impl()) {}
Reverb::~Reverb() { delete impl_; }
void Reverb::prepare(float) {}
void Reverb::reset() {}
void Reverb::setSize(float) {}
void Reverb::setDecaySeconds(float) {}
void Reverb::setDamping(float) {}
void Reverb::process(const float*, const float*, float* outL, float* outR, int32_t n) {
    for (int32_t i = 0; i < n; ++i) outL[i] = outR[i] = 0.0f;
}

struct ChannelStrip::Impl {};
ChannelStrip::ChannelStrip() : impl_(new Impl()) {}
ChannelStrip::~ChannelStrip() { delete impl_; }
void ChannelStrip::prepare(float) {}
void ChannelStrip::setVolumeDb(float) {}
void ChannelStrip::setPan(float) {}
void ChannelStrip::setMute(bool) {}
void ChannelStrip::setSends(float, float) {}
void ChannelStrip::process(const float* inL, const float* inR, int32_t n, float* mixL, float* mixR, float*, float*,
                           float*, float*) {
    for (int32_t i = 0; i < n; ++i) {
        mixL[i] += inL[i];
        mixR[i] += inR[i];
    }
}

}  // namespace groove
