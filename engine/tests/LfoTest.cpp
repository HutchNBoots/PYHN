#include "groove/Lfo.h"

#include <gtest/gtest.h>

#include <cmath>
#include <set>
#include <vector>

#include "Analysis.h"

using groove::Lfo;
using Shape = groove::Lfo::Shape;

namespace {

constexpr float kControlRate = 3000.0f;

std::vector<float> run(Shape shape, float hz, int steps, uint32_t seed = 1) {
    Lfo lfo;
    lfo.setUpdateRate(kControlRate);
    lfo.setFrequency(hz);
    lfo.setShape(shape);
    lfo.seed(seed);
    lfo.reset();
    std::vector<float> out(static_cast<size_t>(steps));
    for (float& v : out) v = lfo.process();
    return out;
}

}  // namespace

TEST(Lfo, ShapesAreBipolarAndHaveTheRate) {
    for (Shape s : {Shape::Sine, Shape::Triangle, Shape::Square}) {
        auto x = run(s, 5.0f, 30000);  // 10 s
        float lo = 1.0f, hi = -1.0f;
        for (float v : x) {
            lo = std::min(lo, v);
            hi = std::max(hi, v);
        }
        EXPECT_NEAR(lo, -1.0f, 0.01f);
        EXPECT_NEAR(hi, 1.0f, 0.01f);
        // Count upward zero crossings: 5 per second (the square crosses at its edges).
        int up = 0;
        for (size_t i = 1; i < x.size(); ++i) up += (x[i - 1] < 0.0f && x[i] >= 0.0f) ? 1 : 0;
        EXPECT_NEAR(up, 50, 1) << static_cast<int>(s);
    }
}

TEST(Lfo, ResetRestartsTheCycle) {
    Lfo lfo;
    lfo.setUpdateRate(kControlRate);
    lfo.setFrequency(3.0f);
    for (int i = 0; i < 777; ++i) lfo.process();
    lfo.reset();
    EXPECT_NEAR(lfo.process(), 0.0f, 1e-6f);  // sine starts at zero
}

TEST(Lfo, SampleAndHoldStepsOncePerCycleAndIsSeeded) {
    auto x = run(Shape::SampleHold, 10.0f, 3000);
    std::set<float> distinct(x.begin(), x.end());
    EXPECT_NEAR(static_cast<double>(distinct.size()), 10.0, 1.0);
    for (float v : x) {
        EXPECT_GE(v, -1.0f);
        EXPECT_LE(v, 1.0f);
    }
    EXPECT_EQ(x, run(Shape::SampleHold, 10.0f, 3000));  // repeatable
    EXPECT_NE(x, run(Shape::SampleHold, 10.0f, 3000, 99));
}

TEST(Lfo, TempoDivisionsAt120Bpm) {
    EXPECT_FLOAT_EQ(Lfo::divisionHz(Lfo::nearestDivision(2.0f, 120.0f), 120.0f), 2.0f);   // 1/4
    EXPECT_STREQ(Lfo::kDivisionNames[Lfo::nearestDivision(2.0f, 120.0f)], "1/4");
    EXPECT_STREQ(Lfo::kDivisionNames[Lfo::nearestDivision(8.0f, 120.0f)], "1/16");
    EXPECT_STREQ(Lfo::kDivisionNames[Lfo::nearestDivision(0.05f, 120.0f)], "4 bars");
    EXPECT_STREQ(Lfo::kDivisionNames[Lfo::nearestDivision(30.0f, 120.0f)], "1/32T");
    // Monotonic: a faster rate never selects a slower division.
    float prevHz = 0.0f;
    for (float r = 0.05f; r <= 30.0f; r *= 1.05f) {
        const float hz = Lfo::divisionHz(Lfo::nearestDivision(r, 120.0f), 120.0f);
        EXPECT_GE(hz, prevHz);
        prevHz = hz;
    }
}
