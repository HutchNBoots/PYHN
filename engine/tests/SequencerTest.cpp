// Sequencer (docs/feature-spec.md F3): clock, rates, shuffle, gate, probability, pattern
// switching, step loop, live and motion recording, transport readout. All offline, with
// logging instruments (SequencerTestUtil.h).

#include "groove/Sequencer.h"

#include <gtest/gtest.h>

#include <array>
#include <atomic>
#include <cmath>
#include <limits>
#include <thread>
#include <vector>

#include "SequencerTestUtil.h"
#include "groove/DrumMachine.h"
#include "groove/SynthParams.h"

using namespace groove;
using namespace groove::test;

namespace {

constexpr int64_t kRate = 48000;
constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();

// One synth slot, pattern 0: length 16 at `rate`, every step on (note 60 + step).
void simplePattern(SeqHarness& h, int slot = 1, int rate = 1, float shuffle = 0.0f, int length = 16) {
    h.seq->setPatternSettings(slot, 0, length, rate, shuffle, 0.5f);
    h.fillSteps(slot, 0, 60);
}

std::vector<int64_t> frames(const std::vector<SeqCall>& calls) {
    std::vector<int64_t> f;
    for (const SeqCall& c : calls) f.push_back(c.frame);
    return f;
}

}  // namespace

// --- F3.1 clock ---

// Every step of 10 minutes lands exactly on its computed frame, at several tempos
// (including awkward tenths) and callback sizes: zero drift.
TEST(SequencerClock, ZeroDriftOverTenMinutesAtAnyTempo) {
    struct Case {
        float bpm;
        int block;
    };
    for (const Case c : {Case{40.0f, 96}, Case{97.3f, 96}, Case{120.0f, 37}, Case{133.7f, 192}, Case{171.1f, 96},
                         Case{240.0f, 61}}) {
        SeqHarness h;
        simplePattern(h);
        h.seq->setTempo(c.bpm);
        h.run(1234, c.block);  // Play at an arbitrary frame, mid-callback
        h.seq->play();
        const int64_t start = h.frame;
        h.run(10 * 60 * kRate, c.block);

        const int64_t tenths = Sequencer::tempoTenths(c.bpm);
        const auto ons = h.slot(1).noteOns();
        const int64_t expectedCount = (10 * 60 * kRate - 1) * 4 * tenths / (600 * kRate) + 1;
        ASSERT_NEAR(static_cast<double>(ons.size()), static_cast<double>(expectedCount), 1.0) << c.bpm;
        for (std::size_t k = 0; k < ons.size(); ++k) {
            ASSERT_EQ(ons[k].frame - start, expectedStepFrame(static_cast<int64_t>(k), kRate, tenths, 4))
                << c.bpm << " BPM, step " << k;
            ASSERT_EQ(ons[k].note, 60 + static_cast<int>(k % 16));
        }
        // And against wall-clock time, not just self-consistency: the last step is within one
        // frame of k * 60 / (bpm * 4) seconds.
        const double k = static_cast<double>(ons.size() - 1);
        const double ideal = k * 60.0 * kRate / (static_cast<double>(tenths) / 10.0 * 4.0);
        EXPECT_LT(std::fabs(static_cast<double>(ons.back().frame - start) - ideal), 1.0) << c.bpm;
    }
}

TEST(SequencerClock, TempoRunsFrom40To240InTenthSteps) {
    EXPECT_EQ(Sequencer::tempoTenths(10.0f), 400);
    EXPECT_EQ(Sequencer::tempoTenths(300.0f), 2400);
    EXPECT_EQ(Sequencer::tempoTenths(120.04f), 1200);
    EXPECT_EQ(Sequencer::tempoTenths(120.06f), 1201);
    EXPECT_EQ(Sequencer::tempoTenths(133.7f), 1337);
    EXPECT_EQ(Sequencer::tempoTenths(kNaN), 1200);

    SeqHarness h;
    h.seq->setTempo(97.3f);
    h.apply();
    EXPECT_NEAR(h.seq->transport().tempoBpm, 97.3f, 1e-4f);
    const auto tempo = h.slot(3).of(SeqCall::Type::Tempo);
    ASSERT_FALSE(tempo.empty());
    EXPECT_NEAR(tempo.back().value, 97.3f, 1e-4f);
    h.seq->setTempo(250.0f);
    h.apply();
    EXPECT_FLOAT_EQ(h.seq->transport().tempoBpm, 240.0f);
}

// A tempo change re-anchors at the frame it is applied; steps after it are exact again.
TEST(SequencerClock, TempoChangeWhilePlayingStaysExact) {
    SeqHarness h;
    simplePattern(h);
    h.seq->play();
    h.run(10000);  // steps 0 and 1 at 120 BPM (6000 frames per step)
    const int64_t change = h.frame;
    h.seq->setTempo(90.0f);
    h.run(48000);
    const auto ons = h.slot(1).noteOns();
    ASSERT_GE(ons.size(), 6u);
    EXPECT_EQ(ons[0].frame, 0);
    EXPECT_EQ(ons[1].frame, 6000);
    const int64_t units = h.seq->unitsPerBeat() / 4;
    const int64_t changePos = change * 60 * 1200;
    for (std::size_t k = 2; k < ons.size(); ++k) {
        const int64_t pos = static_cast<int64_t>(k) * units;
        EXPECT_EQ(ons[k].frame, change + ceilDiv(pos - changePos, 60 * 900)) << k;
    }
    EXPECT_EQ(ons[3].frame - ons[2].frame, 8000);  // 1/16 at 90 BPM
}

// --- F3.2 rates and length ---

