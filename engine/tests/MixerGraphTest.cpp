// M3 graph (docs/plans/2026-10-16-m3-tape-mixer-fx.md): slots -> channel strips -> slot mix
// (+ FX returns) -> tape input; tape tracks -> channel strips -> master. Tests check what each
// block receives (send buses, per-slot post-fader outputs, the tape's tracks), the tape's
// link to the sequencer transport (F5.1, F5.6), and the parameter stores.

#include <gtest/gtest.h>

#include <cmath>
#include <memory>
#include <vector>

#include "Analysis.h"
#include "EngineTestUtil.h"
#include "groove/AudioGraph.h"

using namespace groove;
using namespace groove::test;

namespace {

constexpr int kChunk = AudioGraph::kChunkFrames;

// Renders whole chunks so buses() describes the last one.
std::vector<float> renderChunks(AudioGraph& g, int chunks) {
    std::vector<float> out(static_cast<std::size_t>(kChunk) * 2), left;
    for (int c = 0; c < chunks; ++c) {
        g.render(out.data(), kChunk, 2);
        for (int i = 0; i < kChunk; ++i) left.push_back(out[2 * static_cast<std::size_t>(i)]);
    }
    return left;
}

float peakOf(const float* x, int n = kChunk) {
    float p = 0.0f;
    for (int i = 0; i < n; ++i) p = std::max(p, std::fabs(x[i]));
    return p;
}

std::unique_ptr<AudioGraph> makeGraph() {
    auto g = std::make_unique<AudioGraph>();
    for (int s = 1; s < kSlotCount; ++s) {
        setCleanSine(g->synth(s));
        g->synth(s).setParam(ParamId::AmpSustain, 1.0f);
    }
    g->prepare(48000.0f);
    return g;
}

// Every step of a slot's pattern 0 plays `note` (synth slots).
void fillPattern(AudioGraph& g, int slot, int length, int note) {
    g.sequencer().setPatternSettings(slot, 0, length, 1, 0.0f, 0.9f);
    for (int i = 0; i < length; ++i) g.sequencer().setStep(slot, 0, i, {Hit{note, 0.9f}});
}

}  // namespace

// F6.3: each slot's sends reach the delay and reverb buses (post-fader); nothing else does.
TEST(MixerGraph, SendsReachTheFxBuses) {
    auto g = makeGraph();
    g->channelParams().set(2, ChannelParamId::DelaySend, 1.0f);
    g->channelParams().set(5, ChannelParamId::ReverbSend, 1.0f);
    g->noteOn(2, 60, 1.0f, 0);
    renderChunks(*g, 20);
    EXPECT_GT(peakOf(g->buses().delaySendL), 0.05f);
    EXPECT_EQ(peakOf(g->buses().reverbSendL), 0.0f);
    g->noteOn(5, 64, 1.0f, 0);
    renderChunks(*g, 20);
    EXPECT_GT(peakOf(g->buses().reverbSendL), 0.05f);
    // Post-fader: the channel's volume at -60 (silence) empties its send.
    g->channelParams().set(2, ChannelParamId::Volume, -60.0f);
    renderChunks(*g, 20);
    EXPECT_EQ(peakOf(g->buses().delaySendL), 0.0f);
}

// F6.4: volume and mute reach the slot's channel strip; other slots are unaffected.
TEST(MixerGraph, VolumeAndMuteReachTheSlotChannel) {
    auto g = makeGraph();
    g->noteOn(1, 60, 1.0f, 0);
    g->noteOn(3, 67, 1.0f, 0);
    renderChunks(*g, 20);
    const float level1 = peakOf(g->buses().slotPostL[1]);
    const float level3 = peakOf(g->buses().slotPostL[3]);
    ASSERT_GT(level1, 0.05f);
    g->channelParams().set(1, ChannelParamId::Mute, 1.0f);
    g->channelParams().set(3, ChannelParamId::Volume, -6.0f);
    renderChunks(*g, 20);
    EXPECT_EQ(peakOf(g->buses().slotPostL[1]), 0.0f);
    EXPECT_NEAR(peakOf(g->buses().slotPostL[3]) / level3, std::pow(10.0f, -6.0f / 20.0f), 0.02f);
    EXPECT_EQ(peakOf(g->buses().slotPostL[2]), 0.0f);
}

