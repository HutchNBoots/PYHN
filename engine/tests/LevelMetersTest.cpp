// Level meters (docs/plans/2026-10-16-v1-closeout.md, "Level meters"): per-chunk peak meters
// for the 12 mixer channels, the two FX returns and the master, with instant attack, a 300 ms
// exponential release and a clip flag held for 1 s, published through relaxed atomics.

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <memory>
#include <thread>
#include <vector>

#include "AllocationCounter.h"
#include "EngineTestUtil.h"
#include "groove/AudioGraph.h"
#include "groove/LevelMeters.h"

using namespace groove;
using namespace groove::test;

namespace {

constexpr int kChunk = AudioGraph::kChunkFrames;
using Peaks = std::array<float, LevelMeters::kCount>;
using Clips = std::array<bool, LevelMeters::kCount>;

// One meter fed `peak` (every other meter silent) for one chunk of `frames`.
void feed(LevelMeters& m, int meter, float peak, int32_t frames, bool clip = false) {
    Peaks p{};
    Clips c{};
    p[static_cast<std::size_t>(meter)] = peak;
    c[static_cast<std::size_t>(meter)] = clip;
    m.update(p.data(), c.data(), frames);
}

void renderChunks(AudioGraph& g, int chunks) {
    std::vector<float> out(static_cast<std::size_t>(kChunk) * 2);
    for (int c = 0; c < chunks; ++c) g.render(out.data(), kChunk, 2);
}

int chunksFor(float seconds, float rate = 48000.0f) {
    return static_cast<int>(std::lround(seconds * rate / static_cast<float>(kChunk)));
}

float peakOf(const float* x, int n = kChunk) {
    float p = 0.0f;
    for (int i = 0; i < n; ++i) p = std::max(p, std::fabs(x[i]));
    return p;
}

std::unique_ptr<AudioGraph> makeGraph(float rate = 48000.0f) {
    auto g = std::make_unique<AudioGraph>();
    for (int s = 1; s < kSlotCount; ++s) setCleanSine(g->synth(s));
    g->prepare(rate);
    return g;
}

}  // namespace

// --- the meter block alone ---

TEST(LevelMeters, StartsSilentAndRejectsBadIndices) {
    LevelMeters m;
    for (int i = 0; i < LevelMeters::kCount; ++i) {
        EXPECT_EQ(m.level(i), 0.0f);
        EXPECT_FALSE(m.clipped(i));
    }
    EXPECT_EQ(m.level(-1), 0.0f);
    EXPECT_EQ(m.level(LevelMeters::kCount), 0.0f);
    EXPECT_FALSE(m.clipped(LevelMeters::kCount));
    static_assert(LevelMeters::kChannels == kMixerChannelCount);
    static_assert(LevelMeters::kDelay == 12 && LevelMeters::kReverb == 13 && LevelMeters::kMaster == 14);
}

TEST(LevelMeters, InstantAttack) {
    LevelMeters m;
    feed(m, 3, 0.25f, 256);
    EXPECT_EQ(m.level(3), 0.25f);
    feed(m, 3, 0.75f, 256);
    EXPECT_EQ(m.level(3), 0.75f);  // a louder chunk shows at once
    EXPECT_EQ(m.level(2), 0.0f);
}

// The release is exponential with a 300 ms time constant: 1/e after 300 ms of silence,
// whatever the chunk sizes, at any sample rate.
TEST(LevelMeters, ReleasesWithA300msTimeConstant) {
    for (float rate : {44100.0f, 48000.0f, 96000.0f}) {
        LevelMeters m;
        m.prepare(rate);
        feed(m, 0, 1.0f, 256);
        int32_t frames = 0;
        const auto target = static_cast<int32_t>(std::lround(LevelMeters::kReleaseSeconds * rate));
        int i = 0;
        while (frames < target) {
            const int32_t n = std::min<int32_t>(target - frames, 64 + (i++ * 37) % 193);  // uneven chunks
            feed(m, 0, 0.0f, n);
            frames += n;
        }
        EXPECT_NEAR(m.level(0), std::exp(-1.0f), 1e-4f) << rate;
        // A quieter chunk than the falling level doesn't pull it down faster.
        feed(m, 0, 0.1f, 1);
        EXPECT_GT(m.level(0), 0.3f);
    }
}

