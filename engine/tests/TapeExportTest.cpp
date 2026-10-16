// M4 WAV export (docs/feature-spec.md F7.2, F7.3): the audio thread snapshots the tape
// (exactly, even while it changes), a background thread renders it through the same code as
// the live graph, faster than real time, into a 24-bit stereo WAV with a correct header.

#include <gtest/gtest.h>

#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <vector>

#include "EngineTestUtil.h"
#include "TapeRig.h"
#include "TapeTestUtil.h"
#include "groove/AudioGraph.h"
#include "groove/TapeFiles.h"
#include "groove/TapeStore.h"

using namespace groove;
using namespace groove::test;

namespace {

constexpr int64_t kBar = 48000;  // 240 BPM at 48 kHz

struct LiveGraph {
    LiveGraph() : g(std::make_unique<AudioGraph>()) {
        for (int s = 1; s < kSlotCount; ++s) setCleanSine(g->synth(s));
        g->prepare(48000.0f);
        g->sequencer().setTempo(240.0f);
    }
    // Renders whole chunks; keeps the output when capture is on.
    void render(int64_t frames) {
        std::vector<float> out(2 * AudioGraph::kChunkFrames);
        for (int64_t done = 0; done < frames; done += AudioGraph::kChunkFrames) {
            g->render(out.data(), AudioGraph::kChunkFrames, 2);
            if (capture) {
                for (int i = 0; i < AudioGraph::kChunkFrames; ++i) {
                    left.push_back(out[2 * static_cast<std::size_t>(i)]);
                    right.push_back(out[2 * static_cast<std::size_t>(i) + 1]);
                }
            }
        }
    }
    // Two tracks: a first take of 2 bars on track 0, one overdub pass on track 1.
    void recordTwoTracks() {
        g->sequencer().play();
        g->tape().armRecord(0);
        g->noteOn(1, 60, 0.9f, 0);
        render(2 * kBar - 2000);
        g->tape().stopRecord();
        render(6000);
        g->noteOff(1, 60);
        g->noteOn(2, 67, 1.0f, 0);
        g->noteOn(4, 72, 1.0f, 0);
        g->tape().armRecord(1);
        render(3 * kBar);
        g->tape().stopRecord();
        render(2 * kBar + 2000);
        ASSERT_EQ(g->tape().state().lengthFrames, 2 * kBar);
        ASSERT_TRUE(g->tape().state().hasAudio[0]);
        ASSERT_TRUE(g->tape().state().hasAudio[1]);
    }
    // Only the tape reaches the master: every slot muted, FX returns at zero. The tape
    // channels and the master get non-trivial settings.
    void tapeOnly() {
        for (int ch = 0; ch < kSlotCount; ++ch) g->channelParams().set(ch, ChannelParamId::Mute, 1.0f);
        g->mixParams().set(MixParamId::DelayReturn, 0.0f);
        g->mixParams().set(MixParamId::ReverbReturn, 0.0f);
        g->channelParams().set(8, ChannelParamId::Volume, 5.0f);
        g->channelParams().set(8, ChannelParamId::Pan, -0.5f);
        g->channelParams().set(9, ChannelParamId::Volume, 4.5f);
        g->channelParams().set(9, ChannelParamId::Pan, 0.7f);
        g->mixParams().set(MixParamId::MasterVolume, 6.0f);  // into the limiter
    }
    TapeExportSettings settings() const {
        TapeExportSettings s;
        for (int t = 0; t < Tape::kTracks; ++t) {
            s.volumeDb[static_cast<std::size_t>(t)] = g->channelParams().get(8 + t, ChannelParamId::Volume);
            s.pan[static_cast<std::size_t>(t)] = g->channelParams().get(8 + t, ChannelParamId::Pan);
            s.mute[static_cast<std::size_t>(t)] = g->channelParams().get(8 + t, ChannelParamId::Mute) >= 0.5f;
        }
        s.masterDb = g->mixParams().get(MixParamId::MasterVolume);
        return s;
    }
    void waitExport() {
        ASSERT_TRUE(waitFor([&] { return g->exportProgress() == 1.0f || g->exportProgress() < 0.0f; }, 60000,
                            [&] { render(AudioGraph::kChunkFrames); }));
        ASSERT_EQ(g->exportProgress(), 1.0f);
    }