TEST(MixerGraph, MasterVolumeAtMinimumIsSilence) {
    auto g = makeGraph();
    g->noteOn(4, 60, 1.0f, 0);
    EXPECT_GT(peakAbs(renderChunks(*g, 20)), 0.05f);
    g->mixParams().set(MixParamId::MasterVolume, -60.0f);
    renderChunks(*g, 2);  // smoothed over a chunk
    EXPECT_EQ(peakAbs(renderChunks(*g, 10)), 0.0f);
}

TEST(MixerGraph, ParameterStoresSanitizeAndValidate) {
    AudioGraph g;
    g.mixParams().set(MixParamId::DelayFeedback, 5.0f);
    EXPECT_FLOAT_EQ(g.mixParams().get(MixParamId::DelayFeedback), 0.95f);
    g.mixParams().set(MixParamId::TapeSpeed, 1.4f);
    EXPECT_FLOAT_EQ(g.mixParams().get(MixParamId::TapeSpeed), 1.0f);
    g.mixParams().set(MixParamId::TapeDub, std::nanf(""));
    EXPECT_FLOAT_EQ(g.mixParams().get(MixParamId::TapeDub), 1.0f);
    g.channelParams().set(11, ChannelParamId::Pan, -3.0f);
    EXPECT_FLOAT_EQ(g.channelParams().get(11, ChannelParamId::Pan), -1.0f);
    g.channelParams().set(12, ChannelParamId::Pan, 0.5f);  // ignored
    EXPECT_EQ(g.channelParams().get(12, ChannelParamId::Pan), 0.0f);
    EXPECT_FLOAT_EQ(g.channelParams().get(0, ChannelParamId::Volume), 0.0f);  // default 0 dB
}

// F5 decision: the tape records the slot mix with the FX returns; its playback is dry (tape
// channels have no sends) and goes through the tape channel strips.
TEST(MixerGraph, TapeRecordsTheMixWithFxAndPlaysBackDryThroughItsChannel) {
    auto g = makeGraph();
    g->channelParams().set(2, ChannelParamId::ReverbSend, 1.0f);
    g->mixParams().set(MixParamId::ReverbDecay, 3.0f);
    g->sequencer().setTempo(120.0f);
    g->sequencer().play();
    g->tape().armRecord(0);  // starts at Play
    g->noteOn(2, 60, 1.0f, 0);
    renderChunks(*g, 40);
    g->noteOff(2, 60);
    renderChunks(*g, 40);  // release (10 ms) long gone; the reverb tail continues
    const int64_t afterRelease = 70 * kChunk;
    g->tape().stopRecord();
    renderChunks(*g, 96000 / kChunk + 10);  // the take ends at the bar line (1 bar)
    ASSERT_EQ(g->tape().state().lengthBars, 1);
    float tail = 0.0f;
    for (int64_t k = afterRelease; k < afterRelease + 2000; ++k) tail = std::max(tail, std::fabs(g->tape().sample(0, k, 0)));
    EXPECT_GT(tail, 1e-3f) << "the reverb return is on tape";

    // Playback: tape channel 8 only; slot sends stay silent while only the tape plays.
    g->channelParams().set(2, ChannelParamId::ReverbSend, 0.0f);
    g->channelParams().set(8, ChannelParamId::ReverbSend, 1.0f);  // ignored on tape channels
    g->channelParams().set(8, ChannelParamId::DelaySend, 1.0f);
    renderChunks(*g, 400);
    EXPECT_GT(peakOf(g->buses().trackL[0]), 0.0f);
    float sends = 0.0f;
    for (int i = 0; i < 40; ++i) {
        renderChunks(*g, 1);
        sends = std::max({sends, peakOf(g->buses().delaySendL), peakOf(g->buses().reverbSendL)});
    }
    EXPECT_EQ(sends, 0.0f);
    // The tape channel's mute silences the master.
    g->channelParams().set(8, ChannelParamId::Mute, 1.0f);
    g->mixParams().set(MixParamId::ReverbReturn, 0.0f);
    renderChunks(*g, 400);
    EXPECT_EQ(peakAbs(renderChunks(*g, 40)), 0.0f);
}

