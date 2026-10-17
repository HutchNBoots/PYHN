// M4 tape persistence and load (docs/feature-spec.md F7.5, F7.6): the writer thread keeps
// the files in sync with the tape through the lock-free ring, flush() makes it durable, the
// files always load as a consistent tape, a load lands incrementally (or at once with no
// stream), and bad files load nothing. Run under TSan for the thread tests.

#include <gtest/gtest.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <filesystem>
#include <memory>
#include <mutex>
#include <thread>
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

Tape::Limits smallLimits() {
    Tape::Limits l;
    l.maxSeconds = 12.0;
    l.budgetBytes = 24000000;
    return l;
}

// A tape rig with a store on the same tape (the store goes first).
struct Saved {
    explicit Saved(const std::string& dir, int64_t bar = 4800, TapeStore::Options options = {},
                   Tape::Limits limits = smallLimits())
        : rig(limits, bar), store(*rig.tape, options) {
        if (!dir.empty()) EXPECT_TRUE(store.setStorageDir(dir));
    }
    // Flush while the "stream" keeps running (the test thread is the audio thread).
    bool flushRunning(int timeoutMs = 10000) {
        return store.flush(timeoutMs, [this] { rig.run(256); });
    }
    // Flush with the stream stopped: the calling thread does the audio side's hand-over.
    bool flushStopped(int timeoutMs = 10000) {
        return store.flush(timeoutMs, [this] { rig.tape->applyPendingOffline(); });
    }
    // Loads and renders (running stream) until landed. Returns how many run() calls saw loading.
    int loadRunning() {
        EXPECT_TRUE(store.loadSaved());
        EXPECT_TRUE(rig.state().loading);
        int loadingRuns = 0;
        EXPECT_TRUE(waitFor([&] { return !rig.state().loading; }, 30000, [&] {
            rig.run(96);
            if (rig.state().loading) ++loadingRuns;
        }));
        return loadingRuns;
    }

    Rig rig;
    TapeStore store;
};

// A first take of `bars` bars on `track` from Play.
void firstTake(Rig& r, int track, int bars) {
    const int64_t f0 = r.frame;
    r.play();
    r.tape->armRecord(track);
    r.runTo(f0 + bars * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(f0 + bars * r.bar + 2000);
}

// One overdub pass (arm, a whole tape length, stop at the next bar line) on `track`.
void overdub(Rig& r, int track) {
    r.tape->armRecord(track);
    r.run(r.state().lengthFrames + r.bar);
    r.tape->stopRecord();
    r.run(2 * r.bar + 2000);
}

}  // namespace

// F7.5: takes on several tracks reach the files; a new tape loaded from them is the same tape,
// with an empty undo history (F7.6).
TEST(TapePersistence, TakesAreSavedAndLoadBackExactly) {
    TempDir dir;
    auto a = std::make_unique<Saved>(dir.path());
    firstTake(a->rig, 0, 3);
    overdub(a->rig, 2);
    a->rig.signal = [](int s, int64_t f, int c) { return 0.5f * noiseAt(s + 3, f, c); };
    overdub(a->rig, 2);  // an overdub on a track with audio
    ASSERT_TRUE(a->flushRunning());
    EXPECT_GT(a->store.durableSequence(), 0u);

    const tapefile::Meta m = readMeta(dir.path());
    EXPECT_EQ(m.sampleRate, 48000u);
    EXPECT_EQ(m.hasAudioMask, 0b101u);
    EXPECT_EQ(m.lengthFrames, 3 * 4800);
    EXPECT_EQ(m.barFrames, 4800);
    EXPECT_EQ(tapefile::fileSize(dir.file(tapefile::trackName(0))), m.fileFrames * 4);
    EXPECT_EQ(tapefile::fileSize(dir.file(tapefile::trackName(2))), m.fileFrames * 4);
    EXPECT_LT(tapefile::fileSize(dir.file(tapefile::kMetaTempName)), 0) << "no temp file left behind";

    Saved b(dir.path());
    b.loadRunning();
    expectSameTape(*a->rig.tape, *b.rig.tape);
    EXPECT_FALSE(b.rig.state().canUndo);
    EXPECT_FALSE(b.rig.state().canRedo);
    EXPECT_EQ(readMeta(dir.path()).sequence, m.sequence) << "a load that lands on untouched files rewrites nothing";
}