    std::unique_ptr<AudioGraph> g;
    bool capture = false;
    std::vector<float> left, right;
};

struct Wav {
    uint32_t rate = 0;
    uint16_t channels = 0, bits = 0, format = 0;
    int64_t frames = 0;
    std::vector<int32_t> samples;  // interleaved
};

uint32_t le32(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24); }
uint16_t le16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }

Wav readWav(const std::string& path) {
    const auto b = readAll(path);
    Wav w;
    EXPECT_GE(b.size(), 44u);
    if (b.size() < 44) return w;
    EXPECT_EQ(std::memcmp(b.data(), "RIFF", 4), 0);
    EXPECT_EQ(le32(b.data() + 4), b.size() - 8);
    EXPECT_EQ(std::memcmp(b.data() + 8, "WAVEfmt ", 8), 0);
    EXPECT_EQ(le32(b.data() + 16), 16u);
    w.format = le16(b.data() + 20);
    w.channels = le16(b.data() + 22);
    w.rate = le32(b.data() + 24);
    w.bits = le16(b.data() + 34);
    EXPECT_EQ(le32(b.data() + 28), w.rate * w.channels * w.bits / 8);
    EXPECT_EQ(le16(b.data() + 32), w.channels * w.bits / 8);
    EXPECT_EQ(std::memcmp(b.data() + 36, "data", 4), 0);
    const uint32_t dataBytes = le32(b.data() + 40);
    EXPECT_EQ(dataBytes, b.size() - 44);
    w.frames = dataBytes / 6;
    for (std::size_t i = 44; i + 3 <= b.size(); i += 3) {
        int32_t v = b[i] | (b[i + 1] << 8) | (b[i + 2] << 16);
        if (v & 0x800000) v -= 0x1000000;
        w.samples.push_back(v);
    }
    return w;
}

}  // namespace

