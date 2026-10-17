// Per-hit steps (docs/plans/2026-10-16-build-20-sequencer-lanes.md, "Engine timing rules"):
// several sounds per step, nudge (late and early, through shuffle, loops, length wraps and
// pattern switches), length in steps, the retrigger rule, pending-event limits, per-hit
// chance, stop, and zero drift with nudges (F3.1). Offline, with logging instruments.

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>

#include "SequencerTestUtil.h"
#include "groove/Sequencer.h"

using namespace groove;
using namespace groove::test;

namespace {

constexpr int64_t kRate = 48000;
constexpr int64_t kStep = 6000;  // 1/16 at 120 BPM

// One step in position units (Sequencer.h: a beat is 36000 * sampleRate units).
constexpr int64_t stepUnits(int64_t stepsPerBeat = 4) { return Sequencer::kUnitsPerBeatPerHz * kRate / stepsPerBeat; }
// The frame of an exact position, Play at frame 0, constant tempo.
int64_t frameAt(int64_t units, int64_t tenths = 1200) { return ceilDiv(units, 60 * tenths); }
// A nudge in units, rounded like the engine.
int64_t nudgeUnits(float nudge, int64_t units) {
    return static_cast<int64_t>(std::llround(static_cast<double>(nudge) * static_cast<double>(units)));
}

std::vector<int64_t> framesOf(const std::vector<SeqCall>& calls) {
    std::vector<int64_t> f;
    for (const SeqCall& c : calls) f.push_back(c.frame);
    return f;
}

// Index of the first call of `type` for `note` at `frame`, or -1.
int indexOf(const LoggingInstrument& inst, SeqCall::Type type, int note, int64_t frame) {
    for (std::size_t i = 0; i < inst.calls.size(); ++i) {
        const SeqCall& c = inst.calls[i];
        if (c.type == type && c.note == note && c.frame == frame) return static_cast<int>(i);
    }
    return -1;
}

}  // namespace

// --- several sounds per step ---

TEST(SequencerHits, KickAndSnareOnTheSameStepBothPlay) {
    SeqHarness h;
    for (int s : {0, 4, 8, 12}) h.seq->setStep(kDrumSlot, 0, s, {Hit{48, 1.0f}});
    for (int s : {4, 12}) h.seq->setStep(kDrumSlot, 0, s, {Hit{48, 1.0f}, Hit{50, 0.6f}});
    h.seq->play();
    h.run(16 * kStep - 1);
    const auto& d = h.slot(kDrumSlot);
    EXPECT_EQ(framesOf(d.noteOns(48)), (std::vector<int64_t>{0, 4 * kStep, 8 * kStep, 12 * kStep}));
    const auto snare = d.noteOns(50);
    EXPECT_EQ(framesOf(snare), (std::vector<int64_t>{4 * kStep, 12 * kStep}));
    EXPECT_FLOAT_EQ(snare[0].value, 0.6f);  // each hit has its own velocity
    EXPECT_FLOAT_EQ(d.noteOns(48)[1].value, 1.0f);
}

// --- nudge ---

