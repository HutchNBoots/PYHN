#include "groove/Oscillator.h"

#include <gtest/gtest.h>

#include <cmath>
#include <vector>

using groove::Oscillator;
using groove::Waveform;

namespace {

constexpr float kRate = 48000.0f;

std::vector<float> renderSeconds(Waveform w, float hz, float seconds) {
    Oscillator osc;
    osc.setSampleRate(kRate);
    osc.setWaveform(w);
    osc.setFrequency(hz);
    std::vector<float> out(static_cast<size_t>(kRate * seconds));
    for (float& s : out) s = osc.process();
    return out;
}

int risingZeroCrossings(const std::vector<float>& x) {
    int n = 0;
    for (size_t i = 1; i < x.size(); ++i) {
        if (x[i - 1] < 0.0f && x[i] >= 0.0f) ++n;
    }
    return n;
}

int fallingCrossings(const std::vector<float>& x) {
    int n = 0;
    for (size_t i = 1; i < x.size(); ++i) {
        if (x[i - 1] > 0.0f && x[i] <= 0.0f) ++n;
    }
    return n;
}

float peak(const std::vector<float>& x) {
    float p = 0.0f;
    for (float s : x) p = std::max(p, std::fabs(s));
    return p;
}

double mean(const std::vector<float>& x) {
    double sum = 0.0;
    for (float s : x) sum += s;
    return sum / static_cast<double>(x.size());
}

}  // namespace

TEST(Oscillator, SineHasRequestedFrequency) {
    auto x = renderSeconds(Waveform::Sine, 1000.0f, 1.0f);
    EXPECT_NEAR(risingZeroCrossings(x), 1000, 1);
}

TEST(Oscillator, SineStartsAtZeroAndPeaksAtOne) {
    auto x = renderSeconds(Waveform::Sine, 440.0f, 0.1f);
    EXPECT_NEAR(x[0], 0.0f, 1e-6f);
    EXPECT_NEAR(peak(x), 1.0f, 0.01f);
}

TEST(Oscillator, SawHasRequestedFrequencyAndIsBounded) {
    auto x = renderSeconds(Waveform::Saw, 220.0f, 1.0f);
    // One reset (falling edge) per cycle.
    EXPECT_NEAR(fallingCrossings(x), 220, 1);
    EXPECT_LT(peak(x), 1.15f);
    EXPECT_NEAR(mean(x), 0.0, 0.01);
}

TEST(Oscillator, SquareHasRequestedFrequencyAndIsBounded) {
    auto x = renderSeconds(Waveform::Square, 330.0f, 1.0f);
    EXPECT_NEAR(risingZeroCrossings(x), 330, 1);
    EXPECT_LT(peak(x), 1.15f);
    EXPECT_NEAR(mean(x), 0.0, 0.01);
}

TEST(Oscillator, PolyBlepSoftensSawDiscontinuity) {
    // A naive saw jumps by 2.0 at the reset; PolyBLEP spreads it over two samples.
    auto x = renderSeconds(Waveform::Saw, 1000.0f, 0.05f);
    float maxJump = 0.0f;
    for (size_t i = 1; i < x.size(); ++i) maxJump = std::max(maxJump, std::fabs(x[i] - x[i - 1]));
    EXPECT_LT(maxJump, 1.6f);
}

TEST(Oscillator, HighNotesStayFinite) {
    auto x = renderSeconds(Waveform::Saw, 20000.0f, 0.1f);
    for (float s : x) ASSERT_TRUE(std::isfinite(s));
    EXPECT_LT(peak(x), 1.5f);
}