TEST(LevelMeters, DecaysToExactZero) {
    LevelMeters m;
    feed(m, 14, 1.0f, 256);
    for (int i = 0; i < chunksFor(6.0f); ++i) feed(m, 14, 0.0f, 256);
    EXPECT_EQ(m.level(14), 0.0f);  // no denormal tail
}

TEST(LevelMeters, ClipIsHeldForOneSecond) {
    LevelMeters m;  // 48 kHz
    feed(m, 5, 1.2f, 256, true);
    EXPECT_TRUE(m.clipped(5));
    EXPECT_FALSE(m.clipped(4));
    int32_t frames = 0;
    while (frames + 256 < 48000) {
        feed(m, 5, 0.5f, 256);
        frames += 256;
        ASSERT_TRUE(m.clipped(5)) << frames;
    }
    feed(m, 5, 0.5f, 48000 - frames);
    EXPECT_FALSE(m.clipped(5));  // 1 s after the clipping chunk
    // A new clip restarts the hold.
    feed(m, 5, 1.0f, 256, true);
    EXPECT_TRUE(m.clipped(5));
}

TEST(LevelMeters, NonFiniteAndHugePeaksStayBounded) {
    LevelMeters m;
    feed(m, 1, std::nanf(""), 256);
    EXPECT_EQ(m.level(1), 0.0f);
    feed(m, 1, INFINITY, 256);
    EXPECT_EQ(m.level(1), LevelMeters::kMaxLevel);
    feed(m, 1, -1.0f, 256);  // not a peak: ignored
    EXPECT_LE(m.level(1), LevelMeters::kMaxLevel);
}

TEST(LevelMeters, ReadCopiesEveryMeterAndResetClears) {
    LevelMeters m;
    Peaks p{};
    Clips c{};
    for (int i = 0; i < LevelMeters::kCount; ++i) p[static_cast<std::size_t>(i)] = 0.05f * static_cast<float>(i + 1);
    c[7] = true;
    m.update(p.data(), c.data(), 256);
    Peaks levels{};
    Clips clips{};
    m.read(levels.data(), clips.data());
    for (int i = 0; i < LevelMeters::kCount; ++i) {
        EXPECT_EQ(levels[static_cast<std::size_t>(i)], p[static_cast<std::size_t>(i)]);
        EXPECT_EQ(clips[static_cast<std::size_t>(i)], i == 7);
    }
    m.reset();
    EXPECT_EQ(m.level(14), 0.0f);
    EXPECT_FALSE(m.clipped(7));
}

TEST(LevelMeters, UpdateNeverAllocates) {
    LevelMeters m;
    Peaks p{};
    Clips c{};
    AllocationScope scope;
    for (int i = 0; i < 1000; ++i) {
        p[static_cast<std::size_t>(i % LevelMeters::kCount)] = static_cast<float>(i % 13) * 0.1f;
        c[static_cast<std::size_t>(i % LevelMeters::kCount)] = i % 17 == 0;
        m.update(p.data(), c.data(), 1 + i % 256);
        (void)m.level(i % LevelMeters::kCount);
    }
    EXPECT_EQ(scope.count(), 0u);
}

// --- the meters in the graph ---

// A steady sine on one slot reads its post-fader peak on that channel only (max of L and R),
// and follows the fader.
TEST(LevelMetersGraph, SineReadsItsPostFaderPeakOnItsChannel) {
    for (int slot : {1, 4, 7}) {
        auto g = makeGraph();
        g->noteOn(slot, 69, 1.0f, 0);
        renderChunks(*g, chunksFor(0.5f));
        const float peak = std::max(peakOf(g->buses().slotPostL[slot]), peakOf(g->buses().slotPostR[slot]));
        ASSERT_GT(peak, 0.1f);
        const float level = g->meters().level(slot);
        EXPECT_GE(level, peak);
        EXPECT_NEAR(level, peak, 0.01f * peak) << slot;
        for (int ch = 0; ch < kMixerChannelCount; ++ch) {
            if (ch != slot) EXPECT_EQ(g->meters().level(ch), 0.0f) << ch;
        }
        EXPECT_EQ(g->meters().level(LevelMeters::kDelay), 0.0f);  // no sends
        EXPECT_EQ(g->meters().level(LevelMeters::kReverb), 0.0f);
        EXPECT_NEAR(g->meters().level(LevelMeters::kMaster), peak, 0.02f * peak);  // below the knee

        // -6 dB on the fader: half the level, once the release has caught up.
        g->channelParams().set(slot, ChannelParamId::Volume, -6.0f);
        renderChunks(*g, chunksFor(3.0f));
        EXPECT_NEAR(g->meters().level(slot), peak * std::pow(10.0f, -6.0f / 20.0f), 0.01f * peak);
    }
}

