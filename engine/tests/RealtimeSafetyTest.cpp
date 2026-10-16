// F0.5: the whole render path (sequencer, all 8 slots, every DSP block, the master bus)
// never allocates, under note, parameter, preset, voice-mode, pattern, transport and
// recording traffic.

#include <gtest/gtest.h>

#include <memory>
#include <vector>

#include "AllocationCounter.h"
#include "groove/Adsr.h"
#include "groove/AudioGraph.h"
#include "groove/Filters.h"
#include "groove/Lfo.h"
#include "groove/Oscillator.h"
#include "groove/Presets.h"
#include "groove/Sequencer.h"

using namespace groove;

TEST(RealtimeSafety, AudioGraphRenderNeverAllocates) {
    auto owned = std::make_unique<AudioGraph>();
    AudioGraph& graph = *owned;
    graph.prepare(48000.0f);
    std::vector<float> out(4096 * 2);
    groove::test::AllocationScope scope;
    for (int i = 0; i < 3000; ++i) {
        SynthEngine& s = graph.synth(1);
        if (i % 7 == 0) s.noteOn(36 + (i * 5) % 48, 0.5f + 0.5f * static_cast<float>(i % 3) / 2.0f, i + 1);
        if (i % 7 == 4) s.noteOff(36 + ((i - 4) * 5) % 48);
        if (i % 50 == 0) s.loadPreset((i / 50) % presetCount());
        if (i % 3 == 0) {
            const auto id = static_cast<ParamId>(i % static_cast<int>(kParamCount));
            const ParamInfo& p = paramInfo(id);
            s.setParam(id, p.min + (p.max - p.min) * static_cast<float>(i % 11) / 10.0f);
        }
        if (i % 400 == 399) s.allNotesOff();
        const int32_t frames = (i % 5 == 0) ? 4096 : 96 + i % 37;  // includes odd and huge sizes
        graph.render(out.data(), frames, 2);
        NoteOnRender rec;
        while (s.popNoteOnRender(rec)) {
        }
    }
    EXPECT_EQ(scope.count(), 0u);
}

TEST(RealtimeSafety, EveryDspBlockRendersWithoutAllocating) {
    Oscillator osc;
    osc.setSampleRate(48000.0f);
    LadderFilter ladder;
    OnePoleHighPass hpf;
    Adsr env;
    Lfo lfo;
    MasterBus bus;
    const AdsrShape shape = AdsrShape::compute(0.01f, 0.1f, 0.5f, 0.1f, 48000.0f);
    std::vector<float> l(256), r(256);
    groove::test::AllocationScope scope;
    env.gateOn();
    for (int block = 0; block < 100; ++block) {
        osc.setFrequency(100.0f + 50.0f * static_cast<float>(block));
        osc.setWave(static_cast<float>(block % 30) / 10.0f);
        ladder.setCutoff(200.0f + 100.0f * static_cast<float>(block));
        hpf.setCutoff(50.0f, 48000.0f);
        for (size_t i = 0; i < l.size(); ++i) {
            l[i] = r[i] = env.process(shape) * ladder.process(hpf.process(osc.process() + osc.sub())) + lfo.process();
        }
        bus.process(l.data(), r.data(), 256);
    }
    EXPECT_EQ(scope.count(), 0u);
}

// The full M2 graph: sequencer playing every slot with shuffle, probability, motion lanes,
// step loops, queued switches, tempo changes, live and motion recording, keys on every slot.
TEST(RealtimeSafety, FullGraphWithSequencerTrafficNeverAllocates) {
    auto owned = std::make_unique<AudioGraph>();
    AudioGraph& g = *owned;
    g.prepare(48000.0f);
    Sequencer& seq = g.sequencer();
    for (int s = 0; s < kSlotCount; ++s) {
        for (int p = 0; p < 2; ++p) {
            seq.setPatternSettings(s, p, 12 + 4 * p, (s + p) % 4, 0.3f * static_cast<float>(p), 0.6f);
            for (int i = 0; i < 16; ++i) {
                const int notes[] = {s == 0 ? 48 + i : 40 + i, 52 + i, 55 + i, 59 + i};
                seq.setStep(s, p, i, i % 3 != 2, notes, s == 0 ? 1 : 1 + i % 4, 0.8f, i % 2 ? -1.0f : 0.9f,
                            i % 4 == 0 ? 0.5f : 1.0f);
            }
            seq.setMotionValue(s, p, s == 0 ? DrumMachine::motionTarget(1, DrumParamId::Tone)
                                            : static_cast<int>(ParamId::FltCutoff), 4, 900.0f);
        }
    }
    std::vector<float> out(4096 * 2);
    g.render(out.data(), 256, 2);  // let the setup commands in

    groove::test::AllocationScope scope;
    for (int i = 0; i < 4000; ++i) {
        const int slot = i % kSlotCount;
        if (i % 500 == 0) seq.play();
        if (i % 500 == 450) seq.stop();
        if (i % 97 == 0) seq.setRecordArmed(i % 194 == 0);
        if (i % 61 == 0) seq.setMotionRecording(i % 122 == 0);
        if (i % 33 == 0) seq.selectPattern(slot, (i / 33) % 2);
        if (i % 41 == 0) seq.setStepLoop(slot, 2, 5);
        if (i % 41 == 20) seq.setStepLoop(slot, -1, -1);
        if (i % 150 == 0) seq.setTempo(60.0f + static_cast<float>(i % 170));
        if (i % 7 == 0) {
            const int note = 60 + i % 5;
            seq.setStep(slot, i % 2, i % 16, true, &note, 1, 0.7f, -1.0f, 0.9f);
        }
        if (i % 13 == 0) seq.setMotionValue(slot, 0, slot == 0 ? 9 : 1, i % 16, 0.3f);
        if (i % 211 == 0) seq.clearMotionLane(slot, 1, 1);
        if (i % 307 == 0) seq.clearPattern(slot, 1);
        if (i % 17 == 0) seq.setPatternSettings(slot, 0, 8 + i % 9, i % 4, 0.5f, 0.5f);
        if (i % 5 == 0) g.noteOn(slot, slot == 0 ? 48 + i % 16 : 48 + i % 24, 0.8f, i + 1);
        if (i % 5 == 3) g.noteOff(slot, slot == 0 ? 48 + (i - 3) % 16 : 48 + (i - 3) % 24);
        if (i % 3 == 0 && slot != 0) {
            g.synth(slot).setParam(ParamId::FltCutoff, 200.0f + static_cast<float>(i % 50) * 100.0f);
            seq.recordMotion(slot, static_cast<int>(ParamId::FltCutoff), 200.0f + static_cast<float>(i % 50) * 100.0f);
        }
        if (i % 3 == 1) {
            g.drums().setParam(i % 16, DrumParamId::Pitch, static_cast<float>(i % 24) - 12.0f);
            seq.recordMotion(0, DrumMachine::motionTarget(i % 16, DrumParamId::Pitch), 3.0f);
        }
        if (i % 400 == 399) g.allNotesOff();
        const int32_t frames = (i % 9 == 0) ? 4096 : 64 + i % 200;
        g.render(out.data(), frames, 2);

        NoteOnRender rec;
        while (g.drums().popNoteOnRender(rec)) {
        }
        for (int s = 1; s < kSlotCount; ++s) {
            while (g.synth(s).popNoteOnRender(rec)) {
            }
        }
        RecordedEvent ev;
        while (seq.popRecorded(ev)) {
        }
        (void)seq.transport();
    }
    EXPECT_EQ(scope.count(), 0u);
}
