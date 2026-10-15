#include "groove/MasterBus.h"

#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "Analysis.h"
#include "groove/DspMath.h"

using groove::MasterBus;
using namespace groove::test;

namespace {
const float kCeiling = std::pow(10.0f, -0.3f / 20.0f);
}

TEST(MasterBus, QuietSignalsPassUntouched) {
    MasterBus bus;
    std::vector<float> l(4800), r(4800);
    for (size_t i = 0; i < l.size(); ++i) {
        l[i] = 0.5f * std::sin(0.05f * static_cast<float>(i));
        r[i] = -l[i];
    }
    auto l0 = l, r0 = r;
    bus.process(l.data(), r.data(), static_cast<int32_t>(l.size()));
    EXPECT_EQ(l, l0);
    EXPECT_EQ(r, r0);
}

// F0.6: peaks never exceed -0.3 dBFS, for any input.
TEST(MasterBus, PeaksNeverExceedCeilingForAnyInput) {
    MasterBus bus;
    groove::dsp::XorShift32 rng(5);
    float worst = 0.0f;
    for (int block = 0; block < 2000; ++block) {
        std::vector<float> l(192), r(192);
        const float scale = std::pow(10.0f, static_cast<float>(block % 13) - 4.0f);  // 1e-4 .. 1e8
        for (size_t i = 0; i < l.size(); ++i) {
            l[i] = scale * rng.nextBipolar();
            r[i] = (block % 3 == 0) ? -l[i] : scale * rng.nextBipolar();
        }
        if (block == 77) l[5] = INFINITY;
        if (block == 78) r[9] = -INFINITY;
        if (block == 79) l[0] = std::nanf("");
        bus.process(l.data(), r.data(), 192);
        for (size_t i = 0; i < l.size(); ++i) {
            ASSERT_TRUE(std::isfinite(l[i]) && std::isfinite(r[i]));
            worst = std::max({worst, std::fabs(l[i]), std::fabs(r[i])});
        }
    }
    EXPECT_LE(worst, kCeiling);
    std::printf("worst output peak %.4f dBFS\n", 20.0 * std::log10(worst));
}

TEST(MasterBus, LoudSineIsLimitedSmoothly) {
    MasterBus bus;
    std::vector<float> l(48000), r(48000);
    for (size_t i = 0; i < l.size(); ++i) l[i] = r[i] = 4.0f * std::sin(2.0f * 3.14159265f * 220.0f * static_cast<float>(i) / 48000.0f);
    bus.process(l.data(), r.data(), 48000);
    EXPECT_LE(peakAbs(l), kCeiling);
    EXPECT_GT(peakAbs(l, 24000), 0.9f * kCeiling);
    // Still a sine-like tone, not a square: fundamental dominates, harmonics modest.
    const double h1 = goertzelAmplitude(l, 48000.0, 220.0, 24000, 24000);
    const double h3 = goertzelAmplitude(l, 48000.0, 660.0, 24000, 24000);
    EXPECT_LT(toDb(h3 / h1), -20.0);
}

TEST(MasterBus, RecoversAfterAPeak) {
    MasterBus bus;
    std::vector<float> l(48000, 0.0f), r(48000, 0.0f);
    l[0] = 10.0f;
    for (size_t i = 24000; i < l.size(); ++i) l[i] = 0.5f;
    bus.process(l.data(), r.data(), 48000);
    EXPECT_NEAR(l.back(), 0.5f, 1e-4f);
    EXPECT_FLOAT_EQ(bus.gain(), 1.0f);
}