// Clears, undo and redo are saved like takes (the last state on disk is the tape's).
TEST(TapePersistence, ClearUndoAndRedoAreSaved) {
    TempDir dir;
    Saved a(dir.path());
    firstTake(a.rig, 1, 2);
    overdub(a.rig, 3);
    const auto check = [&](const char* what) {
        ASSERT_TRUE(a.flushRunning()) << what;
        Saved b(dir.path());
        if (a.rig.state().lengthFrames == 0) {
            EXPECT_FALSE(b.store.loadSaved()) << what;  // an empty tape: nothing to load
            return;
        }
        b.loadRunning();
        SCOPED_TRACE(what);
        expectSameTape(*a.rig.tape, *b.rig.tape);
    };
    check("two tracks");
    a.rig.tape->clearTrack(1);
    a.rig.run(4800);
    check("track 2 cleared");
    a.rig.tape->undo();
    a.rig.run(4800);
    check("clear undone");
    a.rig.tape->undo();
    a.rig.run(4800);
    check("overdub undone");
    a.rig.tape->redo();
    a.rig.run(4800);
    check("overdub redone");
    a.rig.tape->clearTrack(1);
    a.rig.run(4800);
    a.rig.tape->clearTrack(3);
    a.rig.run(4800);
    check("everything cleared");
    EXPECT_EQ(readMeta(dir.path()).hasAudioMask, 0u);
}

// F7.4/F7.5: a first take in progress is saved bar by bar, so a crash keeps what was played.
TEST(TapePersistence, AFirstTakeInProgressIsSavedInWholeBars) {
    TempDir dir;
    Saved a(dir.path());
    a.rig.play();
    a.rig.tape->armRecord(0);
    a.rig.runTo(2 * a.rig.bar + 1000);
    ASSERT_EQ(a.rig.state().recordingTrack, 0);
    ASSERT_TRUE(a.flushRunning());  // keeps recording meanwhile
    ASSERT_EQ(a.rig.state().recordingTrack, 0);
    const tapefile::Meta m = readMeta(dir.path());
    EXPECT_EQ(m.lengthFrames % 4800, 0);
    EXPECT_GE(m.lengthFrames, 3 * 4800);
    EXPECT_EQ(m.hasAudioMask, 1u);

    Saved b(dir.path());
    b.loadRunning();
    EXPECT_EQ(b.rig.state().lengthFrames, m.lengthFrames);
    for (int64_t f = 0; f < 2 * 4800; ++f) {
        ASSERT_EQ(b.rig.tape->sample(0, f, 0), a.rig.tape->sample(0, f, 0)) << f;
    }
}

