#include "groove/LevelMeters.h"

#include <algorithm>
#include <cmath>

namespace groove {

void LevelMeters::prepare(float sampleRate) {
    const float fs = sampleRate > 0.0f && std::isfinite(sampleRate) ? sampleRate : 48000.0f;
    framesPerTau_ = kReleaseSeconds * fs;
    holdFrames_ = static_cast<int32_t>(std::lround(kClipHoldSeconds * fs));
    reset();
}

void LevelMeters::reset() {
    level_.fill(0.0f);
    holdLeft_.fill(0);
    for (auto& l : levelOut_) l.store(0.0f, std::memory_order_relaxed);
    for (auto& c : clipOut_) c.store(false, std::memory_order_relaxed);
}

void LevelMeters::update(const float* peaks, const bool* clips, int32_t frames) {
    if (frames <= 0) return;
    const float release = std::exp(-static_cast<float>(frames) / framesPerTau_);
    for (std::size_t i = 0; i < static_cast<std::size_t>(kCount); ++i) {
        float peak = peaks[i];
        if (!(peak > 0.0f)) peak = 0.0f;  // NaN or negative: no reading
        peak = std::min(peak, kMaxLevel);
        float level = level_[i] * release;
        if (level < kFloor) level = 0.0f;
        level = std::max(level, peak);  // instant attack
        level_[i] = level;

        int32_t& hold = holdLeft_[i];
        hold = clips[i] ? holdFrames_ : std::max<int32_t>(0, hold - frames);

        levelOut_[i].store(level, std::memory_order_relaxed);
        clipOut_[i].store(hold > 0, std::memory_order_relaxed);
    }
}

float LevelMeters::level(int meter) const {
    if (meter < 0 || meter >= kCount) return 0.0f;
    return levelOut_[static_cast<std::size_t>(meter)].load(std::memory_order_relaxed);
}

bool LevelMeters::clipped(int meter) const {
    if (meter < 0 || meter >= kCount) return false;
    return clipOut_[static_cast<std::size_t>(meter)].load(std::memory_order_relaxed);
}

void LevelMeters::read(float* levels, bool* clips) const {
    for (std::size_t i = 0; i < static_cast<std::size_t>(kCount); ++i) {
        levels[i] = levelOut_[i].load(std::memory_order_relaxed);
        clips[i] = clipOut_[i].load(std::memory_order_relaxed);
    }
}

}  // namespace groove