// F3.1 with nudges: for ten minutes, every hit (nudged -50%..+50% through shuffle) lands
// exactly on step position + round(nudge * step) in units: zero drift, sample-exact.
TEST(SequencerHits, NudgedHitsAreSampleExactThroughShuffleForTenMinutes) {
    struct Case {
        float bpm;
        int rate;
        int64_t spb;
        int block;
        int64_t seconds;
    };
    for (const Case c : {Case{97.3f, 1, 4, 37, 600}, Case{133.7f, 3, 6, 96, 60}, Case{240.0f, 2, 8, 61, 30}}) {
        const float shuffle = 0.5f;
        SeqHarness h;
        h.seq->setTempo(c.bpm);
        h.seq->setPatternSettings(1, 0, 16, c.rate, shuffle, 0.5f);
        std::array<float, 16> nudge{};
        for (int i = 0; i < 16; ++i) {
            nudge[static_cast<std::size_t>(i)] = static_cast<float>((i * 7) % 11 - 5) * 0.1f;  // -0.5 .. 0.5
            h.seq->setStep(1, 0, i, {Hit{60 + i, 0.8f, 0.25f, 1.0f, nudge[static_cast<std::size_t>(i)]}});
        }
        h.seq->play();
        h.run(c.seconds * kRate, c.block);

        const int64_t tenths = Sequencer::tempoTenths(c.bpm);
        const int64_t units = stepUnits(c.spb);
        const int64_t shuffleUnits = units / 1500 * 500;
        const int64_t total = c.seconds * kRate;
        std::size_t checked = 0;
        for (int i = 0; i < 16; ++i) {
            const auto ons = h.slot(1).noteOns(60 + i);
            const auto offs = h.slot(1).noteOffs(60 + i);
            std::size_t pass = 0;
            for (;; ++pass) {
                const int64_t k = static_cast<int64_t>(pass) * 16 + i;
                const int64_t pos = k * units + (k % 2 == 1 ? shuffleUnits : 0) +
                                    nudgeUnits(nudge[static_cast<std::size_t>(i)], units);
                // The first pass's early step 0 can't sound before Play: it sounds at Play.
                const int64_t fired = std::max<int64_t>(0, pos);
                const int64_t expected = frameAt(fired, tenths);
                if (expected >= total) break;
                ASSERT_LT(pass, ons.size()) << c.bpm << " BPM, step " << k;
                ASSERT_EQ(ons[pass].frame, expected) << c.bpm << " BPM, step " << k;
                const int64_t off = frameAt(fired + units / 4, tenths);  // length 1/4 step
                if (off < total) {
                    ASSERT_LT(pass, offs.size());
                    ASSERT_EQ(offs[pass].frame, off) << c.bpm << " BPM, step " << k;
                }
                ++checked;
            }
            ASSERT_EQ(ons.size(), pass) << i;
        }
        EXPECT_GT(checked, 400u);
    }
}

// An early hit is scheduled when the step before fires, for the step that really comes
// next: across the length wrap, a queued pattern switch and a step loop.
TEST(SequencerHits, EarlyHitsLookAheadAcrossWrapSwitchAndLoop) {
    {
        SeqHarness h;
        h.seq->setPatternSettings(1, 0, 12, 1, 0.0f, 0.5f);
        h.seq->setStep(1, 0, 0, {Hit{70, 0.8f, 0.25f, 1.0f, -0.5f}});
        h.seq->setStep(1, 1, 0, {Hit{80, 0.8f, 0.25f, 1.0f, -0.25f}});
        h.seq->play();
        h.run(12 * kStep * 2 + 100);
        EXPECT_EQ(framesOf(h.slot(1).noteOns(70)), (std::vector<int64_t>{0, 12 * kStep - 3000, 24 * kStep - 3000}));

        // Queue pattern 1 (16 steps): its early step 0 comes 1/4 step before the boundary,
        // and pattern 0's step 0 doesn't sound there.
        h.slot(1).clear();
        h.seq->selectPattern(1, 1);
        h.run(12 * kStep + 16 * kStep);
        EXPECT_TRUE(h.slot(1).noteOns(70).empty());
        EXPECT_EQ(framesOf(h.slot(1).noteOns(80)), (std::vector<int64_t>{36 * kStep - 1500, 52 * kStep - 1500}));
    }
    {
        SeqHarness h;
        h.seq->setStep(1, 0, 1, {Hit{71, 0.8f, 0.25f, 1.0f, -0.25f}});
        h.seq->setStep(1, 0, 5, {Hit{75, 0.8f, 0.25f, 1.0f, -0.5f}});
        h.seq->play();
        h.run(100);  // step 0 played
        h.seq->setStepLoop(1, 5, 1);
        h.runTo(11 * kStep + 100);  // 1 2 3 4 5 | 1 2 3 4 5 | 1
        // Step 1 again right after the loop's step 5: early, before 36000 and 66000.
        EXPECT_EQ(framesOf(h.slot(1).noteOns(71)), (std::vector<int64_t>{kStep - 1500, 6 * kStep - 1500, 11 * kStep - 1500}));
        EXPECT_EQ(framesOf(h.slot(1).noteOns(75)), (std::vector<int64_t>{5 * kStep - 3000, 10 * kStep - 3000}));
    }
}

