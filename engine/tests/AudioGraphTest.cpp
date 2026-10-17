// AudioGraph (M2): 8 slots, sample-accurate sequencer events, live recording at the exact
// applied frame, every instrument rendered every frame, and threading.

#include "groove/AudioGraph.h"

#include <gtest/gtest.h>

#include <atomic>
#include <cmath>
#include <memory>
#include <thread>
#include <vector>

#include "Analysis.h"
#include "EngineTestUtil.h"
#include "groove/Presets.h"

using namespace groove;
using namespace groove::test;

namespace {

constexpr float kRate = 48000.0f;

int64_t ceilDivTest(int64_t num, int64_t den) { return (num + den - 1) / den; }

// Renders `frames` stereo frames in callbacks of `block`, returning the left channel.
std::vector<float> renderGraph(AudioGraph& g, int frames, int block = 96) {
    std::vector<float> out(static_cast<std::size_t>(frames) * 2);
    for (int done = 0; done < frames; done += block) {
        const int n = std::min(block, frames - done);
        g.render(out.data() + static_cast<std::size_t>(done) * 2, n, 2);
    }
    std::vector<float> left(static_cast<std::size_t>(frames));
    for (std::size_t i = 0; i < left.size(); ++i) left[i] = out[2 * i];
    return left;
}

std::size_t firstNonZero(const std::vector<float>& x, std::size_t from = 0) {
    for (std::size_t i = from; i < x.size(); ++i) {
        if (x[i] != 0.0f) return i;
    }
    return x.size();
}

}  // namespace

// A sequencer note lands on its exact frame in the output, even mid-callback: the graph
// splits the render at the event. Calibrated against the same note played at frame 0.
TEST(AudioGraph, SequencerNotesAreSampleAccurate) {
    auto ref = std::make_unique<AudioGraph>();
    setCleanSine(ref->synth(3));
    ref->synth(3).setParam(ParamId::OscWave, 0.0f);  // saw: non-zero from its first sample
    ref->prepare(kRate);
    ref->synth(3).seqNoteOn(57, 1.0f);
    const std::size_t latency = firstNonZero(renderGraph(*ref, 960));
    ASSERT_LT(latency, 4u);

    for (int block : {96, 37, 256, 1000}) {
        auto g = std::make_unique<AudioGraph>();
        setCleanSine(g->synth(3));
        g->synth(3).setParam(ParamId::OscWave, 0.0f);
        g->prepare(kRate);
        Sequencer& seq = g->sequencer();
        const int note = 57;
        seq.setTempo(133.7f);
        seq.setPatternSettings(3, 0, 8, 1, 0.3f, 0.25f);
        seq.setStep(3, 0, 3, {Hit{note, 1.0f}});
        seq.setStep(3, 0, 6, {Hit{note, 1.0f}});
        seq.play();
        const auto x = renderGraph(*g, 40000, block);
        // Steps 3 (odd: shuffled by 0.3 * 2/3 step) and 6, from the 1/3000-step formula.
        const int64_t step3 = ceilDivTest((3000 * 3 + 2 * 300) * 600 * 48000LL, 3000LL * 4 * 1337);
        const int64_t step6 = ceilDivTest(3000 * 6 * 600 * 48000LL, 3000LL * 4 * 1337);
        EXPECT_EQ(firstNonZero(x), static_cast<std::size_t>(step3) + latency) << block;
        // After step 3's gate the voice releases; find step 6's onset after silence.
        std::size_t silentFrom = static_cast<std::size_t>(step3) + 1;
        while (silentFrom < x.size() && !(x[silentFrom] == 0.0f && x[silentFrom + 1] == 0.0f)) ++silentFrom;
        ASSERT_LT(silentFrom, static_cast<std::size_t>(step6));
        EXPECT_EQ(firstNonZero(x, silentFrom + 2), static_cast<std::size_t>(step6) + latency) << block;
    }
}