TEST(SequencerClock, EveryStepRateIsExact) {
    const int64_t spb[] = {2, 4, 8, 6};  // 1/8, 1/16, 1/32, 1/16T
    for (int rate = 0; rate < 4; ++rate) {
        SeqHarness h;
        simplePattern(h, 1, rate);
        h.seq->setTempo(127.3f);
        h.seq->play();
        h.run(4 * kRate);
        const auto ons = h.slot(1).noteOns();
        ASSERT_GT(ons.size(), 10u);
        for (std::size_t k = 0; k < ons.size(); ++k) {
            ASSERT_EQ(ons[k].frame, expectedStepFrame(static_cast<int64_t>(k), kRate, 1273, spb[rate]))
                << "rate " << rate << " step " << k;
        }
    }
}

TEST(SequencerPattern, LengthFrom1To64Loops) {
    for (int length : {1, 5, 16, 33, 64}) {
        SeqHarness h;
        simplePattern(h, 2, 2, 0.0f, length);
        h.seq->setTempo(240.0f);
        h.seq->play();
        h.run(static_cast<int64_t>(3 * length) * 1500 - 1);  // 1/32 at 120 BPM: 1500 frames
        const auto ons = h.slot(2).noteOns();
        ASSERT_EQ(ons.size(), static_cast<std::size_t>(3 * length));
        for (std::size_t k = 0; k < ons.size(); ++k) ASSERT_EQ(ons[k].note, 60 + static_cast<int>(k) % length);
    }
    SeqHarness h;
    h.seq->setPatternSettings(1, 3, 0, 9, kNaN, 7.0f);
    h.apply();
    EXPECT_EQ(h.seq->pattern(1, 3).length, 1);
    EXPECT_EQ(h.seq->pattern(1, 3).rate, StepRate::SixteenthTriplet);
    EXPECT_EQ(h.seq->pattern(1, 3).shuffleMilli, 0);
    EXPECT_FLOAT_EQ(h.seq->pattern(1, 3).gate, 1.0f);
    h.seq->setPatternSettings(1, 3, 100, -1, 2.0f, 0.0f);
    h.apply();
    EXPECT_EQ(h.seq->pattern(1, 3).length, 64);
    EXPECT_EQ(h.seq->pattern(1, 3).rate, StepRate::Eighth);
    EXPECT_EQ(h.seq->pattern(1, 3).shuffleMilli, 1000);
    EXPECT_FLOAT_EQ(h.seq->pattern(1, 3).gate, 0.05f);
}

TEST(SequencerPattern, ShorteningWhilePlayingWrapsAtTheNextStep) {
    SeqHarness h;
    simplePattern(h);
    h.seq->play();
    h.run(6000 * 10 + 1);  // steps 0..10 played
    h.seq->setPatternSettings(1, 0, 8, 1, 0.0f, 0.5f);
    h.run(6000 * 3);
    const auto ons = h.slot(1).noteOns();
    ASSERT_EQ(ons.size(), 14u);
    EXPECT_EQ(ons[11].note, 60);  // past the new end: back to step 0, on time
    EXPECT_EQ(ons[11].frame, 66000);
    EXPECT_EQ(ons[12].note, 61);
}

// --- F3.3 shuffle ---

TEST(SequencerClock, ShuffleDelaysOddStepsByUpToTwoThirdsExactly) {
    const int64_t spb[] = {2, 4, 8, 6};
    for (int rate = 0; rate < 4; ++rate) {
        for (float shuffle : {0.0f, 0.25f, 0.5f, 0.733f, 1.0f}) {
            SeqHarness h;
            simplePattern(h, 1, rate, shuffle);
            h.seq->setTempo(111.1f);
            h.seq->play();
            h.run(3 * kRate);
            const auto ons = h.slot(1).noteOns();
            const auto milli = static_cast<int64_t>(std::lround(shuffle * 1000.0f));
            ASSERT_GT(ons.size(), 8u);
            for (std::size_t i = 0; i < ons.size(); ++i) {
                const auto k = static_cast<int64_t>(i);
                // position (in 1/3000 of a step): 3000 k, plus 2 * milli on odd steps
                const int64_t steps3000 = 3000 * k + (k % 2 == 1 ? 2 * milli : 0);
                const int64_t expected = ceilDiv(steps3000 * 600 * kRate, 3000 * spb[rate] * 1111);
                ASSERT_EQ(ons[i].frame, expected) << "rate " << rate << " shuffle " << shuffle << " step " << k;
            }
            if (shuffle == 1.0f) {
                // Odd steps sit 2/3 of a step late (a triplet feel), within a frame.
                const double stepFrames = 600.0 * kRate / (static_cast<double>(spb[rate]) * 1111.0);
                EXPECT_NEAR(static_cast<double>(ons[1].frame - ons[0].frame), stepFrames * 5.0 / 3.0, 1.0);
            }
        }
    }
}

// --- gate and note-offs ---

TEST(SequencerNotes, NoteOffComesAtLengthTimesStepLength) {
    SeqHarness h;
    h.seq->setPatternSettings(1, 0, 4, 1, 0.0f, 0.5f);
    const int note = 50;
    h.seq->setStep(1, 0, 0, {Hit{note, 1.0f, Hit::kPatternGate}});  // pattern gate 0.5
    h.seq->setStep(1, 0, 1, {Hit{note, 1.0f, 0.25f}});              // its own length
    h.seq->setStep(1, 0, 2, {Hit{note, 1.0f, 1.0f}});               // one step
    h.seq->setStep(1, 0, 3, {Hit{note, 1.0f, Hit::kMinLength}});    // shortest: 1/16 step
    h.seq->play();
    h.run(4 * 6000);
    const auto ons = h.slot(1).noteOns();
    const auto offs = h.slot(1).noteOffs();
    ASSERT_EQ(ons.size(), 4u);
    ASSERT_EQ(offs.size(), 4u);
    EXPECT_EQ(offs[0].frame, 3000);
    EXPECT_EQ(offs[1].frame, 6000 + 1500);
    EXPECT_EQ(offs[2].frame, 18000);  // exactly when step 3 starts, and before its note-on
    EXPECT_EQ(offs[3].frame, 18000 + 375);
    // At frame 18000 the off of step 2 precedes the on of step 3.
    const auto& calls = h.slot(1).calls;
    std::size_t offAt = 0, onAt = 0;
    for (std::size_t i = 0; i < calls.size(); ++i) {
        if (calls[i].frame != 18000) continue;
        if (calls[i].type == SeqCall::Type::NoteOff) offAt = i;
        if (calls[i].type == SeqCall::Type::NoteOn) onAt = i;
    }
    EXPECT_LT(offAt, onAt);
}