// F7.6: missing, corrupt, truncated or wrong-rate files load nothing and never crash, and the
// old files stay as they are.
TEST(TapePersistence, BadFilesLoadNothing) {
    TempDir good;
    {
        Saved a(good.path());
        firstTake(a.rig, 0, 2);
        overdub(a.rig, 1);
        ASSERT_TRUE(a.flushRunning());
    }
    const auto meta = readAll(good.file(tapefile::kMetaName));
    ASSERT_EQ(meta.size(), tapefile::kMetaBytes);

    struct Case {
        const char* name;
        std::function<void(const std::string&)> damage;
        float rate = 48000.0f;
    };
    const auto withMeta = [](const std::function<void(tapefile::Meta&)>& edit) {
        return [edit](const std::string& d) {
            tapefile::Meta m = readMeta(d);
            edit(m);
            std::vector<uint8_t> b(tapefile::kMetaBytes);
            tapefile::encode(m, b.data());
            writeAll(tapefile::join(d, tapefile::kMetaName), b);
        };
    };
    const std::vector<Case> cases = {
        {"meta missing", [](const std::string& d) { std::filesystem::remove(tapefile::join(d, tapefile::kMetaName)); }},
        {"meta bit flipped",
         [&](const std::string& d) {
             auto b = meta;
             b[40] ^= 0x10;
             writeAll(tapefile::join(d, tapefile::kMetaName), b);
         }},
        {"meta truncated",
         [&](const std::string& d) {
             auto b = meta;
             b.resize(50);
             writeAll(tapefile::join(d, tapefile::kMetaName), b);
         }},
        {"meta garbage", [](const std::string& d) {
             std::vector<uint8_t> b(tapefile::kMetaBytes);
             for (std::size_t i = 0; i < b.size(); ++i) b[i] = static_cast<uint8_t>(i * 37 + 11);
             writeAll(tapefile::join(d, tapefile::kMetaName), b);
         }},
        {"future version",
         [&](const std::string& d) {
             auto b = meta;
             b[8] = 2;
             const uint32_t crc = tapefile::crc32(b.data(), 76);
             for (int i = 0; i < 4; ++i) b[static_cast<std::size_t>(76 + i)] = static_cast<uint8_t>(crc >> (8 * i));
             writeAll(tapefile::join(d, tapefile::kMetaName), b);
         }},
        {"track file missing", [](const std::string& d) { std::filesystem::remove(tapefile::join(d, tapefile::trackName(1))); }},
        {"track file truncated",
         [](const std::string& d) {
             const auto p = tapefile::join(d, tapefile::trackName(0));
             std::filesystem::resize_file(p, std::filesystem::file_size(p) - 4);
         }},
        {"too long for the tape", withMeta([](tapefile::Meta& m) {
             m.lengthFrames = 100 * m.barFrames;
             m.fileFrames = m.prerollFrames + m.lengthFrames + 2000;
         })},
        {"not whole bars", withMeta([](tapefile::Meta& m) { m.lengthFrames += 7; })},
        {"tracks without length", withMeta([](tapefile::Meta& m) { m.lengthFrames = 0; })},
        {"another sample rate", [](const std::string&) {}, 44100.0f},
    };
    for (const Case& c : cases) {
        SCOPED_TRACE(c.name);
        TempDir dir;
        copyDir(good.path(), dir.path());
        c.damage(dir.path());
        std::vector<std::vector<uint8_t>> before;
        for (int t = 0; t < Tape::kTracks; ++t) before.push_back(readAll(dir.file(tapefile::trackName(t))));
        before.push_back(readAll(dir.file(tapefile::kMetaName)));
        Saved b(dir.path());
        b.rig.tape->prepare(c.rate);
        b.rig.play();
        b.rig.run(960);
        if (b.store.loadSaved()) {
            ADD_FAILURE() << "accepted";
            ASSERT_TRUE(waitFor([&] { return !b.rig.state().loading; }, 20000, [&] { b.rig.run(96); }));
        }
        b.rig.run(4800);
        EXPECT_FALSE(b.rig.state().loading);
        EXPECT_EQ(b.rig.state().lengthFrames, 0);
        EXPECT_TRUE(b.flushRunning());
        // Nothing changed on the tape, so nothing was written: the old files stay as they were.
        for (int t = 0; t < Tape::kTracks; ++t) EXPECT_EQ(readAll(dir.file(tapefile::trackName(t))), before[static_cast<std::size_t>(t)]);
        EXPECT_EQ(readAll(dir.file(tapefile::kMetaName)), before.back());
    }
}

