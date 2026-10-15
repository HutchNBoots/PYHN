#include "groove/SynthCore.h"

#include <gtest/gtest.h>

#include <atomic>
#include <cmath>
#include <thread>
#include <vector>

#include "AllocationCounter.h"

using groove::NoteOnRender;
using groove::SynthCore;
using groove::Waveform;

namespace {

constexpr float kRate = 48000.0f;
constexpr int kBlock = 128;

std::vector<float> renderMono(SynthCore& synth, int frames) {
    std::vector<float> out(static_cast<size_t>(frames));
    synth.render(out.data(), frames, 1);
    return out;
}

float peak(const std::vector<float>& x, size_t from = 0, size_t to = SIZE_MAX) {
    float p = 0.0f;
    for (size_t i = from; i < std::min(to, x.size()); ++i) p = std::max(p, std::fabs(x[i]));
    return p;
}

}  // namespace

class SynthCoreTest : public ::testing::Test {
protected:
    void SetUp() override { synth.prepare(kRate); }
    SynthCore synth;
};

TEST_F(SynthCoreTest, SilentWithoutNotes) {
    auto x = renderMono(synth, 4800);
    EXPECT_EQ(peak(x), 0.0f);
}

TEST_F(SynthCoreTest, NoteOnSoundsWithinFirstMillisecondOfNextBlock) {
    ASSERT_TRUE(synth.noteOn(69, 0));
    auto x = renderMono(synth, kBlock);
    EXPECT_GT(peak(x, 0, 48), 0.0f);
    EXPECT_GT(peak(x), 0.1f);
}

TEST_F(SynthCoreTest, NoteOffDecaysToExactSilence) {
    synth.noteOn(60, 0);
    renderMono(synth, 4800);
    synth.noteOff(60);
    auto x = renderMono(synth, 48000);
    EXPECT_EQ(peak(x, 24000), 0.0f);
}

TEST_F(SynthCoreTest, AllNotesOffSilencesEverything) {
    synth.noteOn(60, 0);
    synth.noteOn(64, 0);
    renderMono(synth, kBlock);
    synth.allNotesOff();
    auto x = renderMono(synth, 48000);
    EXPECT_EQ(peak(x, 24000), 0.0f);
}

TEST_F(SynthCoreTest, MeasuredNoteOnReportsStartFrame) {
    renderMono(synth, kBlock);
    renderMono(synth, kBlock);
    ASSERT_TRUE(synth.noteOn(60, 123456789));
    renderMono(synth, kBlock);

    NoteOnRender rec;
    ASSERT_TRUE(synth.popNoteOnRender(rec));
    EXPECT_EQ(rec.tapTimeNs, 123456789);
    EXPECT_EQ(rec.frameIndex, 2 * kBlock);
    EXPECT_FALSE(synth.popNoteOnRender(rec));
    EXPECT_EQ(synth.framesRendered(), 3 * kBlock);
}

TEST_F(SynthCoreTest, UnmeasuredEventsProduceNoRecord) {
    synth.noteOn(60, 0);
    synth.noteOff(60);
    renderMono(synth, kBlock);
    NoteOnRender rec;
    EXPECT_FALSE(synth.popNoteOnRender(rec));
}

TEST_F(SynthCoreTest, PrepareRestartsFrameCounter) {
    renderMono(synth, kBlock);
    synth.prepare(kRate);
    EXPECT_EQ(synth.framesRendered(), 0);
}

TEST_F(SynthCoreTest, LegatoNoteDoesNotRetriggerEnvelope) {
    synth.setWaveform(Waveform::Sine);
    synth.noteOn(60, 0);
    renderMono(synth, 4800);
    synth.noteOn(67, 0);
    auto x = renderMono(synth, 4800);
    // Envelope stays at sustain, so the new pitch is at full level from the first cycle.
    EXPECT_GT(peak(x, 0, 200), 0.4f);
}

TEST_F(SynthCoreTest, ReleasingTopNoteReturnsToHeldNote) {
    synth.setWaveform(Waveform::Sine);
    synth.noteOn(57, 0);  // 220 Hz
    synth.noteOn(69, 0);  // 440 Hz
    renderMono(synth, 4800);
    synth.noteOff(69);
    renderMono(synth, 480);  // let the gain settle
    auto x = renderMono(synth, 48000);
    int crossings = 0;
    for (size_t i = 1; i < x.size(); ++i) {
        if (x[i - 1] < 0.0f && x[i] >= 0.0f) ++crossings;
    }
    EXPECT_NEAR(crossings, 220, 2);
}

TEST_F(SynthCoreTest, DuplicatesSignalAcrossChannels) {
    synth.noteOn(60, 0);
    std::vector<float> out(kBlock * 2);
    synth.render(out.data(), kBlock, 2);
    for (int i = 0; i < kBlock; ++i) EXPECT_EQ(out[2 * i], out[2 * i + 1]);
}

TEST_F(SynthCoreTest, OutputRespectsGain) {
    synth.setGain(0.25f);
    renderMono(synth, 48000);  // settle gain smoothing
    synth.noteOn(60, 0);
    auto x = renderMono(synth, 48000);
    EXPECT_LT(peak(x), 0.25f * 1.15f);
    EXPECT_GT(peak(x), 0.2f);
}

TEST_F(SynthCoreTest, RenderNeverAllocates) {
    std::vector<float> out(kBlock * 2);
    groove::test::AllocationScope scope;
    for (int i = 0; i < 1000; ++i) {
        if (i % 10 == 0) synth.noteOn(48 + i % 24, i + 1);
        if (i % 10 == 5) synth.noteOff(48 + (i - 5) % 24);
        synth.render(out.data(), kBlock, 2);
        NoteOnRender rec;
        while (synth.popNoteOnRender(rec)) {
        }
    }
    EXPECT_EQ(scope.count(), 0u);
}

TEST_F(SynthCoreTest, ControlAndAudioThreadsRunConcurrently) {
    std::atomic<bool> done{false};
    std::thread audio([&] {
        std::vector<float> out(kBlock * 2);
        while (!done.load()) synth.render(out.data(), kBlock, 2);
    });
    int records = 0;
    for (int i = 0; i < 2000; ++i) {
        while (!synth.noteOn(60 + i % 12, i + 1)) std::this_thread::yield();
        while (!synth.noteOff(60 + i % 12)) std::this_thread::yield();
        NoteOnRender rec;
        while (synth.popNoteOnRender(rec)) ++records;
    }
    done.store(true);
    audio.join();
    NoteOnRender rec;
    while (synth.popNoteOnRender(rec)) ++records;
    EXPECT_GT(records, 0);
}
