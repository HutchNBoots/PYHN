#include "groove/Adsr.h"

#include <gtest/gtest.h>

#include <cmath>
#include <vector>

using groove::Adsr;
using groove::AdsrShape;

namespace {

constexpr float kRate = 48000.0f;

// Samples until the envelope reaches `stage` (or gives up).
long samplesUntil(Adsr& env, const AdsrShape& shape, Adsr::Stage stage, long limit) {
    for (long n = 1; n <= limit; ++n) {
        env.process(shape);
        if (env.stage() == stage) return n;
    }
    return -1;
}

}  // namespace

TEST(Adsr, IdleIsSilent) {
    Adsr env;
    const auto shape = AdsrShape::compute(0.01f, 0.1f, 0.5f, 0.1f, kRate);
    for (int i = 0; i < 100; ++i) EXPECT_EQ(env.process(shape), 0.0f);
    EXPECT_TRUE(env.isIdle());
    env.gateOff();
    EXPECT_TRUE(env.isIdle());
}

// F1.6: every segment takes its set time, from 1 ms to 10 s.
TEST(Adsr, SegmentTimesMatchFrom1msTo10s) {
    for (float t : {0.001f, 0.01f, 0.1f, 1.0f, 10.0f}) {
        const auto shape = AdsrShape::compute(t, t, 0.5f, t, kRate);
        const long expected = std::lround(t * kRate);
        const long tolerance = std::max(2L, expected / 100);  // 1%

        Adsr env;
        env.gateOn();
        EXPECT_NEAR(samplesUntil(env, shape, Adsr::Stage::Decay, 2 * expected + 10), expected, tolerance) << "attack " << t;
        EXPECT_FLOAT_EQ(env.level(), 1.0f);
        EXPECT_NEAR(samplesUntil(env, shape, Adsr::Stage::Sustain, 2 * expected + 10), expected, tolerance) << "decay " << t;
        EXPECT_FLOAT_EQ(env.level(), 0.5f);

        // Release from full level takes the release time.
        const auto full = AdsrShape::compute(t, t, 1.0f, t, kRate);
        Adsr env2;
        env2.gateOn();
        samplesUntil(env2, full, Adsr::Stage::Sustain, 2 * expected + 10);
        env2.gateOff();
        EXPECT_NEAR(samplesUntil(env2, full, Adsr::Stage::Idle, 2 * expected + 10), expected, tolerance) << "release " << t;
        EXPECT_EQ(env2.level(), 0.0f);
    }
}

TEST(Adsr, ShortestTimeIsOneMillisecond) {
    const auto shape = AdsrShape::compute(0.0f, 0.0f, 0.5f, 0.0f, kRate);
    Adsr env;
    env.gateOn();
    EXPECT_NEAR(samplesUntil(env, shape, Adsr::Stage::Decay, 1000), 48, 2);
}

TEST(Adsr, CurvesAreExponential) {
    const auto shape = AdsrShape::compute(0.1f, 0.1f, 0.0f, 0.1f, kRate);
    Adsr env;
    env.gateOn();
    // Attack is convex (fast start): above the straight line halfway through.
    for (int i = 0; i < 2400; ++i) env.process(shape);
    EXPECT_GT(env.level(), 0.6f);
    // Decay to 0: about -30 dB halfway, far below a straight line's 0.5.
    samplesUntil(env, shape, Adsr::Stage::Decay, 10000);
    for (int i = 0; i < 2400; ++i) env.process(shape);
    EXPECT_LT(env.level(), 0.05f);
    EXPECT_GT(env.level(), 0.01f);
}

TEST(Adsr, SegmentsAreMonotonic) {
    const auto shape = AdsrShape::compute(0.01f, 0.05f, 0.3f, 0.05f, kRate);
    Adsr env;
    env.gateOn();
    float prev = 0.0f;
    while (env.stage() == Adsr::Stage::Attack) {
        const float v = env.process(shape);
        ASSERT_GE(v, prev);
        prev = v;
    }
    while (env.stage() == Adsr::Stage::Decay) {
        const float v = env.process(shape);
        ASSERT_LE(v, prev);
        prev = v;
    }
    env.gateOff();
    while (!env.isIdle()) {
        const float v = env.process(shape);
        ASSERT_LE(v, prev);
        prev = v;
    }
}

// F1.6: a note-on at any stage starts from the current level, with no jump.
TEST(Adsr, RetriggerAtAnyStageStartsFromCurrentLevel) {
    const auto shape = AdsrShape::compute(0.005f, 0.05f, 0.4f, 0.2f, kRate);
    // Max per-sample step of an attack from zero: the steepest part of the curve.
    Adsr probe;
    probe.gateOn();
    float maxAttackStep = 0.0f, prev = 0.0f;
    while (probe.stage() == Adsr::Stage::Attack) {
        const float v = probe.process(shape);
        maxAttackStep = std::max(maxAttackStep, v - prev);
        prev = v;
    }

    for (int stopAt : {100, 240, 1000, 3000, 10000}) {         // attack, decay, sustain
        for (int releaseFor : {0, 50, 2000, 8000}) {            // ...and into the release
            Adsr env;
            env.gateOn();
            for (int i = 0; i < stopAt; ++i) env.process(shape);
            if (releaseFor > 0) {
                env.gateOff();
                for (int i = 0; i < releaseFor; ++i) env.process(shape);
            }
            const float before = env.level();
            env.gateOn();
            const float after = env.process(shape);
            EXPECT_LE(std::fabs(after - before), maxAttackStep + 1e-6f) << stopAt << " " << releaseFor;
            if (before < 1.0f) EXPECT_GE(after, before) << stopAt << " " << releaseFor;
        }
    }
}

TEST(Adsr, SustainChangeGlides) {
    auto shape = AdsrShape::compute(0.001f, 0.001f, 1.0f, 0.1f, kRate);
    Adsr env;
    env.gateOn();
    for (int i = 0; i < 1000; ++i) env.process(shape);
    ASSERT_EQ(env.stage(), Adsr::Stage::Sustain);
    shape = AdsrShape::compute(0.001f, 0.001f, 0.0f, 0.1f, kRate);
    float prev = env.level(), maxStep = 0.0f;
    for (int i = 0; i < 960; ++i) {
        const float v = env.process(shape);
        maxStep = std::max(maxStep, std::fabs(v - prev));
        prev = v;
    }
    EXPECT_LT(maxStep, 0.02f);   // no jump
    EXPECT_LT(env.level(), 0.01f);  // but gets there within 20 ms
}

TEST(Adsr, ControlRateEnvelopeKeepsTimes) {
    const float controlRate = kRate / 16.0f;
    const auto shape = AdsrShape::compute(0.5f, 0.5f, 0.5f, 0.5f, controlRate);
    Adsr env;
    env.gateOn();
    EXPECT_NEAR(samplesUntil(env, shape, Adsr::Stage::Decay, 10000), 1500, 15);
}
