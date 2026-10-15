// STUB: silent placeholder so the rest of the engine builds while the drums module is
// written. The drums module replaces this file entirely.

#include "groove/DrumMachine.h"

#include <array>
#include <atomic>

namespace groove {

struct DrumMachine::Impl {
    std::array<std::array<std::atomic<float>, kDrumParamCount>, kSoundCount> params;
    std::atomic<int64_t> frames{0};
    Impl() {
        for (auto& key : params) {
            for (std::size_t i = 0; i < kDrumParamCount; ++i) key[i].store(kDrumParamInfo[i].def);
        }
    }
};

DrumMachine::DrumMachine() : impl_(new Impl()) {}
DrumMachine::~DrumMachine() { delete impl_; }

bool DrumMachine::noteOn(int, float, int64_t) { return true; }
bool DrumMachine::noteOff(int) { return true; }
bool DrumMachine::allNotesOff() { return true; }
bool DrumMachine::popNoteOnRender(NoteOnRender&) { return false; }

void DrumMachine::setParam(int key, DrumParamId id, float value) {
    if (key < 0 || key >= kSoundCount) return;
    impl_->params[static_cast<std::size_t>(key)][index(id)].store(sanitizeDrumParam(id, value),
                                                                  std::memory_order_relaxed);
}

float DrumMachine::param(int key, DrumParamId id) const {
    if (key < 0 || key >= kSoundCount) return 0.0f;
    return impl_->params[static_cast<std::size_t>(key)][index(id)].load(std::memory_order_relaxed);
}

const char* DrumMachine::soundName(int key) { return key >= 0 && key < kSoundCount ? "Drum" : ""; }

void DrumMachine::prepare(float) { impl_->frames.store(0, std::memory_order_relaxed); }
void DrumMachine::seqNoteOn(int, float) {}
void DrumMachine::seqNoteOff(int) {}
void DrumMachine::seqAllNotesOff() {}
void DrumMachine::setMotion(int, float) {}
void DrumMachine::clearAllMotion() {}
void DrumMachine::setTempo(float) {}

void DrumMachine::renderStereo(float* left, float* right, int32_t numFrames) {
    for (int32_t i = 0; i < numFrames; ++i) left[i] = right[i] = 0.0f;
    impl_->frames.store(impl_->frames.load(std::memory_order_relaxed) + numFrames, std::memory_order_relaxed);
}

int64_t DrumMachine::framesRendered() const { return impl_->frames.load(std::memory_order_relaxed); }

}  // namespace groove