// Documented limits: what the look-ahead scheduled isn't recalled by a later switch, and an
// early hit it didn't schedule sounds at its own step's time.
TEST(SequencerHits, LookAheadIsNotRecalledAndMissedEarlyHitsSoundOnTheirStep) {
    SeqHarness h;
    h.seq->setStep(1, 0, 0, {Hit{70, 0.8f, 0.25f, 1.0f, -0.5f}});
    h.seq->setStep(1, 1, 0, {Hit{80, 0.8f, 0.25f, 1.0f, -0.5f}});
    h.seq->play();
    h.runTo(15 * kStep + 1000);  // step 15 fired and looked ahead to pattern 0's step 0
    h.seq->selectPattern(1, 1);
    h.runTo(16 * kStep + 1);
    EXPECT_EQ(framesOf(h.slot(1).noteOns(70)), (std::vector<int64_t>{0, 16 * kStep - 3000}));
    EXPECT_EQ(framesOf(h.slot(1).noteOns(80)), (std::vector<int64_t>{16 * kStep}));
    h.runTo(32 * kStep + 1);
    EXPECT_EQ(framesOf(h.slot(1).noteOns(80)), (std::vector<int64_t>{16 * kStep, 32 * kStep - 3000}));

    // A hit made early after the look-ahead passed its step: on the step this time, then early.
    h.runTo(32 * kStep + 3 * kStep + 100);
    h.seq->setStep(1, 1, 4, {Hit{90, 0.8f, 0.25f, 1.0f, -0.25f}});
    h.runTo(48 * kStep + 4 * kStep + 1);
    EXPECT_EQ(framesOf(h.slot(1).noteOns(90)), (std::vector<int64_t>{36 * kStep, 52 * kStep - 1500}));
}

// Shuffle above 75%: an even step's hit nudged before its odd predecessor sounds with it.
TEST(SequencerHits, HeavyShuffleEarlyHitSoundsWithThePreviousStep) {
    SeqHarness h;
    h.seq->setPatternSettings(1, 0, 16, 1, 1.0f, 0.5f);  // step 1 at 6000 * 5/3 = 10000
    h.seq->setStep(1, 0, 1, {Hit{61}});
    h.seq->setStep(1, 0, 2, {Hit{62, 0.8f, 0.25f, 1.0f, -0.5f}});  // exact time 9000
    h.seq->setStep(1, 0, 4, {Hit{64, 0.8f, 0.25f, 1.0f, -0.3f}});  // 24000 - 1800 > step 3 (22000)
    h.seq->play();
    h.run(5 * kStep);
    EXPECT_EQ(framesOf(h.slot(1).noteOns(61)), (std::vector<int64_t>{10000}));
    EXPECT_EQ(framesOf(h.slot(1).noteOns(62)), (std::vector<int64_t>{10000}));
    EXPECT_EQ(framesOf(h.slot(1).noteOffs(62)), (std::vector<int64_t>{10000 + 1500}));  // length from when it sounded
    EXPECT_EQ(framesOf(h.slot(1).noteOns(64)), (std::vector<int64_t>{24000 - 1800}));
}

