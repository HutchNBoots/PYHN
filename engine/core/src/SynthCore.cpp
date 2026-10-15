#include "groove/SynthCore.h"

#include <cmath>

namespace groove {

namespace {

constexpr float kAttackMs = 1.0f;
constexpr float kReleaseMs = 80.0f;
constexpr float kGainSmoothingSeconds = 0.005f;

float noteToHz(int note) {
    return 440.0f * std::pow(2.0f, static_cast<float>(note - 69) / 12.0f);
}

}  // namespace

SynthCore::SynthCore() { prepare(48000.0f); }

void SynthCore::prepare(float sampleRate) {
    osc_ = Oscillator{};
    osc_.setSampleRate(sampleRate);
    env_ = Envelope{};
    env_.setSampleRate(sampleRate);
    env_.setAttackMs(kAttackMs);
    env_.setReleaseMs(kReleaseMs);
    held_.clear();
    gainCoeff_ = 1.0f - std::exp(-1.0f / (kGainSmoothingSeconds * sampleRate));
    smoothedGain_ = gain_.load(std::memory_order_relaxed);
    framesRendered_.store(0, std::memory_order_relaxed);
}

bool SynthCore::noteOn(int note, int64_t tapTimeNs) {
    return events_.push({NoteEvent::Type::NoteOn, note, tapTimeNs});
}

bool SynthCore::noteOff(int note) { return events_.push({NoteEvent::Type::NoteOff, note, 0}); }

bool SynthCore::allNotesOff() { return events_.push({NoteEvent::Type::AllNotesOff, 0, 0}); }

void SynthCore::render(float* out, int32_t numFrames, int32_t channelCount) {
    // Events apply at the start of the block: UI events carry no frame position.
    NoteEvent event;
    while (events_.pop(event)) handle(event);

    osc_.setWaveform(static_cast<Waveform>(waveform_.load(std::memory_order_relaxed)));
    const float targetGain = gain_.load(std::memory_order_relaxed);

    for (int32_t frame = 0; frame < numFrames; ++frame) {
        smoothedGain_ += (targetGain - smoothedGain_) * gainCoeff_;
        const float sample = osc_.process() * env_.process() * smoothedGain_;
        for (int32_t ch = 0; ch < channelCount; ++ch) *out++ = sample;
    }

    // Single writer (this thread), so load + store is fine.
    framesRendered_.store(framesRendered_.load(std::memory_order_relaxed) + numFrames,
                          std::memory_order_relaxed);
}

void SynthCore::handle(const NoteEvent& e) {
    switch (e.type) {
        case NoteEvent::Type::NoteOn: {
            const bool wasEmpty = held_.empty();
            held_.push(e.note);
            startNote(e.note, wasEmpty);
            if (e.tapTimeNs != 0) {
                // Dropped if the control thread has stopped reading; measurement only.
                renderRecords_.push({e.tapTimeNs, framesRendered_.load(std::memory_order_relaxed)});
            }
            break;
        }
        case NoteEvent::Type::NoteOff: {
            const bool wasTop = held_.top() == e.note;
            held_.remove(e.note);
            if (held_.empty()) {
                env_.gateOff();
            } else if (wasTop) {
                startNote(held_.top(), false);
            }
            break;
        }
        case NoteEvent::Type::AllNotesOff:
            held_.clear();
            env_.gateOff();
            break;
    }
}

void SynthCore::startNote(int note, bool retrigger) {
    osc_.setFrequency(noteToHz(note));
    if (retrigger) {
        if (env_.isIdle()) osc_.resetPhase();
        env_.gateOn();
    }
}

}  // namespace groove