// Hard right: the left side is silent, the meter reads the right side (max of L/R).
TEST(LevelMetersGraph, PannedChannelReadsTheLouderSide) {
    auto g = makeGraph();
    g->channelParams().set(2, ChannelParamId::Pan, 1.0f);
    g->noteOn(2, 64, 1.0f, 0);
    renderChunks(*g, chunksFor(0.5f));
    EXPECT_EQ(peakOf(g->buses().slotPostL[2]), 0.0f);
    const float right = peakOf(g->buses().slotPostR[2]);
    ASSERT_GT(right, 0.1f);
    EXPECT_NEAR(g->meters().level(2), right, 0.01f * right);
}

TEST(LevelMetersGraph, MutedChannelDecaysToZero) {
    auto g = makeGraph();
    g->noteOn(3, 60, 1.0f, 0);
    renderChunks(*g, chunksFor(0.5f));
    const float before = g->meters().level(3);
    ASSERT_GT(before, 0.1f);
    g->channelParams().set(3, ChannelParamId::Mute, 1.0f);
    renderChunks(*g, chunksFor(0.3f));
    // 5 ms mute fade, then the 300 ms release: about 1/e of the level.
    EXPECT_LT(g->meters().level(3), 0.4f * before);
    EXPECT_GT(g->meters().level(3), 0.3f * before);
    renderChunks(*g, chunksFor(2.0f));
    EXPECT_LT(g->meters().level(3), 0.001f);  // below the display's -60 dB floor
    renderChunks(*g, chunksFor(3.0f));
    EXPECT_EQ(g->meters().level(3), 0.0f);
    EXPECT_EQ(g->meters().level(LevelMeters::kMaster), 0.0f);
}

TEST(LevelMetersGraph, FxReturnsAreMeteredPostReturnLevel) {
    auto g = makeGraph();
    g->channelParams().set(1, ChannelParamId::DelaySend, 1.0f);
    g->channelParams().set(1, ChannelParamId::ReverbSend, 1.0f);
    g->mixParams().set(MixParamId::ReverbReturn, 0.0f);
    g->noteOn(1, 60, 1.0f, 0);
    renderChunks(*g, chunksFor(1.0f));
    EXPECT_GT(g->meters().level(LevelMeters::kDelay), 0.05f);
    EXPECT_EQ(g->meters().level(LevelMeters::kReverb), 0.0f);  // return at 0
    g->mixParams().set(MixParamId::ReverbReturn, 1.0f);
    renderChunks(*g, chunksFor(0.5f));
    EXPECT_GT(g->meters().level(LevelMeters::kReverb), 0.01f);
}

TEST(LevelMetersGraph, TapeChannelsAreMetered) {
    auto g = makeGraph();
    g->sequencer().setTempo(240.0f);  // 1 s bars
    g->noteOn(5, 60, 1.0f, 0);
    g->sequencer().play();
    g->tape().armRecord(1);
    renderChunks(*g, chunksFor(2.1f));  // a take of at least one bar
    g->tape().stopRecord();
    g->noteOff(5, 60);
    g->channelParams().set(5, ChannelParamId::Mute, 1.0f);  // only the tape sounds now
    renderChunks(*g, chunksFor(1.5f));
    const float track = peakOf(g->buses().trackL[1]);
    ASSERT_GT(track, 0.05f);
    EXPECT_GT(g->meters().level(kFirstTapeChannel + 1), 0.05f);
    EXPECT_EQ(g->meters().level(kFirstTapeChannel + 0), 0.0f);
    EXPECT_EQ(g->meters().level(kFirstTapeChannel + 2), 0.0f);
}

