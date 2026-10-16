// Tape (F5): bar-snapped takes following the transport, overdub with Dub, loop region and
// click-free wraps, speed/reverse, commit, clear, undo/redo within the pool, overview,
// readout, the command queue, and threading. The tape is driven directly with a fake
// transport (bar lines every `bar` frames from Play), exactly as AudioGraph drives it.

#include "groove/Tape.h"

#include <gtest/gtest.h>

#include <atomic>
#include <cmath>
#include <functional>
#include <memory>
#include <thread>
#include <vector>

#include "Analysis.h"
#include "TapeRig.h"

using namespace groove;
using namespace groove::test;

namespace {

void expectWholeBars(const TapeState& s) {
    EXPECT_EQ(s.lengthFrames, static_cast<int64_t>(s.lengthBars) * s.barFrames);
}

}  // namespace

// F5.1: an armed record on an empty tape starts on the sequencer's next bar line, stops on
// the next bar line after Stop, and its length in whole bars becomes the tape length.
TEST(Tape, FirstTakeStartsAndStopsOnBarLinesAndSetsTheLength) {
    Rig r;
    r.play();
    r.run(3000);  // more than 50 ms after Play (sooner starts at the Play frame)
    r.tape->armRecord(1);
    r.run(500);
    EXPECT_EQ(r.state().armedTrack, 1);
    EXPECT_EQ(r.state().recordingTrack, -1);
    r.runTo(r.bar + 10);
    EXPECT_EQ(r.state().recordingTrack, 1);
    EXPECT_EQ(r.state().lengthFrames, 0);  // empty until the first take ends
    EXPECT_EQ(r.state().barFrames, r.bar);
    r.runTo(r.bar * 3 + 700);  // 2.1 bars into the take
    r.tape->stopRecord();
    r.runTo(r.bar * 4 - 10);
    EXPECT_EQ(r.state().recordingTrack, 1);
    r.runTo(r.bar * 4 + 10);
    const TapeState s = r.state();
    EXPECT_EQ(s.recordingTrack, -1);
    EXPECT_EQ(s.lengthBars, 3);
    expectWholeBars(s);
    EXPECT_TRUE(s.hasAudio[1]);
    EXPECT_FALSE(s.hasAudio[0]);
    EXPECT_TRUE(s.canUndo);
    // Tape frame k holds the mix input of stream frame bar + k.
    float worst = 0.0f;
    for (int64_t k = 0; k < 3 * r.bar; k += 7) {
        worst = std::max(worst, std::fabs(r.tape->sample(1, k, 0) - noiseAt(0, r.bar + k, 0)));
        worst = std::max(worst, std::fabs(r.tape->sample(1, k, 1) - noiseAt(0, r.bar + k, 1)));
    }
    EXPECT_LE(worst, kQuant);
    EXPECT_EQ(r.tape->sample(0, 100, 0), 0.0f);
}

