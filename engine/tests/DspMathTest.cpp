#include "groove/DspMath.h"

#include <gtest/gtest.h>

#include <cmath>

namespace dsp = groove::dsp;

TEST(DspMath, SinCycleMatchesLibm) {
    for (int i = 0; i < 100000; ++i) {
        const float t = static_cast<float>(i) / 100000.0f;
        EXPECT_NEAR(dsp::sinCycle(t), std::sin(2.0 * 3.14159265358979 * t), 2e-6) << t;
    }
}

TEST(DspMath, SoftClipIsBoundedMonotonicAndSmooth) {
    float prev = -1.0f;
    for (float x = -10.0f; x <= 10.0f; x += 0.001f) {
        const float y = dsp::softClip(x);
        ASSERT_GE(y, prev - 1e-6f);
        ASSERT_LE(std::fabs(y), 1.0f + 1e-6f);
        ASSERT_LT(y - prev, 0.0011f);  // slope never above 1
        prev = y;
    }
    EXPECT_NEAR(dsp::softClip(0.1f), std::tanh(0.1f), 1e-4f);
}

TEST(DspMath, BlepResidualsAreContinuousAndComplete) {
    // Residual of a unit step: -1/2 at the edge, zero at +-3 samples, odd.
    EXPECT_FLOAT_EQ(dsp::blepResidual(0.0f), -0.5f);
    EXPECT_NEAR(dsp::blepResidual(-1e-6f), 0.5f, 1e-5f);
    for (float x : {1.0f, 2.0f}) {
        EXPECT_NEAR(dsp::blepResidual(x - 1e-5f), dsp::blepResidual(x + 1e-5f), 1e-4f) << x;
        EXPECT_NEAR(dsp::blampResidual(x - 1e-5f), dsp::blampResidual(x + 1e-5f), 1e-4f) << x;
    }
    EXPECT_NEAR(dsp::blepResidual(2.99999f), 0.0f, 1e-6f);
    EXPECT_NEAR(dsp::blampResidual(2.99999f), 0.0f, 1e-6f);
    // The BLAMP is the integral of the BLEP: its slope matches.
    for (float x = -2.9f; x < 2.9f; x += 0.1f) {
        if (std::fabs(x) < 0.01f) continue;
        const float h = 1e-3f;
        const float slope = (dsp::blampResidual(x + h) - dsp::blampResidual(x - h)) / (2 * h);
        EXPECT_NEAR(slope, dsp::blepResidual(x), 2e-3f) << x;
    }
}

TEST(DspMath, XorShiftIsSeededUniformAndNeverStuck) {
    dsp::XorShift32 a(42), b(42), z(0);
    double sum = 0.0, sumSq = 0.0;
    for (int i = 0; i < 100000; ++i) {
        const float v = a.nextBipolar();
        ASSERT_EQ(v, b.nextBipolar());
        ASSERT_GE(v, -1.0f);
        ASSERT_LE(v, 1.0f);
        sum += v;
        sumSq += v * v;
        ASSERT_NE(z.nextU32(), 0u);  // seed 0 is remapped
    }
    EXPECT_NEAR(sum / 100000.0, 0.0, 0.01);
    EXPECT_NEAR(sumSq / 100000.0, 1.0 / 3.0, 0.01);  // uniform variance
}
