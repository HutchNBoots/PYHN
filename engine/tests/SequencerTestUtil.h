#pragma once

// Offline harness for Sequencer tests: fake instruments that log every call with the frame
// it happened at, and a driver that applies commands and advances the clock exactly the way
// AudioGraph::render() does (commands at each chunk start, then fire-and-render up to each
// event frame).

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

#include "groove/Instrument.h"
#include "groove/Sequencer.h"

namespace groove::test {

struct SeqCall {
    enum class Type { NoteOn, NoteOff, AllNotesOff, Motion, ClearMotion, Tempo };
    Type type;
    int64_t frame;
    int note;  // or motion target
    float value;  // velocity, motion value or tempo
};

class LoggingInstrument final : public Instrument {
public:
    const int64_t* clock = nullptr;
    std::vector<SeqCall> calls;

    void prepare(float) override {}
    void seqNoteOn(int note, float velocity) override { log(SeqCall::Type::NoteOn, note, velocity); }
    void seqNoteOff(int note) override { log(SeqCall::Type::NoteOff, note, 0.0f); }
    void seqAllNotesOff() override { log(SeqCall::Type::AllNotesOff, 0, 0.0f); }
    void setMotion(int target, float value) override { log(SeqCall::Type::Motion, target, value); }
    void clearAllMotion() override { log(SeqCall::Type::ClearMotion, 0, 0.0f); }
    void setTempo(float bpm) override { log(SeqCall::Type::Tempo, 0, bpm); }
    void renderStereo(float* l, float* r, int32_t n) override {
        std::fill(l, l + n, 0.0f);
        std::fill(r, r + n, 0.0f);
    }

    std::vector<SeqCall> of(SeqCall::Type type) const {
        std::vector<SeqCall> out;
        for (const SeqCall& c : calls) {
            if (c.type == type) out.push_back(c);
        }
        return out;
    }
    std::vector<SeqCall> noteOns() const { return of(SeqCall::Type::NoteOn); }
    std::vector<SeqCall> noteOffs() const { return of(SeqCall::Type::NoteOff); }
    void clear() { calls.clear(); }

private:
    void log(SeqCall::Type t, int note, float value) { calls.push_back({t, clock ? *clock : 0, note, value}); }
};

struct SeqHarness {
    explicit SeqHarness(float sampleRate = 48000.0f) : seq(std::make_unique<Sequencer>()) {
        for (int s = 0; s < kSlotCount; ++s) {
            inst[static_cast<std::size_t>(s)].clock = &frame;
            seq->attach(s, &inst[static_cast<std::size_t>(s)]);
        }
        seq->prepare(sampleRate);
        for (auto& i : inst) i.clear();
    }

    // Renders `frames` frames in callbacks of `block` frames.
    void run(int64_t frames, int block = 96) {
        const int64_t end = frame + frames;
        while (frame < end) {
            const auto n = static_cast<int32_t>(std::min<int64_t>(block, end - frame));
            seq->applyCommands(frame);
            for (int32_t pos = 0; pos < n;) {
                const int32_t step = seq->advance(frame, n - pos);
                frame += step;
                pos += step;
            }
        }
    }
    // Renders up to (not including) the given frame.
    void runTo(int64_t target, int block = 96) {
        if (target > frame) run(target - frame, block);
    }
    // Applies queued commands now, as the next chunk start would.
    void apply() { seq->applyCommands(frame); }

    LoggingInstrument& slot(int s) { return inst[static_cast<std::size_t>(s)]; }

    std::vector<RecordedEvent> recorded() {
        std::vector<RecordedEvent> out;
        RecordedEvent e;
        while (seq->popRecorded(e)) out.push_back(e);
        return out;
    }

    // Every step of a pattern on, one note each (base + step), default gate.
    void fillSteps(int s, int pattern, int base, float probability = 1.0f) {
        for (int i = 0; i < kMaxSteps; ++i) {
            const int note = base + i;
            seq->setStep(s, pattern, i, true, &note, 1, 0.8f, -1.0f, probability);
        }
    }

    std::unique_ptr<Sequencer> seq;
    std::array<LoggingInstrument, kSlotCount> inst;
    int64_t frame = 0;
};

// ceil(num / den) for non-negative num and positive den.
inline int64_t ceilDiv(int64_t num, int64_t den) { return (num + den - 1) / den; }

// The exact frame of grid step k (no shuffle) relative to Play, from first principles:
// k steps of 60 / (bpm * stepsPerBeat) seconds, bpm = tenths / 10, rounded up to a frame.
inline int64_t expectedStepFrame(int64_t k, int64_t sampleRate, int64_t tempoTenths, int64_t stepsPerBeat) {
    return ceilDiv(k * 600 * sampleRate, stepsPerBeat * tempoTenths);
}

}  // namespace groove::test