// An armed record starts at Play, and the head starts at the loop start on Play and goes
// back there on Stop.
TEST(Tape, ArmedRecordStartsAtPlayAndTheHeadFollowsTheTransport) {
    Rig r;
    r.run(500);
    r.tape->armRecord(0);
    r.run(500);
    EXPECT_EQ(r.state().armedTrack, 0);
    r.play();  // frame 1000
    r.run(96);
    EXPECT_EQ(r.state().recordingTrack, 0);
    r.runTo(1000 + 2 * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(1000 + 3 * r.bar);
    EXPECT_EQ(r.state().lengthBars, 2);
    EXPECT_NEAR(r.tape->sample(0, 0, 0), noiseAt(0, 1000, 0), kQuant);
    EXPECT_NEAR(r.tape->sample(0, 5, 0), noiseAt(0, 1005, 0), kQuant);
    // The head loops: 1 bar after the take ended it is at 1 bar.
    EXPECT_EQ(r.state().headFrame, r.bar);
    r.stop();
    r.run(96);
    EXPECT_EQ(r.state().headFrame, 0);
    r.play();
    r.run(480);
    EXPECT_EQ(r.state().headFrame, 480);
}

// A transport Stop during the first take ends it; its length rounds up to whole bars.
TEST(Tape, StopDuringTheFirstTakeRoundsUpToWholeBars) {
    Rig r;
    r.play();
    r.tape->armRecord(2);
    r.run(r.bar + r.bar / 3);
    r.stop();
    r.run(2000);
    const TapeState s = r.state();
    EXPECT_EQ(s.recordingTrack, -1);
    EXPECT_EQ(s.lengthBars, 2);
    expectWholeBars(s);
    EXPECT_EQ(s.headFrame, 0);
}

// The first take stops itself at 64 bars...
TEST(Tape, FirstTakeStopsItselfAtSixtyFourBars) {
    Rig r({}, 1000);
    r.play();
    r.tape->armRecord(0);
    r.run(70 * 1000, 256);
    EXPECT_EQ(r.state().recordingTrack, -1);
    EXPECT_EQ(r.state().lengthBars, 64);
    expectWholeBars(r.state());
}

// ...or at 2 minutes (whole bars), whichever comes first.
TEST(Tape, FirstTakeStopsItselfAtTheTimeLimit) {
    Tape::Limits small;
    small.maxSeconds = 1.0;  // 48000 frames
    Rig r(small, 10000);
    EXPECT_EQ(r.tape->maxFrames(), 48000);
    r.play();
    r.tape->armRecord(0);
    r.run(60000, 256);
    EXPECT_EQ(r.state().recordingTrack, -1);
    EXPECT_EQ(r.state().lengthBars, 4);
    expectWholeBars(r.state());
}

// The real limits: 4 tracks x 2 minutes of 16-bit stereo plus the undo pool stay within 256 MB.
TEST(Tape, StorageIsTwoMinutesPerTrackWithinTheBudget) {
    Tape t;
    t.prepare(48000.0f);
    EXPECT_EQ(t.maxFrames(), 120 * 48000);
    const int64_t total = 4 * t.trackBytes() + 4 * t.poolCapacityFrames();
    EXPECT_LE(total, 256000000);
    EXPECT_GE(t.poolCapacityFrames(), t.maxFrames());  // a full track's action is always undoable
    t.prepare(96000.0f);
    EXPECT_LE(4 * t.trackBytes() + 4 * t.poolCapacityFrames(), 256000000);
    EXPECT_GE(t.poolCapacityFrames(), t.maxFrames());
}

// F5.2: later takes start and stop on tape bar lines, write old x Dub + input, loop over the
// loop region while recording lasts; playback of the track being recorded plays the old audio.
TEST(Tape, OverdubMixesWithDubAndPlaybackPlaysTheOldAudio) {
    Rig r;
    r.signal = [](int, int64_t, int c) { return c == 0 ? 0.25f : -0.25f; };
    r.play();
    r.tape->armRecord(0);
    r.runTo(2 * r.bar - 300);
    r.tape->stopRecord();
    r.runTo(2 * r.bar + 2000);
    ASSERT_EQ(r.state().lengthBars, 2);

    r.signal = [](int, int64_t, int c) { return c == 0 ? 0.1f : 0.0f; };
    r.params.dub = 0.5f;
    r.tape->armRecord(0);
    r.run(500);  // head at ~2500 frames: starts at the next tape bar line (tape frame 4800)
    const int64_t start = r.frame + (r.bar - r.state().headFrame);
    r.runTo(start + 10);
    EXPECT_EQ(r.state().recordingTrack, 0);
    // One full loop and a bit (it wraps and keeps going), then stop at the next bar line.
    r.runTo(start + 2 * r.bar + 100);
    r.tape->stopRecord();
    r.runTo(start + 3 * r.bar + 2000);
    EXPECT_EQ(r.state().recordingTrack, -1);
    // Bar 1 was written twice (old*0.5+in, twice), bar 0 once.
    const float once = 0.25f * 0.5f + 0.1f;
    const float twice = once * 0.5f + 0.1f;
    EXPECT_NEAR(r.tape->sample(0, r.bar / 2, 0), once, 2 * kQuant);
    EXPECT_NEAR(r.tape->sample(0, r.bar + r.bar / 2, 0), twice, 2 * kQuant);
    EXPECT_NEAR(r.tape->sample(0, r.bar / 2, 1), -0.25f * 0.5f, 2 * kQuant);
    // While the first cycle recorded, playback of track 0 was the old audio.
    const auto out = r.playedFrom(0, start + 1000);
    EXPECT_NEAR(out[0], 0.25f, 2 * kQuant);
    expectWholeBars(r.state());
}

TEST(Tape, DubZeroReplaces) {
    Rig r;
    r.signal = [](int, int64_t, int) { return 0.3f; };
    r.play();
    r.tape->armRecord(3);
    r.runTo(r.bar - 100);
    r.tape->stopRecord();
    r.runTo(r.bar + 3000);
    r.signal = [](int, int64_t, int) { return -0.2f; };
    r.params.dub = 0.0f;
    r.tape->armRecord(3);
    r.runTo(2 * r.bar + 50);
    r.tape->stopRecord();
    r.runTo(4 * r.bar);
    EXPECT_NEAR(r.tape->sample(3, r.bar / 2, 0), -0.2f, 2 * kQuant);
}

// F5.3: the loop region (in bars) bounds playback and recording; a region past the tape's end
// is clamped: start into the tape, end at the tape length, at least 1 bar.
TEST(Tape, LoopRegionIsClampedIntoTheTape) {
    Rig r;
    r.play();
    r.tape->armRecord(0);
    r.runTo(4 * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(4 * r.bar + 100);
    ASSERT_EQ(r.state().lengthBars, 4);
    r.params.loopStartBars = 1;
    r.params.loopLengthBars = 2;
    r.run(96);
    EXPECT_EQ(r.tape->loopStart(), r.bar);
    EXPECT_EQ(r.tape->loopEnd(), 3 * r.bar);
    r.params.loopStartBars = 10;
    r.params.loopLengthBars = 5;
    r.run(96);
    EXPECT_EQ(r.tape->loopStart(), 3 * r.bar);
    EXPECT_EQ(r.tape->loopEnd(), 4 * r.bar);
    r.params.loopStartBars = 2;
    r.params.loopLengthBars = 9;
    r.run(96);
    EXPECT_EQ(r.tape->loopStart(), 2 * r.bar);
    EXPECT_EQ(r.tape->loopEnd(), 4 * r.bar);
    r.params.loopLengthBars = 0;  // whole tape from the start bar
    r.run(96);
    EXPECT_EQ(r.tape->loopEnd(), 4 * r.bar);
    // The head stays inside the region.
    for (int i = 0; i < 300; ++i) {
        r.run(96);
        EXPECT_GE(r.state().headFrame, 2 * r.bar);
        EXPECT_LT(r.state().headFrame, 4 * r.bar);
    }
}

TEST(Tape, RecordingStaysInsideTheLoopRegion) {
    Rig r;
    r.signal = [](int, int64_t, int) { return 0.2f; };
    r.play();
    r.tape->armRecord(0);
    r.runTo(4 * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(4 * r.bar + 100);
    r.params.loopStartBars = 1;
    r.params.loopLengthBars = 1;
    r.signal = [](int, int64_t, int) { return 0.1f; };
    r.run(96);
    r.tape->armRecord(0);
    r.run(4 * r.bar);  // four passes over bar 1
    r.tape->stopRecord();
    r.run(2 * r.bar);
    const int64_t x = r.tape->crossfadeFrames();
    EXPECT_NEAR(r.tape->sample(0, r.bar / 2, 0), 0.2f, 2 * kQuant);        // bar 0 untouched
    EXPECT_NEAR(r.tape->sample(0, 2 * r.bar + 2 * x, 0), 0.2f, 2 * kQuant);  // bar 2 untouched
    EXPECT_GT(r.tape->sample(0, r.bar + r.bar / 2, 0), 0.5f);              // bar 1 overdubbed 4 times
}

// F5.3: playback wraps the loop with no click, forward and reverse, whole tape and inner
// region: the largest sample step stays at the level of the signal's own steps.
TEST(Tape, LoopWrapsWithoutClicks) {
    for (int region = 0; region < 2; ++region) {
        for (bool reverse : {false, true}) {
            Rig r;
            r.signal = [](int, int64_t f, int) {
                return 0.6f * static_cast<float>(std::sin(2.0 * kPiD * 220.0 * static_cast<double>(f) / 48000.0));
            };
            const float ownStep = 0.6f * static_cast<float>(2.0 * kPiD * 220.0 / 48000.0);
            r.bar = 4801;  // not a multiple of the period: the wrap is a discontinuity in the raw data
            r.play();
            r.tape->armRecord(0);
            r.runTo(3 * r.bar - 300);
            r.tape->stopRecord();
            r.runTo(3 * r.bar + 100);
            ASSERT_EQ(r.state().lengthBars, 3);
            if (region == 1) {
                r.params.loopStartBars = 1;
                r.params.loopLengthBars = 1;
            }
            r.params.reverse = reverse;
            r.run(2000);
            const int64_t from = r.frame;
            r.run(5 * r.bar);
            const auto out = r.playedFrom(0, from);
            EXPECT_LT(maxStep(out), 2.0f * ownStep) << "region " << region << " reverse " << reverse;
            EXPECT_GT(rms(out), 0.3);
        }
    }
}

// Punch-in and punch-out of an overdub don't write a click into the tape.
TEST(Tape, PunchInAndOutAreClickFree) {
    Rig r;
    r.signal = [](int, int64_t f, int) {
        return 0.4f * static_cast<float>(std::sin(2.0 * kPiD * 150.0 * static_cast<double>(f) / 48000.0));
    };
    r.play();
    r.tape->armRecord(0);
    r.runTo(4 * r.bar - 300);
    r.tape->stopRecord();
    r.runTo(4 * r.bar + 100);
    r.signal = [](int, int64_t f, int) {
        return 0.4f * static_cast<float>(std::sin(2.0 * kPiD * 330.0 * static_cast<double>(f) / 48000.0 + 1.0));
    };
    r.tape->armRecord(1);
    r.run(r.bar + r.bar / 2);
    r.tape->stopRecord();
    r.run(2 * r.bar);
    std::vector<float> t1;
    for (int64_t k = 0; k < 4 * r.bar; ++k) t1.push_back(r.tape->sample(1, k, 0));
    // A sine of 0.4 at 330 Hz steps by at most 0.0173 per frame.
    EXPECT_LT(maxStep(t1), 0.02f);
    EXPECT_GT(peakAbs(t1), 0.3f);
}

// F5.5: 2x and 0.5x change pitch and speed tape-style; reverse keeps the pitch; aliasing at
// 2x is filtered.
TEST(Tape, SpeedAndReverseChangePitchTapeStyle) {
    Rig r;
    r.signal = [](int, int64_t f, int) {
        return 0.5f * static_cast<float>(std::sin(2.0 * kPiD * 1000.0 * static_cast<double>(f) / 48000.0));
    };
    r.bar = 48000;
    r.play();
    r.tape->armRecord(0);
    r.runTo(2 * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(2 * r.bar + 100);
    struct Case {
        int speed;
        bool reverse;
        double hz;
    };
    for (const Case c : {Case{2, false, 2000.0}, Case{0, false, 500.0}, Case{1, true, 1000.0}, Case{0, true, 500.0},
                         Case{2, true, 2000.0}}) {
        r.params.speed = c.speed;
        r.params.reverse = c.reverse;
        r.run(4800);
        const int64_t from = r.frame;
        r.run(24000);
        const auto out = r.playedFrom(0, from);
        EXPECT_NEAR(zeroCrossingHz(out, 48000.0, 0, 20000), c.hz, c.hz * 0.003) << c.speed << c.reverse;
        // The loop wrap of a phase-coherent sine bumps by at most +0.7 dB for 10 ms.
        EXPECT_NEAR(peakAbs(out), 0.5f, 0.05f);
        EXPECT_NEAR(rms(out), 0.5 / std::sqrt(2.0), 0.01);
    }
}

TEST(Tape, TwoTimesSpeedFiltersAliases) {
    Rig r;
    // 14 kHz played at 2x would alias to 48 - 28 = 20 kHz.
    r.signal = [](int, int64_t f, int) {
        return 0.5f * static_cast<float>(std::sin(2.0 * kPiD * 14000.0 * static_cast<double>(f) / 48000.0));
    };
    r.bar = 96000;
    r.play();
    r.tape->armRecord(0);
    r.runTo(2 * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(2 * r.bar + 100);
    r.params.speed = 2;
    r.run(4800);
    const int64_t from = r.frame;
    r.run(40000);
    const auto out = r.playedFrom(0, from);
    const double alias = goertzelAmplitude(out, 48000.0, 20000.0, 0, 32768);
    std::printf("14 kHz at 2x: alias at 20 kHz %.1f dB\n", toDb(alias / 0.5));
    EXPECT_LT(toDb(alias / 0.5), -30.0);
}

TEST(Tape, SpeedChangesDoNotClick) {
    Rig r;
    r.signal = [](int, int64_t f, int) {
        return 0.5f * static_cast<float>(std::sin(2.0 * kPiD * 300.0 * static_cast<double>(f) / 48000.0));
    };
    r.play();
    r.tape->armRecord(0);
    r.runTo(3 * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(3 * r.bar + 100);
    const int64_t from = r.frame;
    for (int i = 0; i < 12; ++i) {
        r.params.speed = i % 3;
        r.params.reverse = (i / 3) % 2 == 1;
        r.run(3000);
    }
    const auto out = r.playedFrom(0, from);
    // 300 Hz at 2x: 600 Hz of amplitude 0.5 steps by 0.039 per frame.
    EXPECT_LT(maxStep(out), 0.06f);
}

// F5.5: recording (and commit) only at 1x forward; otherwise an arm is ignored.
TEST(Tape, ArmAndCommitAreIgnoredUnlessOneTimesForward) {
    for (int c = 0; c < 3; ++c) {
        Rig r;
        r.params.speed = c == 0 ? 2 : 1;
        r.params.reverse = c == 1;
        if (c == 2) r.params.speed = 0;
        r.play();
        r.run(96);
        r.tape->armRecord(0);
        r.run(96);
        EXPECT_EQ(r.state().armedTrack, -1);
        r.tape->commit(1, -1);
        r.passBars = 1;
        r.run(96);
        EXPECT_EQ(r.state().armedTrack, -1);
        r.run(3 * r.bar);
        EXPECT_EQ(r.state().recordingTrack, -1);
        EXPECT_EQ(r.state().lengthFrames, 0);
    }
    // Armed at 1x, then switched to reverse before the bar line: the arm is dropped.
    Rig r;
    r.play();
    r.run(3000);
    r.tape->armRecord(0);
    r.run(96);
    EXPECT_EQ(r.state().armedTrack, 0);
    r.params.reverse = true;
    r.run(r.bar);
    EXPECT_EQ(r.state().armedTrack, -1);
    EXPECT_EQ(r.state().recordingTrack, -1);
}

// F5.6: a commit of one slot records exactly that slot's pattern pass from its next
// boundary; on an empty tape the length rounds up to whole bars (the rest silent).
TEST(Tape, CommitOfOneSlotRecordsExactlyOnePassOfThatSlot) {
    Rig r;
    r.passFrames = 3600;  // e.g. a 12-step pattern when a bar is 16 steps (4800)
    r.play();
    r.run(3000);
    r.tape->commit(2, 4);  // source 5 = slot 4
    r.run(96);
    EXPECT_EQ(r.state().armedTrack, 2);
    r.run(2 * r.bar);
    const TapeState s = r.state();
    EXPECT_EQ(s.recordingTrack, -1);
    EXPECT_EQ(s.lengthBars, 1);
    expectWholeBars(s);
    // The pass started at the slot's boundary (frame 3600) and is exactly 3600 frames long.
    float worst = 0.0f;
    for (int64_t k = 0; k < 3600; k += 3) worst = std::max(worst, std::fabs(r.tape->sample(2, k, 0) - noiseAt(5, 3600 + k, 0)));
    EXPECT_LE(worst, kQuant);
    // After the pass's ramped tail, the rest of the bar is silent.
    for (int64_t k = 3600 + r.tape->crossfadeFrames(); k < r.bar; ++k) ASSERT_EQ(r.tape->sample(2, k, 0), 0.0f) << k;
}

TEST(Tape, CommitOfAllSlotsRecordsTheMixForWholeBars) {
    Rig r;
    r.passBars = 2;
    r.play();
    r.run(3000);
    r.tape->commit(0, -1);
    r.run(5 * r.bar);
    EXPECT_EQ(r.state().lengthBars, 2);
    EXPECT_NEAR(r.tape->sample(0, 100, 0), noiseAt(0, 2 * r.bar + 100, 0), kQuant);
    EXPECT_NEAR(r.tape->sample(0, 2 * r.bar - 100, 1), noiseAt(0, 4 * r.bar - 100, 1), kQuant);
}

// On a non-empty tape a commit writes one pass over the loop region from the head, like a
// record pass (Dub applies).
TEST(Tape, CommitOnATapeOverdubsOnePass) {
    Rig r;
    r.signal = [](int, int64_t, int) { return 0.2f; };
    r.play();
    r.tape->armRecord(0);
    r.runTo(2 * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(2 * r.bar + 100);
    r.signal = [](int s, int64_t, int) { return s == 1 ? 0.05f : 0.0f; };  // slot 0
    r.passFrames = 2400;
    r.tape->commit(0, 0);
    r.run(3 * r.bar);
    // Exactly one 2400-frame pass, at the next multiple of 2400 since Play (frame 9600 + ...).
    int64_t changed = 0;
    for (int64_t k = 0; k < 2 * r.bar; ++k) changed += std::fabs(r.tape->sample(0, k, 0) - 0.25f) < 2 * kQuant ? 1 : 0;
    EXPECT_GE(changed, 2400);  // the pass, plus at most the lead-in's last and the tail's first frame
    EXPECT_LE(changed, 2404);
}

// Coordinator rule: the UI sends play() and tapeCommit()/tapeArmRecord() together. However the
// two queues interleave, the take starts at the pattern start (the Play frame), never a whole
// pattern later.
TEST(Tape, CommitOrArmSentWithPlayStartsAtThePlayFrame) {
    for (int delayChunks = 0; delayChunks < 4; ++delayChunks) {
        for (bool commit : {true, false}) {
            Rig r;
            r.passBars = 1;
            r.run(1000);
            r.play();  // frame 1000
            if (delayChunks == 0) {
                if (commit) r.tape->commit(1, -1); else r.tape->armRecord(1);
                r.run(96);
            } else {
                r.run(96 * delayChunks);  // the tape command lands a few callbacks later
                if (commit) r.tape->commit(1, -1); else r.tape->armRecord(1);
                r.run(96);
            }
            r.run(r.bar / 2);
            if (!commit) r.tape->stopRecord();
            r.run(2 * r.bar);
            EXPECT_EQ(r.state().lengthBars, 1) << delayChunks << commit;
            for (int64_t k : {int64_t{0}, int64_t{5}, int64_t{200}, int64_t{4000}}) {
                EXPECT_NEAR(r.tape->sample(1, k, 0), noiseAt(0, 1000 + k, 0), kQuant) << delayChunks << commit << k;
            }
        }
    }
}

// F5.4: undo and redo of a take, an overdub and a clear restore the exact audio.
TEST(Tape, UndoAndRedoRestoreExactly) {
    Rig r;
    r.play();
    r.tape->armRecord(0);
    r.runTo(2 * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(2 * r.bar + 1000);
    auto snapshot = [&](int track) {
        std::vector<float> v;
        for (int64_t k = -r.tape->crossfadeFrames(); k < 2 * r.bar + r.tape->crossfadeFrames(); ++k) {
            v.push_back(r.tape->sample(track, k, 0));
            v.push_back(r.tape->sample(track, k, 1));
        }
        return v;
    };
    const auto take = snapshot(0);
    r.tape->armRecord(0);
    r.run(r.bar);
    r.tape->stopRecord();
    r.run(2 * r.bar);
    const auto dubbed = snapshot(0);
    ASSERT_NE(take, dubbed);
    EXPECT_EQ(r.tape->undoCount(), 2);

    r.tape->undo();
    r.run(4800);
    EXPECT_EQ(snapshot(0), take);
    EXPECT_TRUE(r.state().canRedo);
    r.tape->redo();
    r.run(4800);
    EXPECT_EQ(snapshot(0), dubbed);

    r.tape->clearTrack(0);
    r.run(4800);
    EXPECT_FALSE(r.state().hasAudio[0]);
    EXPECT_EQ(r.state().lengthFrames, 0);  // every track cleared: empty tape
    EXPECT_EQ(r.state().barFrames, 0);
    r.tape->undo();
    r.run(4800);
    EXPECT_EQ(snapshot(0), dubbed);
    EXPECT_EQ(r.state().lengthBars, 2);
    r.tape->undo();
    r.tape->undo();
    r.run(4800);
    EXPECT_EQ(r.state().lengthFrames, 0);  // the first take undone: empty again
    EXPECT_FALSE(r.state().canUndo);
    for (float v : snapshot(0)) ASSERT_EQ(v, 0.0f);
    r.tape->redo();
    r.run(4800);
    EXPECT_EQ(snapshot(0), take);
    EXPECT_EQ(r.state().lengthBars, 2);
    expectWholeBars(r.state());
}

TEST(Tape, ClearingOneTrackKeepsTheTape) {
    Rig r;
    r.play();
    r.tape->armRecord(0);
    r.runTo(2 * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(2 * r.bar + 100);
    r.tape->armRecord(1);
    r.run(r.bar);
    r.tape->stopRecord();
    r.run(2 * r.bar);
    r.tape->clearTrack(0);
    r.run(960);
    EXPECT_EQ(r.state().lengthBars, 2);
    EXPECT_FALSE(r.state().hasAudio[0]);
    EXPECT_TRUE(r.state().hasAudio[1]);
    EXPECT_EQ(r.tape->sample(0, 500, 0), 0.0f);
    r.tape->clearTrack(1);
    r.run(960);
    EXPECT_EQ(r.state().lengthFrames, 0);
}

// The last 8 actions are kept; a 9th drops the oldest. A new action discards the redo entries.
TEST(Tape, KeepsTheLastEightActions) {
    Rig r;
    r.play();
    r.tape->armRecord(0);
    r.runTo(r.bar - 100);
    r.tape->stopRecord();
    r.runTo(r.bar + 100);
    for (int i = 0; i < 9; ++i) {
        r.tape->armRecord(i % 4);
        r.run(r.bar);
        r.tape->stopRecord();
        r.run(r.bar);
    }
    EXPECT_EQ(r.tape->undoCount(), 8);
    for (int i = 0; i < 3; ++i) r.tape->undo();
    r.run(960);
    EXPECT_EQ(r.tape->undoCount(), 5);
    EXPECT_EQ(r.tape->redoCount(), 3);
    r.tape->clearTrack(2);
    r.run(960);
    EXPECT_EQ(r.tape->undoCount(), 6);
    EXPECT_EQ(r.tape->redoCount(), 0);
    for (int i = 0; i < 10; ++i) r.tape->undo();
    r.run(4800);
    EXPECT_EQ(r.tape->undoCount(), 0);
    EXPECT_GT(r.state().lengthFrames, 0);  // the first take dropped off the history: it stays
}

// F5.4: when the pool is full, the oldest actions drop off; the newest stays undoable.
TEST(Tape, AFullPoolDropsTheOldestActions) {
    Tape::Limits small;
    small.maxSeconds = 0.5;  // 24000 frames per track
    small.budgetBytes = 4 * (24000 + 4000) * 4 + 3 * 24000 * 4;  // room for about 3 full-track entries
    Rig r(small, 6000);
    r.play();
    r.tape->armRecord(0);
    r.runTo(4 * 6000 - 100);
    r.tape->stopRecord();
    r.runTo(4 * 6000 + 100);
    for (int i = 0; i < 6; ++i) {
        r.tape->armRecord(1 + i % 3);
        r.run(5 * 6000);
        r.tape->stopRecord();
        r.run(6000);
    }
    EXPECT_GT(r.tape->undoCount(), 0);
    EXPECT_LT(r.tape->undoCount(), 8);
    EXPECT_LE(r.tape->poolUsedFrames(), r.tape->poolCapacityFrames());
    // The newest one still undoes exactly.
    std::vector<float> before;
    const int track = 1 + 5 % 3;
    for (int64_t k = 0; k < 24000; ++k) before.push_back(r.tape->sample(track, k, 0));
    r.tape->undo();
    r.run(960);
    r.tape->redo();
    r.run(960);
    for (int64_t k = 0; k < 24000; ++k) ASSERT_EQ(r.tape->sample(track, k, 0), before[static_cast<std::size_t>(k)]);
}

// F5.4: undo takes effect within 100 ms, even for a full 2-minute track.
// An overdub that wraps the loop many times (its tails and the next passes interleave) stays
// undoable and undo restores the tape exactly; the earlier history is kept (F5.4).
TEST(Tape, AnOverdubAcrossManyLoopWrapsStaysUndoable) {
    for (int block : {32, 96, 256}) {
        SCOPED_TRACE(block);
        Rig r;
        r.play();
        r.tape->armRecord(0);
        r.runTo(2 * r.bar - 100, block);
        r.tape->stopRecord();
        r.runTo(2 * r.bar + 2000, block);
        std::vector<float> before;
        for (int64_t f = -r.tape->crossfadeFrames(); f < 2 * r.bar + r.tape->crossfadeFrames(); ++f) {
            before.push_back(r.tape->sample(0, f, 0));
        }
        r.tape->armRecord(0);
        r.run(12 * r.bar, block);  // six passes
        r.tape->stopRecord();
        r.run(2 * r.bar + 2000, block);
        ASSERT_EQ(r.tape->undoCount(), 2);
        r.tape->undo();
        r.run(2000, block);
        EXPECT_EQ(r.tape->undoCount(), 1);
        std::size_t i = 0, diff = 0;
        for (int64_t f = -r.tape->crossfadeFrames(); f < 2 * r.bar + r.tape->crossfadeFrames(); ++f) {
            diff += r.tape->sample(0, f, 0) != before[i++] ? 1 : 0;
        }
        EXPECT_EQ(diff, 0u);
    }
}

TEST(Tape, UndoOfAFullTwoMinuteTrackTakesUnder100Ms) {
    Rig r({}, 90000);  // 64 bars of 90000 frames = 2 minutes at 48 kHz
    r.play();
    r.tape->armRecord(0);
    r.run(65 * 90000, 256);
    ASSERT_EQ(r.state().lengthFrames, 64 * 90000);
    r.tape->clearTrack(0);
    int64_t frames = 0;
    while (r.state().hasAudio[0]) {
        r.run(96);
        frames += 96;
    }
    EXPECT_LE(frames, 4800);
    r.tape->undo();
    frames = 0;
    r.run(96);
    while (!r.state().hasAudio[0]) {
        r.run(96);
        frames += 96;
    }
    frames += 96;
    EXPECT_LE(frames, 4800);
    EXPECT_NEAR(r.tape->sample(0, 5000000, 0), noiseAt(0, 5000000, 0), kQuant);  // started at Play
}

// Undo during a take ends the take there, then undoes it.
TEST(Tape, UndoDuringATakeEndsAndUndoesIt) {
    Rig r;
    r.play();
    r.tape->armRecord(0);
    r.runTo(2 * r.bar - 100);
    r.tape->stopRecord();
    r.runTo(2 * r.bar + 100);
    std::vector<float> before;
    for (int64_t k = 0; k < 2 * r.bar; ++k) before.push_back(r.tape->sample(0, k, 0));
    r.tape->armRecord(0);
    r.run(r.bar + 2000);
    EXPECT_EQ(r.state().recordingTrack, 0);
    r.tape->undo();
    r.run(2000);
    EXPECT_EQ(r.state().recordingTrack, -1);
    for (int64_t k = 0; k < 2 * r.bar; ++k) ASSERT_EQ(r.tape->sample(0, k, 0), before[static_cast<std::size_t>(k)]) << k;
}

// Peak overviews: 256 bins across the tape length, 255 = 0 dBFS, zeros when empty.
TEST(Tape, OverviewShowsPeaksAcrossTheTape) {
    Rig r;
    r.signal = [](int, int64_t f, int) { return f >= 4800 ? 1.0f : 0.25f; };
    std::array<uint8_t, 256> ov{};
    r.tape->overview(0, ov.data());
    for (uint8_t v : ov) EXPECT_EQ(v, 0);
    r.play();
    r.tape->armRecord(0);  // starts at Play
    r.runTo(4800 + 100);
    r.tape->overview(0, ov.data());
    EXPECT_GT(ov[10], 0);  // grows while the first take records
    r.runTo(2 * 4800 - 100);
    r.tape->stopRecord();
    r.runTo(2 * 4800 + 2000);
    ASSERT_EQ(r.state().lengthBars, 2);
    r.tape->overview(0, ov.data());
    EXPECT_NEAR(ov[20], 64, 1);   // 0.25 -> -12 dB -> 255/4
    EXPECT_EQ(ov[200], 255);
    r.tape->overview(1, ov.data());
    for (uint8_t v : ov) EXPECT_EQ(v, 0);
    r.tape->clearTrack(0);
    r.run(960);
    r.tape->overview(0, ov.data());
    for (uint8_t v : ov) EXPECT_EQ(v, 0);
}

// Commands are never silently dropped: a full queue says so.
TEST(Tape, AFullCommandQueueRefusesInsteadOfDropping) {
    Tape t;
    t.prepare(kRate);
    std::size_t accepted = 0;
    while (t.stopRecord()) ++accepted;
    EXPECT_EQ(accepted, Tape::kCommandCapacity);
    EXPECT_TRUE(t.armRecord(7));  // out of range: ignored, not queued
    EXPECT_TRUE(t.commit(0, 8));
}

// prepare() at the same rate keeps the tape (the UI never resends it); another rate clears it.
TEST(Tape, PrepareKeepsTheTapeAtTheSameRate) {
    Rig r;
    r.play();
    r.tape->armRecord(0);
    r.runTo(r.bar - 100);
    r.tape->stopRecord();
    r.runTo(r.bar + 100);
    const float v = r.tape->sample(0, 777, 0);
    r.tape->prepare(kRate);
    EXPECT_EQ(r.tape->sample(0, 777, 0), v);
    EXPECT_EQ(r.state().lengthBars, 1);
    EXPECT_TRUE(r.state().canUndo);
    r.tape->prepare(44100.0f);
    EXPECT_EQ(r.state().lengthFrames, 0);
    EXPECT_EQ(r.tape->sample(0, 777, 0), 0.0f);
}

// With no stream, commands apply offline (the edit completes at once).
TEST(Tape, OfflineCommandsComplete) {
    Rig r;
    r.play();
    r.tape->armRecord(0);
    r.runTo(r.bar - 100);
    r.tape->stopRecord();
    r.runTo(r.bar + 100);
    r.tape->clearTrack(0);
    r.tape->applyPendingOffline();
    EXPECT_EQ(r.state().lengthFrames, 0);
    r.tape->undo();
    r.tape->applyPendingOffline();
    EXPECT_EQ(r.state().lengthBars, 1);
}

// Control thread (commands, readout, overview) against the audio thread: TSan checks it.
TEST(Tape, ControlAndAudioThreadsRunConcurrently) {
    Tape::Limits small;
    small.maxSeconds = 4.0;
    small.budgetBytes = 20000000;
    auto rig = std::make_unique<Rig>(small, 4800);
    Rig& r = *rig;
    r.passBars = 1;
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
    std::array<uint8_t, 256> ov{};
    for (int i = 0; i < 3000; ++i) {
        switch (i % 9) {
            case 0: while (!r.tape->armRecord(i % 4)) std::this_thread::yield(); break;
            case 3: while (!r.tape->stopRecord()) std::this_thread::yield(); break;
            case 5: while (!r.tape->undo()) std::this_thread::yield(); break;
            case 6: while (!r.tape->redo()) std::this_thread::yield(); break;
            case 7: while (!r.tape->commit(i % 4, i % 9 - 1)) std::this_thread::yield(); break;
            case 8: if (i % 27 == 8) while (!r.tape->clearTrack(i % 4)) std::this_thread::yield(); break;
            default: break;
        }
        (void)r.tape->state();
        r.tape->overview(i % 4, ov.data());
        std::this_thread::sleep_for(std::chrono::microseconds(200));
    }
    done.store(true);
    audio.join();
    SUCCEED();
}