TEST(AudioGraph, SlotsHaveTheirInstrumentsAndAllRenderEveryFrame) {
    auto g = std::make_unique<AudioGraph>();
    EXPECT_EQ(&g->instrument(0), static_cast<Instrument*>(&g->drums()));
    for (int s = 1; s < 8; ++s) EXPECT_EQ(&g->instrument(s), static_cast<Instrument*>(&g->synth(s)));
    EXPECT_FALSE(AudioGraph::isSynthSlot(0));
    EXPECT_FALSE(AudioGraph::isSynthSlot(8));

    // Busy sequence, odd block sizes: every instrument's frame counter matches the graph's.
    Sequencer& seq = g->sequencer();
    for (int s = 0; s < 8; ++s) {
        seq.setPatternSettings(s, 0, 16, s % 4, 0.5f, 0.5f);
        for (int i = 0; i < 16; ++i) {
            const int note = s == 0 ? 48 + i % 16 : 48 + i;
            seq.setStep(s, 0, i, {Hit{note, 0.8f}});
        }
    }
    seq.play();
    int total = 0;
    std::vector<float> out(2 * 600);
    for (int i = 0; i < 300; ++i) {
        const int n = 17 + (i * 37) % 580;
        g->render(out.data(), n, 2);
        total += n;
    }
    EXPECT_EQ(g->framesRendered(), total);
    EXPECT_EQ(g->drums().framesRendered(), total);
    for (int s = 1; s < 8; ++s) EXPECT_EQ(g->synth(s).framesRendered(), total);
}

// Left-channel gain of a channel strip at its default settings (0 dB, centre pan), measured.
static double defaultStripGain() {
    ChannelStrip strip;
    strip.prepare(kRate);
    std::vector<float> in(256, 0.5f), outL(256), outR(256);
    for (int i = 0; i < 40; ++i) {
        std::fill(outL.begin(), outL.end(), 0.0f);
        std::fill(outR.begin(), outR.end(), 0.0f);
        strip.process(in.data(), in.data(), 256, outL.data(), outR.data(), nullptr, nullptr, nullptr, nullptr);
    }
    return outL[255] / 0.5;
}

// Each slot is summed at the same fixed gain, through its channel strip at default settings.
TEST(AudioGraph, SlotsAreSummedAtEqualGain) {
    std::vector<double> levels;
    for (int s = 1; s < 8; s += 3) {
        auto g = std::make_unique<AudioGraph>();
        setCleanSine(g->synth(s));
        g->synth(s).setParam(ParamId::AmpSustain, 1.0f);
        g->prepare(kRate);
        g->noteOn(s, 57, 0.5f, 0);
        levels.push_back(rms(renderGraph(*g, 9600), 4800));
    }
    auto solo = std::make_unique<SynthEngine>();
    setCleanSine(*solo);
    solo->prepare(kRate);
    solo->noteOn(57, 0.5f, 0);
    const double direct = rms(renderLeft(*solo, 9600), 4800);
    for (double l : levels) EXPECT_NEAR(l, AudioGraph::kSlotGain * defaultStripGain() * direct, 1e-4);
}

// A note from the keys is applied at the start of the next chunk, and live recording uses
// exactly that frame (F3.6).
TEST(AudioGraph, LiveRecordingUsesTheFrameTheNoteIsApplied) {
    auto g = std::make_unique<AudioGraph>();
    Sequencer& seq = g->sequencer();
    seq.setRecordArmed(true);
    seq.play();
    // 1/16 at 120 BPM = 6000 frames. Callbacks of 96: the chunk at 2976 (31 x 96) is 24
    // frames before the midpoint of step 0..1 (3000); the one at 3072 is after it.
    renderGraph(*g, 2976);
    g->noteOn(2, 60, 0.9f, 0);
    renderGraph(*g, 96);
    g->noteOn(2, 62, 0.7f, 0);
    renderGraph(*g, 96);
    g->noteOn(0, 50, 1.0f, 0);  // drum key 2
    g->noteOn(0, 20, 1.0f, 0);  // not a kit key: neither played nor recorded
    renderGraph(*g, 96);
    std::vector<RecordedEvent> rec;
    RecordedEvent e;
    while (seq.popRecorded(e)) rec.push_back(e);
    ASSERT_EQ(rec.size(), 3u);
    EXPECT_EQ(rec[0].slot, 2);
    EXPECT_EQ(rec[0].step, 0);
    EXPECT_EQ(rec[0].noteOrTarget, 60);
    EXPECT_EQ(rec[1].step, 1);
    EXPECT_EQ(rec[2].slot, 0);
    EXPECT_EQ(rec[2].noteOrTarget, 50);
    EXPECT_TRUE(seq.pattern(2, 0).steps[0].hasNote(60));
}

