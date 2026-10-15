#include "groove/Oscillator.h"

#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "Analysis.h"

using groove::Oscillator;
using namespace groove::test;

namespace {

constexpr float kRate = 48000.0f;

std::vector<float> render(float wave, float hz, float seconds, float pw = 0.5f, bool sub = false) {
    Oscillator osc;
    osc.setSampleRate(kRate);
    osc.setWave(wave);
    osc.setPulseWidth(pw);
    osc.setFrequency(hz);
    std::vector<float> out(static_cast<size_t>(kRate * seconds));
    for (float& s : out) {
        s = osc.process();
        if (sub) s = osc.sub();
    }
    return out;
}

double mean(const std::vector<float>& x) {
    double sum = 0.0;
    for (float s : x) sum += s;
    return sum / static_cast<double>(x.size());
}

// Fundamentals from 100 Hz to 4 kHz (F1.4). Aliases are placed between harmonics so the
// FFT can tell them apart, plus the worst cases where an alias lands just under 20 kHz.
std::vector<double> aliasTestFrequencies() {
    std::vector<double> f;
    for (int i = 0; i <= 24; ++i) {
        double hz = 100.0 * std::pow(40.0, i / 24.0);
        const double r = 48000.0 / hz;
        const double frac = r - std::floor(r);
        if (frac < 0.2 || frac > 0.8) hz = 48000.0 / (std::floor(r) + 0.5);
        f.push_back(std::min(hz, 4000.0));
    }
    for (int k = 8; k <= 40; k += 2) f.push_back(28060.0 / k);  // alias at ~19.94 kHz
    f.push_back(3999.0);
    return f;
}

void expectBandLimited(float wave, float pw, bool sub, const char* name) {
    double worst = -300.0, worstF0 = 0.0, worstHz = 0.0;
    for (double f0 : aliasTestFrequencies()) {
        const double osc = sub ? 2.0 * f0 : f0;  // the sub sounds an octave below
        auto x = render(wave, static_cast<float>(osc), 1.5f, pw, sub);
        const AliasReport r = measureAliasing(x, kRate, f0);
        if (r.worstDb > worst) {
            worst = r.worstDb;
            worstF0 = f0;
            worstHz = r.worstHz;
        }
    }
    std::printf("%-12s worst alias %.1f dB (f0 %.1f Hz, alias at %.0f Hz)\n", name, worst, worstF0, worstHz);
    EXPECT_LT(worst, -40.0) << name << ": f0 " << worstF0 << " Hz, alias at " << worstHz << " Hz";
}

}  // namespace

TEST(Oscillator, MorphWeightsCrossfadeNeighbours) {
    auto w = Oscillator::morphWeights(0.0f);
    EXPECT_FLOAT_EQ(w[0], 1.0f);
    w = Oscillator::morphWeights(1.25f);
    EXPECT_FLOAT_EQ(w[1], 0.75f);
    EXPECT_FLOAT_EQ(w[2], 0.25f);
    EXPECT_FLOAT_EQ(w[0] + w[3], 0.0f);
    w = Oscillator::morphWeights(3.0f);
    EXPECT_FLOAT_EQ(w[3], 1.0f);
    EXPECT_FLOAT_EQ(w[0] + w[1] + w[2], 0.0f);
    w = Oscillator::morphWeights(-5.0f);
    EXPECT_FLOAT_EQ(w[0], 1.0f);
}

TEST(Oscillator, EveryWaveHasRequestedPitchAndNoDc) {
    for (float wave : {0.0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f}) {
        auto x = render(wave, 220.0f, 1.0f);
        // Fundamental phase is the same for every wave, so rising zero crossings of the
        // fundamental count cycles even for the saw (its reset is a rising edge at t=0).
        EXPECT_NEAR(mean(x), 0.0, 0.01) << wave;
        EXPECT_LT(peakAbs(x), 1.2f) << wave;
        EXPECT_GT(peakAbs(x), 0.6f) << wave;
        const auto mag = magnitudeSpectrum(x, 0, 32768);
        const double binHz = kRate / 32768.0;
        // The strongest component is the fundamental.
        size_t best = 1;
        for (size_t b = 1; b < mag.size(); ++b) {
            if (mag[b] > mag[best]) best = b;
        }
        EXPECT_NEAR(static_cast<double>(best) * binHz, 220.0, 2.0) << wave;
    }
}