// The old files stay until a new change overwrites them: after a failed load, rendering and
// transport changes write nothing; the first take replaces the saved tape.
TEST(TapePersistence, OldFilesStayUntilANewTake) {
    TempDir dir;
    {
        Saved a(dir.path());
        firstTake(a.rig, 0, 2);
        ASSERT_TRUE(a.flushRunning());
    }
    // Make the load fail (wrong rate) but keep the files intact.
    Saved b(dir.path());
    b.rig.tape->prepare(44100.0f);
    const auto metaBefore = readAll(dir.file(tapefile::kMetaName));
    const auto trackBefore = readAll(dir.file(tapefile::trackName(0)));
    EXPECT_FALSE(b.store.loadSaved());
    b.rig.play();
    b.rig.run(20000);
    b.rig.stop();
    b.rig.run(5000);
    EXPECT_TRUE(b.flushRunning());  // nothing changed: nothing to wait for
    EXPECT_EQ(readAll(dir.file(tapefile::kMetaName)), metaBefore);
    EXPECT_EQ(readAll(dir.file(tapefile::trackName(0))), trackBefore);
    firstTake(b.rig, 2, 1);
    ASSERT_TRUE(b.flushRunning());
    const tapefile::Meta m = readMeta(dir.path());
    EXPECT_EQ(m.sampleRate, 44100u);
    EXPECT_EQ(m.hasAudioMask, 0b100u);
}

// F7.6: with a running stream the loaded tape lands over several pieces while loading is
// true; the tape is silent and commands wait meanwhile; then it plays the loaded audio.
TEST(TapePersistence, ALoadLandsIncrementallyWhileTheStreamRuns) {
    TempDir dir;
    constexpr int64_t kBar = 48000;
    auto a = std::make_unique<Saved>(dir.path(), kBar);
    firstTake(a->rig, 0, 4);
    overdub(a->rig, 1);
    ASSERT_TRUE(a->flushRunning());

    Saved b(dir.path(), kBar);
    firstTake(b.rig, 2, 1);  // a different tape (with undo history) playing when the load lands
    ASSERT_TRUE(b.rig.state().canUndo);
    ASSERT_TRUE(b.store.loadSaved());
    bool silentWhileLanding = true;
    bool armedWhileLanding = false;
    int landingRuns = 0;
    ASSERT_TRUE(waitFor([&] { return !b.rig.state().loading; }, 30000, [&] {
        const std::size_t at = b.rig.played[0].size();
        b.rig.run(96);
        const TapeState s = b.rig.state();
        if (s.loading && s.lengthFrames == 0) {
            ++landingRuns;
            if (!armedWhileLanding) armedWhileLanding = b.rig.tape->armRecord(3);  // waits
            for (int t = 0; t < Tape::kTracks; ++t) {
                for (std::size_t i = at; i < b.rig.played[static_cast<std::size_t>(t)].size(); ++i) {
                    silentWhileLanding = silentWhileLanding && b.rig.played[static_cast<std::size_t>(t)][i] == 0.0f;
                }
            }
        }
    }));
    EXPECT_GE(landingRuns, 2) << "the landing is spread over pieces";
    EXPECT_TRUE(silentWhileLanding);
    EXPECT_TRUE(armedWhileLanding);
    expectSameTape(*a->rig.tape, *b.rig.tape);
    EXPECT_FALSE(b.rig.state().canUndo);
    // It plays (the head restarts at the loop start with a short fade-in).
    b.rig.run(kBar);
    float peak = 0.0f;
    for (float v : b.rig.playedFrom(0, static_cast<int64_t>(b.rig.played[0].size()) - 20000)) peak = std::max(peak, std::fabs(v));
    EXPECT_GT(peak, 0.05f);
    // The arm sent during the landing applied after it (the next bar line starts the take).
    b.rig.run(2 * kBar);
    EXPECT_EQ(b.rig.state().recordingTrack, 3);
}

// With no stream the control thread lands the load as soon as it is read (F7.6).
TEST(TapePersistence, ALoadWithNoStreamLandsDirectly) {
    TempDir dir;
    auto a = std::make_unique<Saved>(dir.path());
    firstTake(a->rig, 1, 2);
    ASSERT_TRUE(a->flushRunning());
    Saved b(dir.path());
    ASSERT_TRUE(b.store.loadSaved());
    ASSERT_TRUE(waitFor([&] { return !b.rig.state().loading; }, 20000, [&] { b.rig.tape->applyPendingOffline(); }));
    expectSameTape(*a->rig.tape, *b.rig.tape);
}