// With shuffle, an odd step's gate can outlast the next step's start. A retrigger of the same
// note takes it over: the earlier note's off must not cut the new one.
TEST(SequencerNotes, RetriggerIsNotCutByThePreviousGate) {
    SeqHarness h;
    h.seq->setPatternSettings(1, 0, 16, 1, 1.0f, 1.0f);
    const int note = 60;
    for (int i = 0; i < 16; ++i) h.seq->setStep(1, 0, i, {Hit{note, 1.0f}});
    h.seq->play();
    h.run(16 * 6000);
    bool held = false;
    int64_t onFrame = 0;
    for (const SeqCall& c : h.slot(1).calls) {
        if (c.type == SeqCall::Type::NoteOn) {
            held = true;
            onFrame = c.frame;
        } else if (c.type == SeqCall::Type::NoteOff) {
            ASSERT_TRUE(held);
            EXPECT_GE(c.frame - onFrame, 1999) << "an off must belong to the latest on";
            held = false;
        }
    }
}

TEST(SequencerNotes, StopReleasesNotesClearsMotionAndRewinds) {
    SeqHarness h;
    simplePattern(h);
    h.seq->play();
    h.run(6000 * 3 + 10);
    h.seq->stop();
    h.apply();
    EXPECT_FALSE(h.slot(1).of(SeqCall::Type::AllNotesOff).empty());
    EXPECT_FALSE(h.slot(1).of(SeqCall::Type::ClearMotion).empty());
    const TransportState t = h.seq->transport();
    EXPECT_FALSE(t.playing);
    EXPECT_EQ(t.currentStep[1], -1);
    h.slot(1).clear();
    h.run(48000);
    EXPECT_TRUE(h.slot(1).noteOns().empty());
    h.seq->play();
    h.run(10);
    ASSERT_EQ(h.slot(1).noteOns().size(), 1u);
    EXPECT_EQ(h.slot(1).noteOns()[0].note, 60);  // from step 0 again
}

// --- F3.4 probability ---

TEST(SequencerProbability, TenThousandPlaysMatchTheSettingWithin2Percent) {
    for (float p : {0.1f, 0.25f, 0.5f, 0.75f, 0.9f}) {
        SeqHarness h;
        h.seq->setTempo(240.0f);
        h.seq->setPatternSettings(1, 0, 16, 2, 0.0f, 0.5f);  // 1/32: 1500 frames per step
        h.fillSteps(1, 0, 60, p);
        h.seq->play();
        h.run(10000 * 1500 - 1, 1024);
        const double rate = static_cast<double>(h.slot(1).noteOns().size()) / 10000.0;
        EXPECT_NEAR(rate, p, 0.02) << p;
    }
}

TEST(SequencerProbability, ZeroAndOneAreExactAndRendersRepeat) {
    SeqHarness h;
    h.seq->setPatternSettings(1, 0, 4, 2, 0.0f, 0.5f);
    h.seq->setPatternSettings(2, 0, 4, 2, 0.0f, 0.5f);
    h.seq->setPatternSettings(3, 0, 4, 2, 0.0f, 0.5f);
    h.fillSteps(1, 0, 60, 0.0f);
    h.fillSteps(2, 0, 60, 1.0f);
    h.fillSteps(3, 0, 60, 0.5f);
    h.seq->setPatternSettings(4, 0, 4, 2, 0.0f, 0.5f);
    h.fillSteps(4, 0, 60, 0.5f);
    h.seq->setTempo(240.0f);  // 1/32: 1500 frames per step
    h.seq->play();
    h.run(400 * 1500 - 1);
    EXPECT_TRUE(h.slot(1).noteOns().empty());
    EXPECT_EQ(h.slot(2).noteOns().size(), 400u);
    const auto first = frames(h.slot(3).noteOns());
    EXPECT_NE(first, frames(h.slot(4).noteOns())) << "each slot has its own generator";

    // Play again: seeded on play, so the same steps sound.
    h.seq->stop();
    h.apply();
    h.slot(3).clear();
    const int64_t restart = h.frame;
    h.seq->play();
    h.run(400 * 1500 - 1);
    auto second = frames(h.slot(3).noteOns());
    for (int64_t& f : second) f -= restart;
    EXPECT_EQ(first, second);
}

// --- F3.9 pattern switching ---