// A late hit waiting for its time moves with the clock when the tempo changes.
TEST(SequencerHits, TempoChangeMovesAWaitingHitWithTheClock) {
    SeqHarness h;
    h.seq->setStep(1, 0, 1, {Hit{61, 0.8f, 0.25f, 1.0f, 0.5f}});  // at 9000 at 120 BPM
    h.seq->play();
    h.runTo(6100);
    h.seq->setTempo(90.0f);
    h.run(10000);
    const int64_t pos = stepUnits() * 3 / 2;
    const int64_t change = 6100;
    EXPECT_EQ(framesOf(h.slot(1).noteOns(61)), (std::vector<int64_t>{change + ceilDiv(pos - change * 60 * 1200, 60 * 900)}));
}

// --- length ---

TEST(SequencerHits, LengthIsInStepsAtTheTempoItSoundedAt) {
    SeqHarness h;
    h.seq->setPatternSettings(1, 0, 4, 1, 0.0f, 0.5f);
    h.seq->setStep(1, 0, 0, {Hit{60, 0.8f, Hit::kPatternGate}});  // 0.5 step
    h.seq->setStep(1, 0, 1, {Hit{61, 0.8f, 2.5f}});
    h.seq->setStep(1, 0, 2, {Hit{62, 0.8f, 16.0f}});
    h.seq->setStep(1, 0, 3, {Hit{63, 0.8f, 4.0f}});
    h.seq->play();
    h.runTo(2 * kStep + 1000);
    h.seq->setStep(1, 0, 2, {});  // 62 sounds once: its off comes long after the pattern end
    h.runTo(3 * kStep + 1000);
    h.seq->setTempo(60.0f);  // after 63 sounded: its off stays 4 steps at 120 BPM
    h.run(30 * kStep);
    EXPECT_EQ(h.slot(1).noteOffs(60)[0].frame, 3000);
    EXPECT_EQ(h.slot(1).noteOffs(61)[0].frame, kStep + 15000);
    EXPECT_EQ(framesOf(h.slot(1).noteOns(62)), (std::vector<int64_t>{2 * kStep}));
    EXPECT_EQ(framesOf(h.slot(1).noteOffs(62)), (std::vector<int64_t>{2 * kStep + 16 * kStep}));
    EXPECT_EQ(h.slot(1).noteOffs(63)[0].frame, 3 * kStep + 4 * kStep);
}

// The shortest note there is: 1/16 of a 1/32 step at 240 BPM (93.75 frames), exact. (Above
// kMinNoteSeconds at every supported rate, so that floor only guards rounding.)
TEST(SequencerHits, ShortestLengthIsExactAtTheFastestStep) {
    SeqHarness h;
    h.seq->setTempo(240.0f);
    h.seq->setPatternSettings(1, 0, 4, 2, 0.0f, 0.5f);  // 1/32: 1500 frames
    h.seq->setStep(1, 0, 0, {Hit{60, 0.8f, Hit::kMinLength}});
    h.seq->setStep(1, 0, 1, {Hit{61, 0.8f, 0.125f}});  // 187.5 frames
    h.seq->play();
    h.run(3000);
    EXPECT_EQ(h.slot(1).noteOffs(60)[0].frame, 94);
    EXPECT_EQ(h.slot(1).noteOffs(61)[0].frame, 1500 + 188);
    EXPECT_LT(Sequencer::kMinNoteSeconds * 48000.0f, 94.0f);
}

// --- retrigger and pending limits ---