// Master: metered after the limiter (never above the ceiling); clip means the mix reached full
// scale before the limiter. Channels clip at full scale post-fader. Both hold for 1 s.
TEST(LevelMetersGraph, ClipFlagsAndThePostLimiterMaster) {
    auto g = makeGraph();
    for (int s = 1; s < kSlotCount; ++s) {
        g->channelParams().set(s, ChannelParamId::Volume, 6.0f);
        g->channelParams().set(s, ChannelParamId::Pan, 1.0f);
        g->noteOn(s, 57 + s, 1.0f, 0);
    }
    g->mixParams().set(MixParamId::MasterVolume, 6.0f);
    renderChunks(*g, chunksFor(0.5f));
    const LevelMeters& m = g->meters();
    EXPECT_TRUE(m.clipped(LevelMeters::kMaster));
    EXPECT_LE(m.level(LevelMeters::kMaster), g->master().ceiling());
    EXPECT_GT(m.level(LevelMeters::kMaster), 0.5f);
    for (int s = 1; s < kSlotCount; ++s) {
        const float peak = peakOf(g->buses().slotPostR[s]);
        EXPECT_EQ(m.clipped(s), m.level(s) >= 1.0f) << s;
        EXPECT_GE(m.level(s), peak);
    }
    EXPECT_TRUE(m.clipped(1)) << "slot 1 at +6 dB, hard panned, reaches full scale (level " << m.level(1) << ")";
    EXPECT_FALSE(m.clipped(0));  // the silent drum slot

    // Quiet again: the flags stay up for about 1 s, then drop.
    for (int s = 1; s < kSlotCount; ++s) g->channelParams().set(s, ChannelParamId::Volume, -24.0f);
    g->mixParams().set(MixParamId::MasterVolume, 0.0f);
    renderChunks(*g, chunksFor(0.9f));
    EXPECT_TRUE(m.clipped(LevelMeters::kMaster));
    EXPECT_TRUE(m.clipped(1));
    renderChunks(*g, chunksFor(0.15f));
    EXPECT_FALSE(m.clipped(LevelMeters::kMaster));
    EXPECT_FALSE(m.clipped(1));
}

// prepare() (a new stream) clears the meters.
TEST(LevelMetersGraph, PrepareResets) {
    auto g = makeGraph();
    g->noteOn(1, 60, 1.0f, 0);
    renderChunks(*g, 20);
    ASSERT_GT(g->meters().level(1), 0.0f);
    g->prepare(48000.0f);
    EXPECT_EQ(g->meters().level(1), 0.0f);
    EXPECT_EQ(g->meters().level(LevelMeters::kMaster), 0.0f);
}

// The meters only change how they are read, never the sound: two identical graphs, one read
// constantly, render the same output.
TEST(LevelMetersGraph, ReadingNeverChangesTheOutput) {
    auto a = makeGraph();
    auto b = makeGraph();
    std::vector<float> oa(512), ob(512);
    for (int i = 0; i < 400; ++i) {
        if (i % 40 == 0) {
            a->noteOn(1 + i % 7, 60 + i % 12, 1.0f, 0);
            b->noteOn(1 + i % 7, 60 + i % 12, 1.0f, 0);
        }
        a->render(oa.data(), 256, 2);
        b->render(ob.data(), 256, 2);
        std::array<float, LevelMeters::kCount> l{};
        std::array<bool, LevelMeters::kCount> c{};
        b->meters().read(l.data(), c.data());
        ASSERT_EQ(oa, ob) << i;
    }
}

// The control thread polls the meters while the audio thread renders (run under TSan).
TEST(LevelMetersGraph, ControlThreadReadsWhileAudioThreadRenders) {
    auto g = makeGraph();
    g->channelParams().set(1, ChannelParamId::DelaySend, 0.5f);
    g->noteOn(1, 60, 1.0f, 0);
    g->noteOn(4, 67, 1.0f, 0);
    std::atomic<bool> done{false};
    std::atomic<int> nonZero{0};
    std::thread reader([&] {
        std::array<float, LevelMeters::kCount> l{};
        std::array<bool, LevelMeters::kCount> c{};
        while (!done.load(std::memory_order_acquire)) {
            g->meters().read(l.data(), c.data());
            if (l[1] > 0.0f && l[LevelMeters::kMaster] > 0.0f) nonZero.fetch_add(1, std::memory_order_relaxed);
            for (int i = 0; i < LevelMeters::kCount; ++i) {
                (void)g->meters().level(i);
                (void)g->meters().clipped(i);
            }
            std::this_thread::yield();
        }
    });
    std::thread audio([&] {
        std::vector<float> out(512);
        for (int i = 0; i < chunksFor(2.0f); ++i) g->render(out.data(), 96 + i % 160, 2);
        done.store(true, std::memory_order_release);
    });
    audio.join();
    reader.join();
    EXPECT_GT(nonZero.load(), 0);
}