TEST(Oscillator, NeighbouringWavesAreInPhase) {
    // Crossfades must not cancel the fundamental: the midpoint keeps most of its level.
    for (float wave : {0.5f, 1.5f, 2.5f}) {
        auto mid = render(wave, 440.0f, 0.5f);
        auto lo = render(std::floor(wave), 440.0f, 0.5f);
        auto hi = render(std::ceil(wave), 440.0f, 0.5f);
        const double aMid = goertzelAmplitude(mid, kRate, 440.0, 0, 16384);
        const double aLo = goertzelAmplitude(lo, kRate, 440.0, 0, 16384);
        const double aHi = goertzelAmplitude(hi, kRate, 440.0, 0, 16384);
        EXPECT_NEAR(aMid, 0.5 * (aLo + aHi), 0.02) << wave;
    }
}

TEST(Oscillator, SineIsPure) {
    auto x = render(3.0f, 1000.0f, 1.5f);
    EXPECT_NEAR(x[0], 0.0f, 1e-6f);
    EXPECT_NEAR(peakAbs(x), 1.0f, 1e-3f);
    const AliasReport r = measureAliasing(x, kRate, 1000.0, 23000.0);
    EXPECT_LT(r.worstDb, -90.0);
    // Harmonic distortion of the polynomial sine.
    const auto mag = magnitudeSpectrum(x, 0, 32768);
    const double binHz = kRate / 32768.0;
    const double h1 = peakNear(mag, binHz, 1000.0);
    for (int k = 2; k <= 10; ++k) EXPECT_LT(toDb(peakNear(mag, binHz, 1000.0 * k) / h1), -100.0);
}

TEST(Oscillator, PulseWidthSetsDutyCycle) {
    for (float pw : {0.1f, 0.25f, 0.5f, 0.8f}) {
        auto x = render(1.0f, 100.0f, 1.0f, pw);
        // DC is removed, so "high" samples sit above the mean.
        size_t high = 0;
        for (float s : x) high += s > 0.0f ? 1 : 0;
        EXPECT_NEAR(static_cast<double>(high) / static_cast<double>(x.size()), pw, 0.01) << pw;
        EXPECT_NEAR(mean(x), 0.0, 0.01) << pw;
    }
}

TEST(Oscillator, SubIsAnOctaveBelowSquare) {
    auto x = render(0.0f, 440.0f, 1.0f, 0.5f, true);
    EXPECT_NEAR(zeroCrossingHz(x, kRate), 220.0, 0.01);
    EXPECT_NEAR(mean(x), 0.0, 0.01);
    EXPECT_LT(peakAbs(x), 1.2f);
}

TEST(Oscillator, PitchIsExactAcrossRange) {
    // 32-bit phase: better than 0.01 cent from C0 to the top of the keyboard.
    for (int note = 12; note <= 120; note += 12) {
        const double hz = 440.0 * std::pow(2.0, (note - 69) / 12.0);
        auto x = render(3.0f, static_cast<float>(hz), 2.0f);
        EXPECT_NEAR(cents(zeroCrossingHz(x, kRate), hz), 0.0, 0.01) << note;
    }
}

TEST(Oscillator, SawIsBandLimited) { expectBandLimited(0.0f, 0.5f, false, "saw"); }
TEST(Oscillator, SquareIsBandLimited) { expectBandLimited(1.0f, 0.5f, false, "square"); }
TEST(Oscillator, NarrowPulseIsBandLimited) { expectBandLimited(1.0f, 0.25f, false, "pulse25"); }
TEST(Oscillator, TriangleIsBandLimited) { expectBandLimited(2.0f, 0.5f, false, "triangle"); }
TEST(Oscillator, SineIsBandLimited) { expectBandLimited(3.0f, 0.5f, false, "sine"); }
TEST(Oscillator, MorphedWavesAreBandLimited) {
    expectBandLimited(0.5f, 0.5f, false, "saw/square");
    expectBandLimited(1.5f, 0.5f, false, "square/tri");
    expectBandLimited(2.5f, 0.5f, false, "tri/sine");
}
TEST(Oscillator, SubIsBandLimited) { expectBandLimited(0.0f, 0.5f, true, "sub"); }

TEST(Oscillator, HighNotesStayFiniteAndBounded) {
    for (float wave : {0.0f, 1.0f, 2.0f, 3.0f}) {
        for (float hz : {12000.0f, 20000.0f, 30000.0f}) {
            auto x = render(wave, hz, 0.1f, 0.05f);
            for (float s : x) ASSERT_TRUE(std::isfinite(s));
            EXPECT_LT(peakAbs(x), 3.0f) << wave << " " << hz;
        }
    }
}
