#include "groove/AudioGraph.h"

#include <algorithm>
#include <climits>
#include <cmath>

namespace groove {

AudioGraph::AudioGraph() : drums_(std::make_unique<DrumMachine>()), sequencer_(std::make_unique<Sequencer>()) {
    instruments_[kDrumSlot] = drums_.get();
    for (std::size_t i = 0; i < synths_.size(); ++i) {
        synths_[i] = std::make_unique<SynthEngine>();
        instruments_[i + kFirstSynthSlot] = synths_[i].get();
    }
    for (int s = 0; s < kSlots; ++s) sequencer_->attach(s, instruments_[static_cast<std::size_t>(s)]);
    prepare(48000.0f);
}

AudioGraph::~AudioGraph() = default;

void AudioGraph::prepare(float sampleRate) {
    for (Instrument* inst : instruments_) inst->prepare(sampleRate);
    master_.prepare(sampleRate);
    sequencer_->prepare(sampleRate);  // after the instruments: it sends them the tempo
    frame_.store(0, std::memory_order_relaxed);
}

SynthEngine& AudioGraph::synth(int slot) {
    if (!isSynthSlot(slot)) slot = kFirstSynthSlot;
    return *synths_[static_cast<std::size_t>(slot - kFirstSynthSlot)];
}

Instrument& AudioGraph::instrument(int slot) {
    return *instruments_[static_cast<std::size_t>(std::clamp(slot, 0, kSlots - 1))];
}

bool AudioGraph::noteOn(int slot, int note, float velocity, int64_t tapTimeNs) {
    if (slot < 0 || slot >= kSlots || note < 0 || note > 127) return false;
    if (std::isnan(velocity)) velocity = 1.0f;
    velocity = std::clamp(velocity, 0.0f, 1.0f);
    return notes_.push({slot, {NoteEvent::Type::NoteOn, note, velocity, tapTimeNs}});
}

bool AudioGraph::noteOff(int slot, int note) {
    if (slot < 0 || slot >= kSlots || note < 0 || note > 127) return false;
    return notes_.push({slot, {NoteEvent::Type::NoteOff, note, 0.0f, 0}});
}

bool AudioGraph::allNotesOff() { return notes_.push({-1, {NoteEvent::Type::AllNotesOff, 0, 0.0f, 0}}); }

void AudioGraph::applyPendingCommands() { sequencer_->applyCommands(framesRendered(), INT_MAX); }

void AudioGraph::forwardNotes(int64_t frame) {
    // The audio thread is the only producer of the instruments' UI note queues (the control
    // thread never calls their noteOn directly in the app), so they stay single-producer.
    // Each instrument drains its queue at its next renderStereo(), later in this same chunk,
    // so the note sounds from `frame`: the frame live recording uses (F3.6).
    KeyNote k;
    while (notes_.pop(k)) {
        const NoteEvent& e = k.event;
        if (k.slot < 0) {
            drums_->allNotesOff();
            for (auto& s : synths_) s->allNotesOff();
            continue;
        }
        if (e.type == NoteEvent::Type::NoteOn) {
            const bool queued = k.slot == kDrumSlot ? drums_->noteOn(e.note, e.velocity, e.tapTimeNs)
                                                    : synth(k.slot).noteOn(e.note, e.velocity, e.tapTimeNs);
            if (queued) sequencer_->recordLiveNote(k.slot, e.note, e.velocity, frame);
        } else if (k.slot == kDrumSlot) {
            drums_->noteOff(e.note);
        } else {
            synth(k.slot).noteOff(e.note);
        }
    }
}

void AudioGraph::renderSlots(int32_t offset, int32_t numFrames) {
    float* l = left_.data() + offset;
    float* r = right_.data() + offset;
    for (Instrument* inst : instruments_) {
        inst->renderStereo(slotLeft_.data(), slotRight_.data(), numFrames);
        for (int32_t i = 0; i < numFrames; ++i) {
            l[i] += kSlotGain * slotLeft_[static_cast<std::size_t>(i)];
            r[i] += kSlotGain * slotRight_[static_cast<std::size_t>(i)];
        }
    }
}

void AudioGraph::render(float* out, int32_t numFrames, int32_t channelCount) {
    if (channelCount <= 0) return;
    for (int32_t done = 0; done < numFrames;) {
        const int32_t n = std::min(kChunkFrames, numFrames - done);
        const int64_t chunkStart = frame_.load(std::memory_order_relaxed);

        sequencer_->applyCommands(chunkStart);
        forwardNotes(chunkStart);

        std::fill(left_.begin(), left_.begin() + n, 0.0f);
        std::fill(right_.begin(), right_.begin() + n, 0.0f);
        for (int32_t pos = 0; pos < n;) {
            // Fires the events due at this frame, then renders up to the next one.
            const int32_t run = sequencer_->advance(chunkStart + pos, n - pos);
            renderSlots(pos, run);
            pos += run;
        }
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
        // Single writer (this thread).
        frame_.store(chunkStart + n, std::memory_order_relaxed);
        done += n;
    }
}

}  // namespace groove