TEST(AudioGraph, KeysPlayEverySlotAndAllNotesOffStopsThem) {
    auto g = std::make_unique<AudioGraph>();
    EXPECT_FALSE(g->noteOn(8, 60, 1.0f, 0));
    EXPECT_FALSE(g->noteOn(-1, 60, 1.0f, 0));
    EXPECT_FALSE(g->noteOn(1, 128, 1.0f, 0));
    for (int s = 1; s < 8; ++s) EXPECT_TRUE(g->noteOn(s, 60, 1.0f, 1000 + s));
    EXPECT_TRUE(g->noteOn(0, 48, 1.0f, 999));
    const auto x = renderGraph(*g, 4800);
    EXPECT_GT(peakAbs(x), 0.1f);
    int records = 0;
    NoteOnRender r;
    for (int s = 1; s < 8; ++s) {
        while (g->synth(s).popNoteOnRender(r)) {
            ++records;
            EXPECT_EQ(r.frameIndex, 0);
        }
    }
    EXPECT_EQ(records, 7);
    EXPECT_TRUE(g->allNotesOff());
    renderGraph(*g, 48000 * 2);
    EXPECT_EQ(peakAbs(renderGraph(*g, 4800)), 0.0f);
}

// Control thread (notes, params, commands, polling) against the audio thread: TSan checks it.
TEST(AudioGraph, ControlAndAudioThreadsRunConcurrently) {
    auto g = std::make_unique<AudioGraph>();
    Sequencer& seq = g->sequencer();
    std::atomic<bool> done{false};
    std::thread audio([&] {
        std::vector<float> out(2 * 192);
        while (!done.load()) g->render(out.data(), 192, 2);
    });
    seq.setRecordArmed(true);
    seq.setMotionRecording(true);
    seq.play();
    int recorded = 0;
    for (int i = 0; i < 2000; ++i) {
        const int slot = i % 8;
        const int note = slot == 0 ? 48 + i % 16 : 48 + i % 24;
        while (!g->noteOn(slot, note, 0.8f, i + 1)) std::this_thread::yield();
        while (!seq.setStep(slot, 0, i % 16, {Hit{note, 0.8f, Hit::kPatternGate, 0.7f}})) std::this_thread::yield();
        if (slot != 0) {
            g->synth(slot).setParam(ParamId::FltCutoff, 300.0f + static_cast<float>(i));
            seq.recordMotion(slot, static_cast<int>(ParamId::FltCutoff), 300.0f + static_cast<float>(i));
        } else {
            g->drums().setParam(i % 16, DrumParamId::Tone, 1000.0f);
        }
        while (!g->noteOff(slot, note)) std::this_thread::yield();
        if (i % 300 == 0) seq.setTempo(90.0f + static_cast<float>(i % 100));
        (void)seq.transport();
        RecordedEvent e;
        while (seq.popRecorded(e)) ++recorded;
        NoteOnRender r;
        for (int s = 1; s < 8; ++s) {
            while (g->synth(s).popNoteOnRender(r)) {
            }
        }
        while (g->drums().popNoteOnRender(r)) {
        }
    }
    done.store(true);
    audio.join();
    EXPECT_GT(recorded, 0);
}

// NativeEngine.stop()/start() closes and reopens the stream (prepare); the UI doesn't resend
// anything, so every slot's parameters, the drum params, tempo and patterns must survive.
TEST(AudioGraph, PrepareKeepsEverythingTheUiSet) {
    auto g = std::make_unique<AudioGraph>();
    g->synth(5).loadPreset(3);
    g->synth(2).setParam(ParamId::FltCutoff, 777.0f);
    g->drums().setParam(4, DrumParamId::Pitch, 5.0f);
    g->sequencer().setTempo(150.5f);
    const int note = 50;
    g->sequencer().setStep(6, 9, 10, {Hit{note, 0.3f, 0.7f, 0.4f, -0.2f}});
    g->applyPendingCommands();  // no stream yet: applied from the control thread
    g->prepare(48000.0f);
    g->prepare(44100.0f);
    EXPECT_EQ(g->synth(5).param(ParamId::FltCutoff), preset(3).values[index(ParamId::FltCutoff)]);
    EXPECT_FLOAT_EQ(g->synth(2).param(ParamId::FltCutoff), 777.0f);
    EXPECT_FLOAT_EQ(g->drums().param(4, DrumParamId::Pitch), 5.0f);
    EXPECT_NEAR(g->sequencer().transport().tempoBpm, 150.5f, 1e-4f);
    EXPECT_FLOAT_EQ(g->synth(2).tempo(), 150.5f);
    const Step& s = g->sequencer().pattern(6, 9).steps[10];
    ASSERT_TRUE(s.on());
    EXPECT_FLOAT_EQ(s.hits[0].probability, 0.4f);
    EXPECT_FLOAT_EQ(s.hits[0].nudge, -0.2f);
}