// F5.1 with the real sequencer: an armed first take starts on the next bar line (4 beats at
// the tempo), and the tape keeps that bar length.
TEST(MixerGraph, FirstTakeFollowsTheSequencerBars) {
    auto g = makeGraph();
    g->sequencer().setTempo(133.7f);
    g->sequencer().play();
    renderChunks(*g, 20);  // > 50 ms after Play
    g->tape().armRecord(2);
    const int64_t bar1 = (2400LL * 48000 + 1336) / 1337;
    while (g->framesRendered() + kChunk <= bar1) renderChunks(*g, 1);
    EXPECT_EQ(g->tape().state().armedTrack, 2);
    renderChunks(*g, 2);
    EXPECT_EQ(g->tape().state().recordingTrack, 2);
    EXPECT_EQ(g->tape().state().barFrames, (2LL * 2400 * 48000 + 1337) / (2 * 1337));  // rounded
    g->tape().stopRecord();
    renderChunks(*g, static_cast<int>(bar1 / kChunk) + 10);
    const TapeState s = g->tape().state();
    EXPECT_EQ(s.lengthBars, 1);
    EXPECT_EQ(s.lengthFrames, s.barFrames);
}

// F5.6: a commit of one slot records only that slot's channel. Build-20: a pattern that isn't
// whole bars long is recorded for ceil(length / bar) whole bars while it keeps looping, so
// the tape has no silent tail.
TEST(MixerGraph, CommitOfOneSlotRecordsOnlyThatSlotForWholeBars) {
    auto g = makeGraph();
    g->sequencer().setTempo(120.0f);
    fillPattern(*g, 1, 12, 60);  // 12 sixteenths = 72000 frames, so one bar of 96000
    fillPattern(*g, 4, 16, 72);
    g->channelParams().set(4, ChannelParamId::Volume, 6.0f);  // loud, but not the source
    g->sequencer().play();
    g->tape().commit(3, 1);
    renderChunks(*g, 2 * 96000 / kChunk);
    const TapeState s = g->tape().state();
    ASSERT_EQ(s.lengthBars, 1);
    EXPECT_EQ(s.recordingTrack, -1);
    // Tape audio is slot 1's post-fader output for the whole bar (compare with a solo render),
    // including the pattern's second pass from 72000 on.
    auto solo = makeGraph();
    solo->sequencer().setTempo(120.0f);
    fillPattern(*solo, 1, 12, 60);
    solo->sequencer().play();
    const auto ref = renderChunks(*solo, 96000 / kChunk + 4);
    // The solo master output is slot 1 alone: same as its post-fader channel (below the limiter's knee).
    double err = 0.0, ref2 = 0.0, tail2 = 0.0;
    for (int64_t k = 100; k < 96000 - 100; ++k) {
        const double d = g->tape().sample(3, k, 0) - ref[static_cast<std::size_t>(k)];
        err += d * d;
        ref2 += static_cast<double>(ref[static_cast<std::size_t>(k)]) * ref[static_cast<std::size_t>(k)];
        if (k >= 72000 + 100) tail2 += static_cast<double>(g->tape().sample(3, k, 0)) * g->tape().sample(3, k, 0);
    }
    EXPECT_GT(ref2, 1.0);
    EXPECT_GT(tail2, 0.1);  // the second pass is on the tape
    EXPECT_LT(err / ref2, 1e-6);
}

// Build-20: on a tape that already has bars, a slot commit of a 20-step pattern records two
// whole bars, not 1.25.
TEST(MixerGraph, CommitOnALongerTapeRecordsWholeBars) {
    auto g = makeGraph();
    g->sequencer().setTempo(120.0f);
    fillPattern(*g, 2, 20, 64);
    fillPattern(*g, 3, 64, 67);
    g->sequencer().play();
    g->tape().commit(0, 3);  // 4 bars
    renderChunks(*g, 4 * 96000 / kChunk + 8);
    ASSERT_EQ(g->tape().state().lengthBars, 4);
    ASSERT_EQ(g->tape().state().recordingTrack, -1);

    g->tape().commit(1, 2);  // 20 sixteenths: two whole bars
    int recorded = 0;
    for (int c = 0; c < 6 * 96000 / kChunk; ++c) {
        renderChunks(*g, 1);
        if (g->tape().state().recordingTrack == 1) ++recorded;
    }
    EXPECT_NEAR(recorded * kChunk, 2 * 96000, 2 * kChunk);
}