TEST(SequencerHits, RetriggerOfAHeldNoteSendsItsOffFirst) {
    SeqHarness h;
    h.seq->setPatternSettings(1, 0, 4, 1, 0.0f, 0.5f);
    h.seq->setStep(1, 0, 0, {Hit{60, 0.8f, 4.0f}});
    h.seq->setStep(1, 0, 1, {Hit{60, 0.8f, 4.0f}});
    h.seq->play();
    h.runTo(4 * kStep + 1);
    const auto& inst = h.slot(1);
    EXPECT_EQ(framesOf(inst.noteOns(60)), (std::vector<int64_t>{0, kStep, 4 * kStep}));
    EXPECT_EQ(framesOf(inst.noteOffs(60)), (std::vector<int64_t>{kStep, 4 * kStep}));
    for (int64_t f : {kStep, 4 * kStep}) {
        const int off = indexOf(inst, SeqCall::Type::NoteOff, 60, f);
        const int on = indexOf(inst, SeqCall::Type::NoteOn, 60, f);
        ASSERT_GE(off, 0);
        EXPECT_LT(off, on) << f;
    }
    h.runTo(5 * kStep + 1);
    EXPECT_EQ(framesOf(inst.noteOffs(60)), (std::vector<int64_t>{kStep, 4 * kStep, 5 * kStep}));
}

// 8 hits on each of 16 steps, every one 16 steps long: all 128 MIDI notes sound at once and
// each ends exactly on time (no pending off is released early).
TEST(SequencerHits, EveryMidiNoteCanBeHeldAtOnce) {
    SeqHarness h;
    for (int s = 0; s < 16; ++s) {
        std::array<Hit, kMaxHitsPerStep> hits{};
        for (int j = 0; j < kMaxHitsPerStep; ++j) hits[static_cast<std::size_t>(j)] = Hit{s * 8 + j, 0.8f, 16.0f};
        h.seq->setStep(1, 0, s, hits.data(), kMaxHitsPerStep);
    }
    h.seq->play();
    h.runTo(16 * kStep);
    EXPECT_EQ(h.slot(1).noteOns().size(), 128u);
    EXPECT_TRUE(h.slot(1).noteOffs().empty());
    h.runTo(32 * kStep);
    for (int n = 0; n < 128; ++n) {
        const auto offs = h.slot(1).noteOffs(n);
        ASSERT_EQ(offs.size(), 1u) << n;
        EXPECT_EQ(offs[0].frame, h.slot(1).noteOns(n)[0].frame + 16 * kStep) << n;
    }
}

// --- chance ---

TEST(SequencerHits, ChanceIsRolledPerHitAndRendersRepeat) {
    SeqHarness h;
    h.seq->setTempo(240.0f);
    h.seq->setPatternSettings(kDrumSlot, 0, 16, 2, 0.0f, 0.5f);  // 1/32: 1500 frames per step
    for (int s = 0; s < 16; ++s) h.seq->setStep(kDrumSlot, 0, s, {Hit{48, 0.8f, -1.0f, 0.5f}, Hit{50, 0.8f, -1.0f, 0.5f}});
    h.seq->play();
    const int64_t steps = 10000;
    h.run(steps * 1500 - 1, 1024);
    const auto kick = framesOf(h.slot(kDrumSlot).noteOns(48));
    const auto snare = framesOf(h.slot(kDrumSlot).noteOns(50));
    std::size_t both = 0;
    for (int64_t f : kick) both += static_cast<std::size_t>(std::count(snare.begin(), snare.end(), f));
    EXPECT_NEAR(static_cast<double>(kick.size()) / steps, 0.5, 0.02);
    EXPECT_NEAR(static_cast<double>(snare.size()) / steps, 0.5, 0.02);
    EXPECT_NEAR(static_cast<double>(both) / steps, 0.25, 0.02) << "independent per hit";

    h.seq->stop();
    h.apply();
    h.slot(kDrumSlot).clear();
    const int64_t restart = h.frame;
    h.seq->play();
    h.run(steps * 1500 - 1, 1024);
    auto again = framesOf(h.slot(kDrumSlot).noteOns(48));
    for (int64_t& f : again) f -= restart;
    EXPECT_EQ(again, kick);
}

// --- stop ---

