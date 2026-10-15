#include "groove/Envelope.h"

#include <gtest/gtest.h>

#include <cmath>

using groove::Envelope;

namespace {

Envelope makeEnvelope() {
    Envelope env;
    env.setSampleRate(48000.0f);
    env.setAttackMs(1.0f);    // 48 samples
    env.setReleaseMs(10.0f);  // 480 samples
    return env;
}

}  // namespace

TEST(Envelope, IdleOutputsSilence) {
    Envelope env = makeEnvelope();
    for (int i = 0; i < 100; ++i) EXPECT_EQ(env.process(), 0.0f);
    EXPECT_TRUE(env.isIdle());
}

TEST(Envelope, AttackRisesMonotonicallyToFullWithinAttackTime) {
    Envelope env = makeEnvelope();
    env.gateOn();
    float prev = 0.0f;
    for (int i = 0; i < 48; ++i) {
        float v = env.process();
        EXPECT_GE(v, prev);
        EXPECT_GT(v, 0.0f);
        prev = v;
    }
    EXPECT_FLOAT_EQ(prev, 1.0f);
    EXPECT_FLOAT_EQ(env.process(), 1.0f);  // sustain
}

TEST(Envelope, ReleaseFallsToSilenceAndGoesIdle) {
    Envelope env = makeEnvelope();
    env.gateOn();
    for (int i = 0; i < 100; ++i) env.process();
    env.gateOff();
    float prev = 1.0f;
    for (int i = 0; i < 480; ++i) {
        float v = env.process();
        EXPECT_LE(v, prev);
        prev = v;
    }
    EXPECT_EQ(prev, 0.0f);
    EXPECT_TRUE(env.isIdle());
}

TEST(Envelope, RetriggerDuringReleaseDoesNotJump) {
    Envelope env = makeEnvelope();
    env.gateOn();
    for (int i = 0; i < 100; ++i) env.process();
    env.gateOff();
    for (int i = 0; i < 200; ++i) env.process();
    float before = env.level();
    ASSERT_GT(before, 0.0f);
    env.gateOn();
    float after = env.process();
    // One attack step at most; never resets to zero.
    EXPECT_GT(after, before);
    EXPECT_LT(after - before, 1.0f / 48.0f + 1e-6f);
}

TEST(Envelope, GateOffWhileIdleStaysIdle) {
    Envelope env = makeEnvelope();
    env.gateOff();
    EXPECT_TRUE(env.isIdle());
    EXPECT_EQ(env.process(), 0.0f);
}