TEST(SequencerPattern, SwitchWhilePlayingWaitsForThePatternEnd) {
    SeqHarness h;
    simplePattern(h);
    h.fillSteps(1, 1, 80);
    h.seq->setPatternSettings(2, 0, 12, 1, 0.0f, 0.5f);  // another slot, a different length
    h.fillSteps(2, 0, 30);
    h.fillSteps(2, 1, 40);
    h.seq->play();
    h.run(6000 * 5 + 100);  // mid-pattern
    h.seq->selectPattern(1, 1);
    h.seq->selectPattern(2, 1);
    h.apply();
    TransportState t = h.seq->transport();
    EXPECT_EQ(t.currentPattern[1], 0);
    EXPECT_EQ(t.queuedPattern[1], 1);
    h.run(6000 * 16);
    const auto ons = h.slot(1).noteOns();
    ASSERT_EQ(ons.size(), 22u);
    for (std::size_t k = 0; k < 16; ++k) EXPECT_EQ(ons[k].note, 60 + static_cast<int>(k)) << "never mid-pattern";
    EXPECT_EQ(ons[16].note, 80);
    EXPECT_EQ(ons[16].frame, 16 * 6000);
    const auto ons2 = h.slot(2).noteOns();
    EXPECT_EQ(ons2[11].note, 30 + 11);  // step 11 of pattern 0
    EXPECT_EQ(ons2[12].note, 40);  // slot 2 switches at its own (12-step) boundary
    EXPECT_EQ(ons2[12].frame, 12 * 6000);
    t = h.seq->transport();
    EXPECT_EQ(t.currentPattern[1], 1);
    EXPECT_EQ(t.queuedPattern[1], -1);
}

TEST(SequencerPattern, SelectWhileStoppedIsImmediateAndReselectCancels) {
    SeqHarness h;
    h.seq->selectPattern(1, 5);
    h.apply();
    EXPECT_EQ(h.seq->transport().currentPattern[1], 5);
    EXPECT_EQ(h.seq->transport().queuedPattern[1], -1);
    simplePattern(h);
    h.seq->selectPattern(1, 0);
    h.seq->play();
    h.run(100);
    h.seq->selectPattern(1, 3);
    h.apply();
    EXPECT_EQ(h.seq->transport().queuedPattern[1], 3);
    h.seq->selectPattern(1, 0);
    h.apply();
    EXPECT_EQ(h.seq->transport().queuedPattern[1], -1);
    h.seq->selectPattern(1, 2);
    h.seq->stop();  // a queued pattern becomes current on stop
    h.apply();
    EXPECT_EQ(h.seq->transport().currentPattern[1], 2);
}

// --- F3.8 step loop ---

TEST(SequencerLoop, LoopsTheRangeAndReturnsToTheVirtualPosition) {
    for (float shuffle : {0.0f, 0.5f}) {
        SeqHarness h;
        simplePattern(h, 1, 1, shuffle);
        h.seq->play();
        h.run(6000 * 4 + 100);  // steps 0..4 played
        h.seq->setStepLoop(1, 9, 8);  // either order
        h.run(6000 * 6);  // six looped steps (virtual 5..10)
        h.seq->setStepLoop(1, -1, -1);
        h.run(6000 * 3 + 2500);
        const auto ons = h.slot(1).noteOns();
        ASSERT_EQ(ons.size(), 14u);
        const int expectedSteps[] = {0, 1, 2, 3, 4, 8, 9, 8, 9, 8, 9, 11, 12, 13};
        const auto milli = static_cast<int64_t>(std::lround(shuffle * 1000.0f));
        for (std::size_t k = 0; k < ons.size(); ++k) {
            EXPECT_EQ(ons[k].note, 60 + expectedSteps[k]) << k;
            const auto kk = static_cast<int64_t>(k);
            const int64_t expected = ceilDiv((3000 * kk + (kk % 2 == 1 ? 2 * milli : 0)) * 600 * kRate, 3000 * 4 * 1200);
            EXPECT_EQ(ons[k].frame, expected) << "still in time, step " << k;
        }
    }
}

// --- F3.6 live record ---

TEST(SequencerRecord, LiveNoteGoesToTheNearestStepByExactFrame) {
    SeqHarness h;
    h.seq->setPatternSettings(1, 0, 16, 1, 0.0f, 0.5f);
    h.seq->setRecordArmed(true);
    h.seq->play();
    h.runTo(6000 * 2 + 2999);  // step 2 at 12000, step 3 at 18000: 2999 frames after step 2
    h.seq->recordLiveNote(1, 64, 0.7f, h.frame);
    h.runTo(6000 * 2 + 3000);  // exactly halfway: ties go to the earlier step
    h.seq->recordLiveNote(1, 65, 0.6f, h.frame);
    h.runTo(6000 * 2 + 3001);  // just past halfway: the next step
    h.seq->recordLiveNote(1, 67, 0.5f, h.frame);

    const auto rec = h.recorded();
    ASSERT_EQ(rec.size(), 3u);
    EXPECT_EQ(rec[0].step, 2);
    EXPECT_EQ(rec[1].step, 2);
    EXPECT_EQ(rec[2].step, 3);
    for (const RecordedEvent& e : rec) {
        EXPECT_EQ(e.kind, RecordedEvent::Kind::Note);
        EXPECT_EQ(e.slot, 1);
        EXPECT_EQ(e.pattern, 0);
    }
    EXPECT_EQ(rec[2].noteOrTarget, 67);
    EXPECT_FLOAT_EQ(rec[2].value, 0.5f);
    const Step& s2 = h.seq->pattern(1, 0).steps[2];
    EXPECT_TRUE(s2.on());
    ASSERT_EQ(s2.hitCount, 2);
    // Each recorded note is its own hit: played velocity, pattern gate, chance 1, nudge 0.
    EXPECT_EQ(s2.hits[0].note, 64);
    EXPECT_FLOAT_EQ(s2.hits[0].velocity, 0.7f);
    EXPECT_EQ(s2.hits[1].note, 65);
    EXPECT_FLOAT_EQ(s2.hits[1].velocity, 0.6f);
    EXPECT_FLOAT_EQ(s2.hits[1].length, Hit::kPatternGate);
    EXPECT_FLOAT_EQ(s2.hits[1].probability, 1.0f);
    EXPECT_FLOAT_EQ(s2.hits[1].nudge, 0.0f);
    EXPECT_TRUE(h.seq->pattern(1, 0).steps[3].hasNote(67));

    // Step 3 fires next without the note (it already sounds from the key), and plays it
    // on the next pass.
    h.slot(1).clear();
    h.run(6000 * 17);
    const auto ons = h.slot(1).noteOns();
    ASSERT_EQ(ons.size(), 3u);
    EXPECT_EQ(ons[0].frame, 6000 * 18);  // step 2 next time round, both notes
    EXPECT_EQ(ons[1].frame, 6000 * 18);
    EXPECT_EQ(ons[2].note, 67);
    EXPECT_EQ(ons[2].frame, 6000 * 19);
}