TEST(SequencerHits, StopReleasesSoundingNotesAndDropsWaitingHits) {
    SeqHarness h;
    h.seq->setStep(1, 0, 3, {Hit{63, 0.8f, 0.25f, 1.0f, 0.375f}});  // at 20250
    h.seq->setStep(1, 0, 4, {Hit{64, 0.8f, 0.25f, 1.0f, -0.5f}});  // at 21000
    h.seq->play();
    h.runTo(19000);
    h.seq->stop();
    h.run(10000);
    EXPECT_TRUE(h.slot(1).noteOns(63).empty());
    EXPECT_TRUE(h.slot(1).noteOns(64).empty());
    EXPECT_FALSE(h.slot(1).of(SeqCall::Type::AllNotesOff).empty());

    const int64_t restart = h.frame;
    h.seq->play();
    h.run(4 * kStep);
    EXPECT_EQ(framesOf(h.slot(1).noteOns(64)), (std::vector<int64_t>{restart + 21000}));
    EXPECT_EQ(framesOf(h.slot(1).noteOns(63)), (std::vector<int64_t>{restart + 20250}));
}

// --- live record ---

// A note recorded onto a step replaces that note's waiting hit (it already sounds from the
// key); the step's other hits still play, and the hit plays again next pass.
TEST(SequencerHits, LiveNoteTakesOverItsWaitingHit) {
    SeqHarness h;
    h.seq->setStep(1, 0, 2, {Hit{64, 0.8f, 0.25f, 1.0f, 0.25f}, Hit{67, 0.8f, 0.25f, 1.0f, 0.25f}});  // 13500
    h.seq->setRecordArmed(true);
    h.seq->play();
    h.runTo(12100);
    h.seq->recordLiveNote(1, 64, 0.9f, h.frame);  // nearest: step 2
    h.runTo(16 * kStep + 14000);
    EXPECT_EQ(framesOf(h.slot(1).noteOns(64)), (std::vector<int64_t>{16 * kStep + 13500}));
    EXPECT_EQ(framesOf(h.slot(1).noteOns(67)), (std::vector<int64_t>{13500, 16 * kStep + 13500}));
    EXPECT_EQ(h.seq->pattern(1, 0).steps[2].hitCount, 2);  // already there: not added
}

// Synth slots: one chance roll per step, shared by the chord (the first hit's chance), early
// and late notes alike; the drum slot rolls per hit (above). Renders still repeat.
TEST(SequencerHits, SynthChordChanceIsOneRollPerStep) {
    SeqHarness h;
    h.seq->setTempo(240.0f);
    h.seq->setPatternSettings(1, 0, 16, 2, 0.0f, 0.5f);  // 1/32: 1500 frames per step
    for (int s = 0; s < 16; ++s) {
        h.seq->setStep(1, 0, s,
                       {Hit{60, 0.8f, 0.25f, 0.5f, -0.25f}, Hit{64, 0.8f, 0.25f, 0.5f, 0.0f},
                        Hit{67, 0.8f, 0.25f, 0.5f, 0.25f}});
    }
    h.seq->play();
    const int64_t steps = 4000;
    h.run(steps * 1500 - 1000, 1024);
    const auto a = framesOf(h.slot(1).noteOns(60));
    const auto b = framesOf(h.slot(1).noteOns(64));
    const auto c = framesOf(h.slot(1).noteOns(67));
    ASSERT_EQ(a.size(), b.size());
    ASSERT_EQ(b.size(), c.size());
    for (std::size_t i = 0; i < b.size(); ++i) {
        ASSERT_EQ(a[i], b[i] - 375) << "all of the chord or none";
        ASSERT_EQ(c[i], b[i] + 375);
    }
    EXPECT_NEAR(static_cast<double>(b.size()) / steps, 0.5, 0.03);

    h.seq->stop();
    h.apply();
    h.slot(1).clear();
    const int64_t restart = h.frame;
    h.seq->play();
    h.run(steps * 1500 - 1000, 1024);
    auto again = framesOf(h.slot(1).noteOns(64));
    for (int64_t& f : again) f -= restart;
    EXPECT_EQ(again, b);
}