// A bad dir disables persistence (with a log) and nothing crashes; only the first good dir
// counts.
TEST(TapePersistence, BadOrRepeatedStorageDirIsSafe) {
    TempDir dir, other;
    Saved a("");
    EXPECT_FALSE(a.store.setStorageDir("/nonexistent/groove/tape"));
    EXPECT_FALSE(a.store.setStorageDir(""));
    EXPECT_FALSE(a.store.enabled());
    EXPECT_FALSE(a.store.loadSaved());
    EXPECT_FALSE(a.flushRunning(100));
    firstTake(a.rig, 0, 1);  // a tape without persistence works as before
    EXPECT_EQ(a.rig.state().lengthBars, 1);

    EXPECT_TRUE(a.store.setStorageDir(dir.path()));  // attached late: catches up with the tape
    EXPECT_FALSE(a.store.setStorageDir(other.path()));
    a.rig.run(256);
    ASSERT_TRUE(a.flushRunning());
    EXPECT_EQ(readMeta(dir.path()).hasAudioMask, 1u);
    EXPECT_LT(tapefile::fileSize(other.file(tapefile::kMetaName)), 0);
}

// A tiny ring overflows all the time: what doesn't fit stays pending (merging into whole-track
// rewrites at worst), nothing is lost, and the files still converge to the tape.
TEST(TapePersistence, ARingOverflowDelaysButLosesNothing) {
    TempDir dir;
    TapeStore::Options tiny;
    tiny.ringBytes = 16384;
    tiny.commitIntervalMs = 0;
    Saved a(dir.path(), 4800, tiny);
    firstTake(a.rig, 0, 4);
    bool overflowed = a.rig.tape->persistPendingFrames() > 0;
    overdub(a.rig, 1);
    overdub(a.rig, 2);
    a.rig.tape->undo();
    a.rig.run(96);
    overflowed = overflowed || a.rig.tape->persistPendingFrames() > 0;
    a.rig.tape->clearTrack(0);
    a.rig.run(96);
    a.rig.tape->undo();
    a.rig.run(96);
    overflowed = overflowed || a.rig.tape->persistPendingFrames() > 0;
    EXPECT_TRUE(overflowed);
    ASSERT_TRUE(a.flushRunning(20000));
    EXPECT_EQ(a.rig.tape->persistPendingFrames(), 0);
    Saved b(dir.path());
    b.loadRunning();
    expectSameTape(*a.rig.tape, *b.rig.tape);
}

// flush() with the stream already stopped: the calling thread hands over what is pending.
TEST(TapePersistence, FlushWorksWithTheStreamStopped) {
    TempDir dir;
    TapeStore::Options tiny;
    tiny.ringBytes = 32768;
    Saved a(dir.path(), 4800, tiny);
    firstTake(a.rig, 0, 3);
    overdub(a.rig, 1);
    a.rig.tape->clearTrack(0);  // queued, not yet applied: applied by the stopped flush
    ASSERT_TRUE(a.flushStopped());
    EXPECT_FALSE(a.rig.state().hasAudio[0]);
    Saved b(dir.path());
    b.loadRunning();
    expectSameTape(*a.rig.tape, *b.rig.tape);
}