TEST(SequencerRecord, ShuffledStepsAreNearestByTheirShuffledTime) {
    SeqHarness h;
    h.seq->setPatternSettings(1, 0, 16, 1, 1.0f, 0.5f);  // step 1 at 6000 * 5/3 = 10000
    h.seq->setRecordArmed(true);
    h.seq->play();
    h.runTo(4801);  // 4801 from step 0, 5199 from step 1
    h.seq->recordLiveNote(1, 60, 1.0f, h.frame);
    h.runTo(5001);  // 5001 vs 4999: step 1
    h.seq->recordLiveNote(1, 61, 1.0f, h.frame);
    const auto rec = h.recorded();
    ASSERT_EQ(rec.size(), 2u);
    EXPECT_EQ(rec[0].step, 0);
    EXPECT_EQ(rec[1].step, 1);
}

TEST(SequencerRecord, OnlyWhileArmedAndPlayingAndWithTheMirrorsRules) {
    SeqHarness h;
    h.seq->recordLiveNote(1, 60, 1.0f, 0);  // stopped, not armed
    h.seq->play();
    h.run(100);
    h.seq->recordLiveNote(1, 60, 1.0f, h.frame);  // not armed
    EXPECT_TRUE(h.recorded().empty());

    h.seq->setRecordArmed(true);
    h.apply();
    EXPECT_TRUE(h.seq->transport().recordArmed);
    for (int n : {60, 61, 62, 63, 64, 65, 66, 67, 68}) h.seq->recordLiveNote(1, n, 0.9f, h.frame);
    h.seq->recordLiveNote(1, 61, 0.3f, h.frame);  // already there
    h.seq->recordLiveNote(1, 69, 0.0f, h.frame);  // replaces 68; velocity at least 0.01
    const Step& s = h.seq->pattern(1, 0).steps[0];
    ASSERT_EQ(s.hitCount, kMaxHitsPerStep);
    for (int i = 0; i < 7; ++i) EXPECT_EQ(s.hits[static_cast<std::size_t>(i)].note, 60 + i);
    EXPECT_FLOAT_EQ(s.hits[1].velocity, 0.9f) << "a note already there is left as it is";
    EXPECT_EQ(s.hits[7].note, 69);  // with 8 hits, the last one is replaced
    EXPECT_FLOAT_EQ(s.hits[7].velocity, Hit::kMinVelocity);
    const auto notes = h.recorded();
    ASSERT_EQ(notes.size(), 11u);
    EXPECT_FLOAT_EQ(notes.back().value, Hit::kMinVelocity);

    // Drum slot: only the kit's 16 keys.
    h.seq->recordLiveNote(kDrumSlot, 47, 1.0f, h.frame);
    h.seq->recordLiveNote(kDrumSlot, 64, 1.0f, h.frame);
    h.seq->recordLiveNote(kDrumSlot, 48, 1.0f, h.frame);
    const auto rec = h.recorded();
    ASSERT_EQ(rec.size(), 1u);
    EXPECT_EQ(rec[0].noteOrTarget, 48);
}

// --- F3.7 motion ---

TEST(SequencerMotion, PlaybackAppliesLaneValuesAtStepStart) {
    SeqHarness h;
    simplePattern(h);
    const int cutoff = static_cast<int>(ParamId::FltCutoff);
    h.seq->setMotionValue(1, 0, cutoff, 0, 500.0f);
    h.seq->setMotionValue(1, 0, cutoff, 1, 500.0f);  // unchanged: not sent again
    h.seq->setMotionValue(1, 0, cutoff, 8, 99999.0f);  // clamped to the range
    h.seq->play();
    h.run(6000 * 16 + 1);
    const auto motion = h.slot(1).of(SeqCall::Type::Motion);
    ASSERT_EQ(motion.size(), 3u);
    EXPECT_EQ(motion[0].frame, 0);
    EXPECT_EQ(motion[0].note, cutoff);
    EXPECT_FLOAT_EQ(motion[0].value, 500.0f);
    EXPECT_EQ(motion[1].frame, 8 * 6000);
    EXPECT_FLOAT_EQ(motion[1].value, 18000.0f);
    EXPECT_EQ(motion[2].frame, 16 * 6000);  // next loop
    EXPECT_FLOAT_EQ(motion[2].value, 500.0f);
}

TEST(SequencerMotion, OverridesClearForTargetsWithNoLane) {
    SeqHarness h;
    simplePattern(h);
    h.fillSteps(1, 1, 70);
    const int cutoff = static_cast<int>(ParamId::FltCutoff);
    const int reso = static_cast<int>(ParamId::FltReso);
    h.seq->setMotionValue(1, 0, cutoff, 0, 500.0f);
    h.seq->setMotionValue(1, 0, reso, 0, 0.5f);
    h.seq->setMotionValue(1, 1, reso, 3, 0.9f);  // pattern 1 has a reso lane only
    h.seq->play();
    h.run(100);
    h.seq->selectPattern(1, 1);
    h.run(6000 * 16);
    auto motion = h.slot(1).of(SeqCall::Type::Motion);
    // Switch at 96000: cutoff (no lane in pattern 1) is cleared; reso holds until step 3.
    bool cutoffCleared = false;
    for (const SeqCall& c : motion) {
        if (c.note == cutoff && std::isnan(c.value)) {
            cutoffCleared = true;
            EXPECT_EQ(c.frame, 16 * 6000);
        }
        if (c.note == reso && std::isnan(c.value)) ADD_FAILURE() << "reso has a lane";
    }
    EXPECT_TRUE(cutoffCleared);

    // Clearing the playing pattern's lane clears its override at once.
    h.slot(1).clear();
    h.run(6000 * 4);
    h.seq->clearMotionLane(1, 1, reso);
    h.apply();
    motion = h.slot(1).of(SeqCall::Type::Motion);
    ASSERT_FALSE(motion.empty());
    EXPECT_EQ(motion.back().note, reso);
    EXPECT_TRUE(std::isnan(motion.back().value));
    EXPECT_FALSE(h.seq->pattern(1, 1).hasLane(reso));
}