// Build-20: "all slots" commits the longest pattern among the slots, rounded up to whole bars.
TEST(MixerGraph, CommitOfAllSlotsUsesTheLongestPatternInWholeBars) {
    auto g = makeGraph();
    g->sequencer().setTempo(120.0f);
    fillPattern(*g, 1, 12, 60);
    fillPattern(*g, 2, 20, 64);  // the longest: 1.25 bars, so 2
    g->sequencer().play();
    g->tape().commit(0, -1);
    renderChunks(*g, 3 * 96000 / kChunk + 8);
    const TapeState s = g->tape().state();
    EXPECT_EQ(s.recordingTrack, -1);
    EXPECT_EQ(s.lengthBars, 2);
}

// Coordinator rule: play() and tapeCommit() sent together (in either queue order and even a
// callback apart) start the commit at the pattern start, not one pattern later.
TEST(MixerGraph, PlayAndCommitTogetherStartAtThePatternStart) {
    for (int gap = 0; gap < 3; ++gap) {
        auto g = makeGraph();
        fillPattern(*g, 1, 16, 60);
        renderChunks(*g, 4);
        g->sequencer().play();
        if (gap > 0) renderChunks(*g, gap);
        g->tape().commit(0, -1);
        renderChunks(*g, 8);
        EXPECT_EQ(g->tape().state().recordingTrack, 0) << gap;
        renderChunks(*g, 96000 / kChunk + 10);
        EXPECT_EQ(g->tape().state().lengthBars, 1) << gap;
        // The note at step 0 is at tape frame 0: the take began at Play.
        float early = 0.0f;
        for (int64_t k = 0; k < 200; ++k) early = std::max(early, std::fabs(g->tape().sample(0, k, 0)));
        EXPECT_GT(early, 0.01f) << gap;
    }
}

// The sequencer side of the link: bar lines and pattern passes are exact.
TEST(MixerGraph, SequencerBarLinesAndPatternPassesAreExact) {
    auto g = makeGraph();
    Sequencer& seq = g->sequencer();
    seq.setTempo(133.7f);
    seq.setPatternSettings(2, 0, 12, 1, 0.0f, 0.5f);
    seq.setPatternSettings(2, 1, 20, 3, 0.0f, 0.5f);
    seq.play();
    renderChunks(*g, 1);
    const int64_t barUnits = seq.barUnits();
    EXPECT_EQ(seq.playFrame(), 0);
    // Bar k at ceil(k * 2400 * 48000 / 1337).
    for (int64_t k : {1, 2, 7}) {
        const int64_t expect = (k * 2400 * 48000 + 1336) / 1337;
        EXPECT_EQ(seq.nextBarFrame(expect - 5), expect);
        EXPECT_EQ(seq.nextBarFrame(expect), expect);
        EXPECT_EQ(seq.nextBarFrame(expect + 1), ((k + 1) * 2400 * 48000 + 1336) / 1337);
    }
    // Slot 2: 12 sixteenths; the next pass starts at step 12's frame. Queue pattern 1 (20 triplets).
    Sequencer::PatternPass p = seq.nextPatternPass(2);
    EXPECT_EQ(p.startPosition, 12 * barUnits / 16);
    EXPECT_EQ(p.units, 12 * barUnits / 16);
    EXPECT_EQ(seq.currentPatternPass(2).startPosition, 0);
    seq.selectPattern(2, 1);
    renderChunks(*g, 1);
    p = seq.nextPatternPass(2);
    EXPECT_EQ(p.units, 20 * seq.unitsPerBeat() / 6);
    EXPECT_EQ(p.startFrame, (12 * 600 * 48000LL + 4 * 1337 - 1) / (4 * 1337));  // step 12's frame
}