// F7.3: the offline render of a tape equals a live capture of the graph's tape-only output
// with the same settings, sample for sample: before quantisation (the renderer against the
// live floats) and in the WAV file (against the live floats quantised the same way). The
// live path runs with every slot muted and the FX returns at zero, a loop region of one bar,
// panned and levelled tape channels and the master pushed into the limiter.
TEST(TapeExport, OfflineRenderIsBitIdenticalToTheLiveTapeOutput) {
    TempDir dir;
    LiveGraph live;
    live.recordTwoTracks();
    live.tapeOnly();
    live.g->mixParams().set(MixParamId::TapeLoopStart, 1.0f);
    live.g->mixParams().set(MixParamId::TapeLoopLength, 1.0f);
    live.g->sequencer().stop();
    live.render(2560);
    live.g->sequencer().play();
    const int64_t start = live.g->framesRendered();
    live.capture = true;
    live.render(5 * kBar);
    live.capture = false;
    ASSERT_EQ(live.g->sequencer().playFrame(), start);
    const auto pass3 = static_cast<std::size_t>(3 * kBar);  // steady looping: the third pass

    // Before quantisation: snapshot the tape through the audio thread, render offline.
    std::vector<int16_t> data(static_cast<std::size_t>(4 * (2 * kBar + 1000)) * 2);
    std::vector<uint8_t> copied(4 * (2 * kBar / TapeSnapshot::kBlockFrames + 4));
    TapeSnapshot snap;
    snap.data = data.data();
    snap.capacityFrames = 4 * (2 * kBar + 1000);
    snap.copied = copied.data();
    snap.blockCapacity = static_cast<int64_t>(copied.size());
    ASSERT_TRUE(live.g->tape().requestSnapshot(&snap));
    ASSERT_TRUE(waitFor([&] { return live.g->tape().snapshotPhase() == TapeSnapshotPhase::Ready; }, 10000,
                        [&] { live.render(AudioGraph::kChunkFrames); }));
    EXPECT_EQ(snap.loopStart, kBar);
    EXPECT_EQ(snap.loopEnd, 2 * kBar);
    TapeExportRenderer renderer(snap, live.settings(), AudioGraph::kPanMakeup);
    ASSERT_EQ(renderer.passFrames(), kBar);
    std::vector<float> l(static_cast<std::size_t>(kBar)), r(static_cast<std::size_t>(kBar));
    renderer.render(l.data(), r.data(), static_cast<int32_t>(kBar));  // priming pass
    renderer.render(l.data(), r.data(), static_cast<int32_t>(kBar));
    live.g->tape().releaseSnapshot();
    int64_t mismatches = 0;
    float peak = 0.0f;
    for (std::size_t i = 0; i < l.size(); ++i) {
        peak = std::max({peak, std::fabs(live.left[pass3 + i]), std::fabs(live.right[pass3 + i])});
        if ((l[i] != live.left[pass3 + i] || r[i] != live.right[pass3 + i]) && ++mismatches < 5) {
            ADD_FAILURE() << "frame " << i << ": " << l[i] << " vs " << live.left[pass3 + i];
        }
    }
    EXPECT_EQ(mismatches, 0);
    EXPECT_GT(peak, std::pow(10.0f, MasterBus::kKneeDb / 20.0f)) << "the limiter was shaping the signal";

    // The WAV: the same samples, quantised to 24 bits.
    const std::string path = dir.file("loop.wav");
    ASSERT_TRUE(live.g->startExport(path, false));
    live.waitExport();
    const Wav w = readWav(path);
    EXPECT_EQ(w.frames, kBar);
    ASSERT_EQ(w.samples.size(), static_cast<std::size_t>(2 * kBar));
    mismatches = 0;
    for (std::size_t i = 0; i < static_cast<std::size_t>(kBar); ++i) {
        if (w.samples[2 * i] != tapefile::toPcm24(live.left[pass3 + i]) ||
            w.samples[2 * i + 1] != tapefile::toPcm24(live.right[pass3 + i])) {
            ++mismatches;
        }
    }
    EXPECT_EQ(mismatches, 0);
}

// F7.2: header, length, progress and the start rules (empty tape, one at a time, progress 0
// as soon as startExport returns true, 1 once the file is closed, NaN before any export).
TEST(TapeExport, WritesA24BitStereoWavOfTheLoopOrTheWholeTape) {
    TempDir dir;
    LiveGraph live;
    EXPECT_TRUE(std::isnan(live.g->exportProgress()));
    EXPECT_FALSE(live.g->startExport(dir.file("empty.wav"), true)) << "empty tape";
    live.recordTwoTracks();
    live.g->mixParams().set(MixParamId::TapeLoopLength, 1.0f);
    live.render(2560);

    ASSERT_TRUE(live.g->startExport(dir.file("whole.wav"), true));
    EXPECT_EQ(live.g->exportProgress(), 0.0f);
    EXPECT_FALSE(live.g->startExport(dir.file("again.wav"), true)) << "one export at a time";
    live.waitExport();
    const Wav whole = readWav(dir.file("whole.wav"));
    EXPECT_EQ(whole.format, 1);
    EXPECT_EQ(whole.channels, 2);
    EXPECT_EQ(whole.bits, 24);
    EXPECT_EQ(whole.rate, 48000u);
    EXPECT_EQ(whole.frames, 2 * kBar);
    EXPECT_EQ(tapefile::fileSize(dir.file("whole.wav")), 44 + 2 * kBar * 6);
    EXPECT_LT(tapefile::fileSize(dir.file("whole.wav.part")), 0);
    int32_t peak = 0;
    for (int32_t v : whole.samples) peak = std::max(peak, std::abs(v));
    EXPECT_GT(peak, 1000000);
    EXPECT_LE(peak, static_cast<int32_t>(8388607 * std::pow(10.0, -0.3 / 20.0)) + 1) << "F0.6 ceiling";

    // A new export resets the finished one's 1.0 to 0 before it returns.
    ASSERT_EQ(live.g->exportProgress(), 1.0f);
    ASSERT_TRUE(live.g->startExport(dir.file("loop.wav"), false));
    EXPECT_EQ(live.g->exportProgress(), 0.0f);
    live.waitExport();
    EXPECT_EQ(readWav(dir.file("loop.wav")).frames, kBar);
}