TEST(SequencerMotion, RecordingWritesTheCurrentStepAndReportsIt) {
    SeqHarness h;
    simplePattern(h);
    const int cutoff = static_cast<int>(ParamId::FltCutoff);
    h.seq->setMotionValue(1, 0, cutoff, 6, 300.0f);
    h.seq->recordMotion(1, cutoff, 1000.0f);  // not recording: ignored
    h.seq->setMotionRecording(true);
    h.seq->recordMotion(1, cutoff, 1000.0f);  // not playing: ignored
    h.apply();
    EXPECT_TRUE(h.recorded().empty());

    h.seq->play();
    h.run(6000 * 5 + 10);  // step 5 is current
    h.seq->recordMotion(1, cutoff, 1234.0f);
    h.seq->recordMotion(1, cutoff, 1500.0f);
    h.run(6000);  // step 6, which has a value: recording, so playback leaves it alone
    h.seq->recordMotion(1, cutoff, 1600.0f);
    h.apply();
    const auto rec = h.recorded();
    ASSERT_EQ(rec.size(), 3u);
    EXPECT_EQ(rec[0].kind, RecordedEvent::Kind::Motion);
    EXPECT_EQ(rec[0].step, 5);
    EXPECT_EQ(rec[1].step, 5);
    EXPECT_FLOAT_EQ(rec[1].value, 1500.0f);
    EXPECT_EQ(rec[2].step, 6);
    EXPECT_EQ(rec[0].noteOrTarget, cutoff);
    const MotionLane* lane = h.seq->pattern(1, 0).lane(cutoff);
    ASSERT_NE(lane, nullptr);
    EXPECT_FLOAT_EQ(lane->values[5], 1500.0f);
    EXPECT_FLOAT_EQ(lane->values[6], 1600.0f);
    for (const SeqCall& c : h.slot(1).of(SeqCall::Type::Motion)) {
        EXPECT_FALSE(c.frame > 6000 * 5 && !std::isnan(c.value)) << "touched lanes don't play while recording";
    }

    // Recording off: playback resumes at the next step with the recorded values.
    h.seq->setMotionRecording(false);
    h.slot(1).clear();
    h.run(6000 * 16);
    bool replayed = false;
    for (const SeqCall& c : h.slot(1).of(SeqCall::Type::Motion)) {
        if (c.frame == 6000 * (16 + 5)) {
            replayed = true;
            EXPECT_FLOAT_EQ(c.value, 1500.0f);
        }
    }
    EXPECT_TRUE(replayed);
}

TEST(SequencerMotion, ANinthLaneIsIgnoredAndNotReported) {
    SeqHarness h;
    simplePattern(h);
    for (int t = 0; t < 8; ++t) h.seq->setMotionValue(1, 0, t, 0, 0.5f);
    h.seq->setMotionValue(1, 0, 20, 0, 0.5f);
    h.seq->setMotionRecording(true);
    h.seq->play();
    h.run(10);
    h.seq->recordMotion(1, 21, 0.5f);
    h.seq->recordMotion(1, 3, 0.25f);
    h.apply();
    EXPECT_FALSE(h.seq->pattern(1, 0).hasLane(20));
    EXPECT_FALSE(h.seq->pattern(1, 0).hasLane(21));
    const auto rec = h.recorded();
    ASSERT_EQ(rec.size(), 1u);
    EXPECT_EQ(rec[0].noteOrTarget, 3);
}

TEST(SequencerMotion, DrumTargetsAreKeyTimesParamCountPlusId) {
    const int last = DrumMachine::kSoundCount * static_cast<int>(kDrumParamCount) - 1;
    EXPECT_TRUE(Sequencer::validTarget(kDrumSlot, last));
    EXPECT_FALSE(Sequencer::validTarget(kDrumSlot, last + 1));
    EXPECT_TRUE(Sequencer::validTarget(1, static_cast<int>(kParamCount) - 1));
    EXPECT_FALSE(Sequencer::validTarget(1, static_cast<int>(kParamCount)));
    EXPECT_FALSE(Sequencer::validTarget(8, 0));
    const int pitchOfKey3 = DrumMachine::motionTarget(3, DrumParamId::Pitch);
    EXPECT_FLOAT_EQ(Sequencer::sanitizeTarget(kDrumSlot, pitchOfKey3, 40.0f), 12.0f);
    EXPECT_TRUE(std::isnan(Sequencer::sanitizeTarget(kDrumSlot, pitchOfKey3, kNaN)));
}

// --- validation, transport, threading ---

