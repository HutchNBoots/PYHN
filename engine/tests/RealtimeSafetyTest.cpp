// F0.5: the whole render path (synth voices, every DSP block, the master bus) never
// allocates, under note, parameter, preset and voice-mode traffic.

#include <gtest/gtest.h>

#include <vector>

#include "AllocationCounter.h"
#include "groove/Adsr.h"
#include "groove/AudioGraph.h"
#include "groove/Filters.h"
#include "groove/Lfo.h"
#include "groove/Oscillator.h"
#include "groove/Presets.h"

using namespace groove;

TEST(RealtimeSafety, AudioGraphRenderNeverAllocates) {
    AudioGraph graph;
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