// F7.2: with no stream the snapshot is taken on the calling thread; a bad path fails with -1
// (and the next export still works).
TEST(TapeExport, WorksWithNoStreamAndFailsCleanly) {
    TempDir dir;
    LiveGraph live;
    live.recordTwoTracks();
    const auto pump = [&] { live.g->applyPendingCommands(); };
    ASSERT_TRUE(live.g->startExport(dir.file("nope/missing/dir/x.wav"), true, pump));
    ASSERT_TRUE(waitFor([&] { return live.g->exportProgress() != 0.0f && !live.g->tapeStore().exportRunning(); }));
    EXPECT_EQ(live.g->exportProgress(), -1.0f);
    ASSERT_TRUE(live.g->startExport(dir.file("ok.wav"), true, pump));
    ASSERT_TRUE(waitFor([&] { return !live.g->tapeStore().exportRunning(); }));
    EXPECT_EQ(live.g->exportProgress(), 1.0f);
    EXPECT_EQ(readWav(dir.file("ok.wav")).frames, 2 * kBar);
}

// F7.2: faster than real time (the background render of a 16-bar, 4-track tape at 1 s a bar).
TEST(TapeExport, RendersFasterThanRealTime) {
    TempDir dir;
    Rig r({}, kBar);
    TapeStore store(*r.tape);
    r.play();
    r.tape->armRecord(0);
    r.runTo(16 * kBar - 100, 256);
    r.tape->stopRecord();
    r.runTo(16 * kBar + 2000, 256);
    for (int t = 1; t < Tape::kTracks; ++t) {
        r.tape->armRecord(t);
        r.run(17 * kBar, 256);
        r.tape->stopRecord();
        r.run(kBar + 2000, 256);
    }
    for (bool h : r.state().hasAudio) ASSERT_TRUE(h);
    ASSERT_EQ(r.state().lengthFrames, 16 * kBar);
    const auto t0 = std::chrono::steady_clock::now();
    ASSERT_TRUE(store.startExport(dir.file("long.wav"), true, TapeExportSettings{}, [&] { r.tape->applyPendingOffline(); }));
    ASSERT_TRUE(waitFor([&] { return !store.exportRunning(); }, 120000));
    const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    ASSERT_EQ(store.exportProgress(), 1.0f);
    EXPECT_EQ(readWav(dir.file("long.wav")).frames, 16 * kBar);
    std::printf("[ export ] 16 s of 4-track tape in %.3f s (%.0fx real time)\n", seconds, 16.0 / seconds);
    EXPECT_LT(seconds, 16.0);
}