TEST(SequencerCommands, InvalidIndicesAreIgnoredAndValuesClampedLikeTheMirror) {
    SeqHarness h;
    const Hit one{60};
    EXPECT_TRUE(h.seq->setStep(-1, 0, 0, &one, 1));
    EXPECT_TRUE(h.seq->setStep(8, 0, 0, &one, 1));
    EXPECT_TRUE(h.seq->setStep(1, 16, 0, &one, 1));
    EXPECT_TRUE(h.seq->setStep(1, 0, 64, &one, 1));
    std::array<Hit, 10> many{};
    for (std::size_t i = 0; i < many.size(); ++i) many[i].note = 40 + static_cast<int>(i);
    many[1] = Hit{200, kNaN, 70.0f, kNaN, kNaN};
    many[2] = Hit{-5, 3.0f, kNaN, -1.0f, 0.9f};
    many[3] = Hit{61, 0.0f, 0.01f, 0.5f, -0.9f};
    many[4] = Hit{62, -1.0f, 0.0f, 2.0f, 0.25f};
    many[5] = Hit{63, 0.5f, -0.5f, 0.5f, -0.25f};
    many[6] = Hit{64, 0.5f, std::numeric_limits<float>::infinity(), 0.5f, 0.0f};
    h.seq->setStep(1, 0, 5, many.data(), 10);  // more than 8: the rest are ignored
    h.seq->setStep(1, 0, 6, many.data(), -3);
    h.seq->setStep(1, 0, 7, nullptr, 4);
    h.seq->setStep(1, 0, 8, {Hit{60}});
    h.seq->setStep(1, 0, 8, {});  // clears it
    h.seq->selectPattern(1, 99);
    h.seq->setStepLoop(1, 3, 64);
    h.seq->setMotionValue(1, 0, 999, 0, 1.0f);
    h.apply();
    const Step& s5 = h.seq->pattern(1, 0).steps[5];
    ASSERT_EQ(s5.hitCount, kMaxHitsPerStep);
    EXPECT_EQ(s5.hits[7].note, 47);
    const Hit& a1 = s5.hits[1];
    EXPECT_EQ(a1.note, 127);
    EXPECT_FLOAT_EQ(a1.velocity, Hit::kDefaultVelocity);
    EXPECT_FLOAT_EQ(a1.length, Hit::kMaxLength);
    EXPECT_FLOAT_EQ(a1.probability, 1.0f);
    EXPECT_FLOAT_EQ(a1.nudge, 0.0f);
    const Hit& a2 = s5.hits[2];
    EXPECT_EQ(a2.note, 0);
    EXPECT_FLOAT_EQ(a2.velocity, 1.0f);
    EXPECT_FLOAT_EQ(a2.length, Hit::kPatternGate);
    EXPECT_FLOAT_EQ(a2.probability, 0.0f);
    EXPECT_FLOAT_EQ(a2.nudge, 0.5f);
    const Hit& a3 = s5.hits[3];
    EXPECT_FLOAT_EQ(a3.velocity, Hit::kMinVelocity);
    EXPECT_FLOAT_EQ(a3.length, Hit::kMinLength);
    EXPECT_FLOAT_EQ(a3.nudge, -0.5f);
    const Hit& a4 = s5.hits[4];
    EXPECT_FLOAT_EQ(a4.velocity, Hit::kMinVelocity);
    EXPECT_FLOAT_EQ(a4.length, Hit::kMinLength);  // 0 is a length, not the pattern gate
    EXPECT_FLOAT_EQ(a4.probability, 1.0f);
    EXPECT_FLOAT_EQ(a4.nudge, 0.25f);
    EXPECT_FLOAT_EQ(s5.hits[5].length, Hit::kPatternGate);  // any negative length
    EXPECT_FLOAT_EQ(s5.hits[5].nudge, -0.25f);
    EXPECT_FLOAT_EQ(s5.hits[6].length, Hit::kMaxLength);
    EXPECT_FALSE(h.seq->pattern(1, 0).steps[6].on());
    EXPECT_FALSE(h.seq->pattern(1, 0).steps[7].on());
    EXPECT_FALSE(h.seq->pattern(1, 0).steps[8].on());
    EXPECT_EQ(h.seq->transport().currentPattern[1], 0);
    EXPECT_FALSE(h.seq->pattern(1, 0).hasLane(999));
}

TEST(SequencerCommands, ClearPatternRestoresDefaults) {
    SeqHarness h;
    h.seq->setPatternSettings(1, 2, 7, 3, 0.4f, 0.9f);
    h.fillSteps(1, 2, 40);
    h.seq->setMotionValue(1, 2, 1, 1, 0.5f);
    h.seq->clearPattern(1, 2);
    h.apply();
    const Pattern& p = h.seq->pattern(1, 2);
    EXPECT_EQ(p.length, 16);
    EXPECT_EQ(p.rate, StepRate::Sixteenth);
    EXPECT_EQ(p.shuffleMilli, 0);
    EXPECT_FLOAT_EQ(p.gate, 0.5f);
    for (const Step& s : p.steps) {
        EXPECT_FALSE(s.on());
        EXPECT_EQ(s.hitCount, 0);
    }
    EXPECT_FALSE(p.hasLane(1));
}

TEST(SequencerTransport, ReadoutFollowsTheAudioThread) {
    SeqHarness h;
    TransportState t = h.seq->transport();
    EXPECT_FALSE(t.playing);
    for (int s = 0; s < kSlotCount; ++s) {
        EXPECT_EQ(t.currentStep[static_cast<std::size_t>(s)], -1);
        EXPECT_EQ(t.queuedPattern[static_cast<std::size_t>(s)], -1);
    }
    simplePattern(h);
    h.seq->setPatternSettings(3, 0, 16, 0, 0.0f, 0.5f);  // 1/8
    h.seq->play();
    h.run(6000 * 5 + 1);
    t = h.seq->transport();
    EXPECT_TRUE(t.playing);
    EXPECT_EQ(t.currentStep[1], 5);
    EXPECT_EQ(t.currentStep[3], 2);
    EXPECT_EQ(t.currentStep[0], 5);  // empty patterns still run
}