// After stop() nothing else renders; the writer's offline pump hands over everything still
// pending, so the files catch up in the background without a flush (coordinator, M4).
TEST(TapePersistence, AfterStopTheWriterFinishesInTheBackground) {
    TempDir dir;
    TapeStore::Options tiny;
    tiny.ringBytes = 16384;
    tiny.commitIntervalMs = 20;
    Saved a(dir.path(), 4800, tiny);
    std::mutex engineMutex;  // as AudioEngine's mutex_: serialises the offline hand-over
    bool streamRunning = true;
    a.store.setOfflinePump([&] {
        std::lock_guard<std::mutex> lock(engineMutex);
        if (!streamRunning) a.rig.tape->applyPendingOffline();
    });
    {
        std::lock_guard<std::mutex> lock(engineMutex);
        firstTake(a.rig, 0, 4);
        overdub(a.rig, 3);
        a.rig.tape->undo();  // ~77 KB of changes, sent while the stream stops: far more than the ring
        streamRunning = false;  // stop(): one hand-over on the stopping thread, the rest in the background
        a.rig.tape->applyPendingOffline();
        ASSERT_GT(a.rig.tape->persistPendingFrames(), 0) << "the test needs work left over at stop";
    }
    ASSERT_TRUE(waitFor([&] {
        std::lock_guard<std::mutex> lock(engineMutex);
        return a.store.durableSequence() == a.rig.tape->changeSequence() && a.rig.tape->persistPendingFrames() == 0;
    }));
    std::lock_guard<std::mutex> lock(engineMutex);
    Saved b(dir.path());
    b.loadRunning();
    expectSameTape(*a.rig.tape, *b.rig.tape);
}