// The snapshot is the tape as it was when the copy started, though a take and an undo change
// the tape while it is copied (copy-on-write).
TEST(TapeExport, SnapshotIsExactWhileTheTapeChanges) {
    Rig r({}, kBar);
    r.play();
    r.tape->armRecord(0);
    r.runTo(4 * kBar - 100);
    r.tape->stopRecord();
    r.runTo(4 * kBar + 2000);
    r.tape->armRecord(1);
    r.runTo(6 * kBar + 1000);
    r.tape->stopRecord();
    r.runTo(8 * kBar + 2000);
    // An overdub pass on track 0, then its undo: the undo is swapping track 0 back (a little
    // per piece) when the snapshot starts.
    r.signal = [](int s, int64_t f, int c) { return 0.7f * noiseAt(s + 5, f, c); };
    r.tape->armRecord(0);
    r.run(5 * kBar, 256);
    r.tape->stopRecord();
    r.run(kBar + 2000, 256);
    ASSERT_EQ(r.state().recordingTrack, -1);
    r.tape->undo();
    r.run(8);
    ASSERT_TRUE(r.tape->busy()) << "the undo is under way";
    const int64_t len = r.state().lengthFrames;
    const int64_t x = r.tape->crossfadeFrames();
    std::array<std::vector<float>, 2> expected;
    for (int t = 0; t < 2; ++t) {
        for (int64_t f = -x; f < len; ++f) expected[static_cast<std::size_t>(t)].push_back(r.tape->sample(t, f, 0));
    }
    std::vector<int16_t> data(static_cast<std::size_t>(2 * (len + x + 1)) * 2);
    std::vector<uint8_t> copied(static_cast<std::size_t>(2 * ((len + x) / TapeSnapshot::kBlockFrames + 2)));
    TapeSnapshot snap;
    snap.data = data.data();
    snap.capacityFrames = 2 * (len + x + 1);
    snap.copied = copied.data();
    snap.blockCapacity = static_cast<int64_t>(copied.size());
    snap.wholeTape = true;
    ASSERT_TRUE(r.tape->requestSnapshot(&snap));
    int runs = 0;
    while (r.tape->snapshotPhase() != TapeSnapshotPhase::Ready) {
        ASSERT_NE(r.tape->snapshotPhase(), TapeSnapshotPhase::Failed);
        r.run(8);
        ++runs;
    }
    EXPECT_GT(runs, 3) << "copied over several pieces";
    ASSERT_EQ(snap.trackCount, 2);
    EXPECT_EQ(snap.rangeStart, -snap.fade);
    int64_t changed = 0, mismatches = 0;
    for (int t = 0; t < 2; ++t) {
        for (int64_t f = snap.rangeStart; f < len; ++f) {
            const float want = expected[static_cast<std::size_t>(t)][static_cast<std::size_t>(f + x)];
            if (r.tape->sample(t, f, 0) != want) ++changed;
            if (Tape::tapeToFloat(snap.frame(snap.slotOfTrack[static_cast<std::size_t>(t)], f)[0]) != want) ++mismatches;
        }
    }
    EXPECT_GT(changed, 10000) << "the tape really changed during the copy";
    EXPECT_EQ(mismatches, 0);
    r.tape->releaseSnapshot();
    EXPECT_EQ(r.tape->snapshotPhase(), TapeSnapshotPhase::Idle);
}

// TSan: the exporter thread against the audio thread (snapshot copy-on-write under takes and
// edits) and repeated exports from the control thread.
TEST(TapeExport, ExporterAndAudioThreadRunConcurrently) {
    TempDir dir;
    Tape::Limits small;
    small.maxSeconds = 4.0;
    small.budgetBytes = 20000000;
    Rig r(small, 4800);
    TapeStore store(*r.tape);
    r.play();
    r.tape->armRecord(0);
    r.runTo(4 * 4800 - 100);
    r.tape->stopRecord();
    r.runTo(4 * 4800 + 1000);
    std::atomic<bool> done{false};
    std::thread audio([&] {
        while (!done.load()) {
            r.run(96);
            if (r.played[0].size() > 100000) {
                for (auto& p : r.played) p.clear();
            }
        }
    });
    int ok = 0;
    for (int i = 0; i < 30; ++i) {
        while (!r.tape->armRecord(1 + i % 3)) std::this_thread::yield();
        if (store.startExport(dir.file("t" + std::to_string(i % 3) + ".wav"), i % 2 == 0, TapeExportSettings{}, {})) {
            while (store.exportRunning()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
            ok += store.exportProgress() == 1.0f ? 1 : 0;
        }
        while (!r.tape->stopRecord()) std::this_thread::yield();
        if (i % 5 == 4) while (!r.tape->undo()) std::this_thread::yield();
    }
    done.store(true);
    audio.join();
    EXPECT_GT(ok, 20);
}