TEST(SequencerTransport, PrepareStopsButKeepsPatterns) {
    SeqHarness h;
    simplePattern(h);
    h.seq->play();
    h.run(1000);
    h.seq->prepare(44100.0f);
    EXPECT_FALSE(h.seq->transport().playing);
    EXPECT_TRUE(h.seq->pattern(1, 0).steps[3].on());
    h.frame = 0;
    h.slot(1).clear();
    h.seq->play();
    h.run(44100);
    const auto ons = h.slot(1).noteOns();
    ASSERT_GE(ons.size(), 2u);
    EXPECT_EQ(ons[1].frame, 5513);  // ceil(44100 * 0.125)
}

// The control thread queues commands and reads the transport and recorded events while the
// audio thread runs (TSan checks the hand-off).
TEST(SequencerThreading, ControlAndAudioThreadsRunConcurrently) {
    SeqHarness h;
    simplePattern(h);
    std::atomic<bool> done{false};
    std::thread audio([&] {
        while (!done.load()) h.run(96);
    });
    int recorded = 0;
    h.seq->setRecordArmed(true);
    h.seq->setMotionRecording(true);
    h.seq->play();
    for (int i = 0; i < 3000; ++i) {
        const int note = 60 + i % 12;
        const Hit hits[] = {Hit{note, 0.8f, Hit::kPatternGate, 0.5f, -0.3f}, Hit{note + 3, 0.6f, 2.5f, 1.0f, 0.4f}};
        while (!h.seq->setStep(1 + i % 7, i % 16, i % 64, hits, i % 2 == 0 ? 2 : 0)) {
            std::this_thread::yield();
        }
        h.seq->setMotionValue(1, 0, i % 8, i % 16, 0.5f);
        h.seq->recordMotion(1, 2, static_cast<float>(i % 100) / 100.0f);
        if (i % 100 == 0) h.seq->selectPattern(1, (i / 100) % 16);
        if (i % 250 == 0) h.seq->setStepLoop(2, 2, 5);
        if (i % 250 == 125) h.seq->setStepLoop(2, -1, -1);
        if (i % 500 == 0) h.seq->setTempo(60.0f + static_cast<float>(i % 180));
        (void)h.seq->transport();
        RecordedEvent e;
        while (h.seq->popRecorded(e)) ++recorded;
    }
    done.store(true);
    audio.join();
    RecordedEvent e;
    while (h.seq->popRecorded(e)) ++recorded;
    EXPECT_GT(recorded, 0);
    EXPECT_TRUE(h.seq->transport().playing);
}

TEST(SequencerLoop, StopAndPatternSwitchClearTheLoop) {
    SeqHarness h;
    simplePattern(h);
    h.fillSteps(1, 1, 80);
    h.seq->play();
    h.run(100);
    h.seq->setStepLoop(1, 2, 3);
    h.seq->selectPattern(1, 1);
    h.run(6000 * 16);  // looping 2,3,... until the virtual boundary, then pattern 1 from step 0
    h.slot(1).clear();
    h.run(6000 * 3);
    auto ons = h.slot(1).noteOns();
    ASSERT_EQ(ons.size(), 3u);
    EXPECT_EQ(ons[0].note, 81);  // 80 fired at the boundary; no loop now
    EXPECT_EQ(ons[1].note, 82);
    EXPECT_EQ(ons[2].note, 83);

    h.seq->setStepLoop(1, 5, 5);
    h.seq->stop();
    h.seq->play();
    h.slot(1).clear();
    h.run(6000 * 3 - 1);
    ons = h.slot(1).noteOns();
    ASSERT_EQ(ons.size(), 3u);
    EXPECT_EQ(ons[1].note, 81) << "Stop cleared the loop";
}

// The UI never resends its mirror: tempo and patterns live as long as the process, across
// stream restarts (prepare).
TEST(SequencerTransport, TempoAndPatternsSurviveAStreamRestart) {
    SeqHarness h;
    h.seq->setTempo(87.4f);
    simplePattern(h, 4, 3, 0.2f, 7);
    h.apply();
    EXPECT_NEAR(h.seq->transport().tempoBpm, 87.4f, 1e-4f);  // stopped: still the real tempo
    h.seq->prepare(48000.0f);
    h.seq->prepare(44100.0f);
    EXPECT_NEAR(h.seq->transport().tempoBpm, 87.4f, 1e-4f);
    EXPECT_EQ(h.seq->pattern(4, 0).length, 7);
    EXPECT_EQ(h.seq->pattern(4, 0).rate, StepRate::SixteenthTriplet);
    EXPECT_EQ(h.seq->pattern(4, 0).shuffleMilli, 200);
    EXPECT_TRUE(h.seq->pattern(4, 0).steps[6].on());
}

// The first-launch burst (and much more) fits the queue before any audio runs.
TEST(SequencerCommands, QueueHoldsAFullSessionBurstBeforeTheStreamStarts) {
    SeqHarness h;
    int pushed = 0;
    while (pushed < static_cast<int>(Sequencer::kCommandCapacity) &&
           h.seq->setStep(1 + pushed % 7, pushed % 16, pushed % 64, {Hit{pushed % 128}, Hit{1}, Hit{2}, Hit{3}, Hit{4}, Hit{5}, Hit{6}, Hit{7}})) {
        ++pushed;
    }
    EXPECT_EQ(pushed, static_cast<int>(Sequencer::kCommandCapacity));
    EXPECT_FALSE(h.seq->setTempo(100.0f)) << "full: the caller is told, nothing is silently lost";
    // Chunks drain at most kMaxCommandsPerChunk each, in order.
    h.apply();
    EXPECT_TRUE(h.seq->setTempo(100.0f));
    h.seq->applyCommands(h.frame, 1 << 30);
    EXPECT_NEAR(h.seq->transport().tempoBpm, 100.0f, 1e-4f);
}