// TSan: the writer and loader threads against the audio thread (takes, edits, flushes, loads
// landing mid-take, a tiny ring) and the control thread.
TEST(TapePersistence, WriterLoaderAndAudioThreadsRunConcurrently) {
    TempDir dir;
    TapeStore::Options tiny;
    tiny.ringBytes = 65536;
    tiny.commitIntervalMs = 5;
    auto s = std::make_unique<Saved>(dir.path(), 4800, tiny);
    Rig& r = s->rig;
    std::atomic<bool> done{false};
    r.play();
    std::thread audio([&] {
        while (!done.load()) {
            r.run(192);
            if (r.played[0].size() > 100000) {
                for (auto& p : r.played) p.clear();
            }
        }
    });
    int loads = 0, flushes = 0;
    for (int i = 0; i < 400; ++i) {
        switch (i % 10) {
            case 0: while (!r.tape->armRecord(i % 4)) std::this_thread::yield(); break;
            case 3: while (!r.tape->stopRecord()) std::this_thread::yield(); break;
            case 5: while (!r.tape->undo()) std::this_thread::yield(); break;
            case 6: while (!r.tape->redo()) std::this_thread::yield(); break;
            case 7: if (i % 30 == 7) while (!r.tape->clearTrack(i % 4)) std::this_thread::yield(); break;
            case 8: flushes += s->store.flush(200, {}) ? 1 : 0; break;
            case 9: if (i % 40 == 9) loads += s->store.loadSaved() ? 1 : 0; break;
            default: break;
        }
        (void)r.state();
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    done.store(true);
    audio.join();
    EXPECT_GT(flushes, 0);
    EXPECT_GT(loads, 0);
    EXPECT_EQ(s->store.writeErrors(), 0);
}

// Coordinator, M4 launch order: setTapeStorageDir, thousands of queued calls applied while the
// stream is stopped, start (prepare at the stream rate), then loadSavedTape. The saved tape
// lands unchanged and the files are not rewritten.
TEST(TapePersistence, LaunchOrderRestoresTheTape) {
    TempDir dir;
    std::vector<float> expected;
    int64_t length = 0;
    {
        auto g = std::make_unique<AudioGraph>();
        for (int s = 1; s < kSlotCount; ++s) setCleanSine(g->synth(s));
        g->prepare(48000.0f);
        ASSERT_TRUE(g->setTapeStorageDir(dir.path()));
        g->sequencer().setTempo(240.0f);
        std::vector<float> out(512);
        g->sequencer().play();
        g->tape().armRecord(1);
        g->noteOn(3, 64, 1.0f, 0);
        for (int i = 0; i < 48000 * 2 / 256 - 4; ++i) g->render(out.data(), 256, 2);
        g->tape().stopRecord();
        for (int i = 0; i < 48000 / 256; ++i) g->render(out.data(), 256, 2);
        g->sequencer().stop();
        g->render(out.data(), 256, 2);
        length = g->tape().state().lengthFrames;
        ASSERT_GT(length, 0);
        for (int64_t f = -480; f < length + 480; ++f) expected.push_back(g->tape().sample(1, f, 0));
        ASSERT_TRUE(g->flushTape(5000, [&] { g->applyPendingCommands(); }));
    }
    const auto metaBefore = readAll(dir.file(tapefile::kMetaName));

    auto g = std::make_unique<AudioGraph>();  // the app's launch
    ASSERT_TRUE(g->setTapeStorageDir(dir.path()));
    for (int i = 0; i < 10000; ++i) {
        const int slot = i % kSlotCount, note = 48 + i % 24;
        g->sequencer().setStep(slot, i % 16, (i / 16) % 64, {Hit{note, 0.8f}});
        if (i % 1000 == 999) g->applyPendingCommands();  // AudioEngine::submit with no stream
    }
    g->applyPendingCommands();
    g->prepare(48000.0f);  // start(): the stream opens at 48 kHz
    std::vector<float> out(512);
    g->render(out.data(), 256, 2);
    ASSERT_TRUE(g->loadSavedTape());
    ASSERT_TRUE(waitFor([&] { return !g->tape().state().loading; }, 20000, [&] { g->render(out.data(), 256, 2); }));
    ASSERT_EQ(g->tape().state().lengthFrames, length);
    for (int64_t f = -480; f < length + 480; ++f) {
        ASSERT_EQ(g->tape().sample(1, f, 0), expected[static_cast<std::size_t>(f + 480)]) << f;
    }
    for (int i = 0; i < 200; ++i) g->render(out.data(), 256, 2);
    EXPECT_TRUE(g->flushTape(2000, {}));
    EXPECT_EQ(readAll(dir.file(tapefile::kMetaName)), metaBefore) << "nothing changed, nothing rewritten";
}

// Build-20: the tape's tempo is saved with it, as its bar length (tape-meta.bin frames per
// bar, the first take's tempo rounded to frames as the sequencer rounds it, which maps back
// to one 0.1 BPM tempo). So tapes saved before build-20 load with their own tempo too. In a
// session at another tempo the loaded tape plays varispeed, and records only at its tempo.
TEST(TapePersistence, TheTapeTempoIsSavedAndLoadsBack) {
    TempDir dir;
    constexpr int64_t kBar1337 = 86163;  // 133.7 BPM at 48 kHz
    {
        Saved a(dir.path(), kBar1337);
        firstTake(a.rig, 0, 2);
        ASSERT_TRUE(a.flushRunning());
        EXPECT_EQ(readMeta(dir.path()).barFrames, kBar1337);
    }
    Saved b(dir.path(), 96000);  // the session is at 120 BPM
    b.loadRunning();
    TapeState s = b.rig.state();
    EXPECT_EQ(s.barFrames, kBar1337);
    EXPECT_EQ(s.lengthBars, 2);
    EXPECT_NEAR(s.tempoBpm, 133.7f, 1e-4f);
    EXPECT_FALSE(s.tempoMatches);
    b.rig.play();
    b.rig.run(9600);
    // Varispeed at 86163 / 96000 tape frames per frame.
    EXPECT_NEAR(static_cast<double>(b.rig.state().headFrame), 9600.0 * kBar1337 / 96000.0, 1.0);
    b.rig.tape->armRecord(1);
    b.rig.run(96);
    EXPECT_EQ(b.rig.state().armedTrack, -1);
    b.rig.bar = kBar1337;  // back at the tape's tempo
    b.rig.run(96);
    EXPECT_TRUE(b.rig.state().tempoMatches);
    b.rig.tape->armRecord(1);
    b.rig.run(96);
    EXPECT_EQ(b.rig.state().armedTrack, 1);
}
