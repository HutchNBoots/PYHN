#include "groove/Filters.h"

#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "Analysis.h"
#include "groove/DspMath.h"

using groove::LadderFilter;
using groove::OnePoleHighPass;
using namespace groove::test;

namespace {

constexpr float kRate = 48000.0f;

std::vector<float> sine(double hz, float amplitude, int frames) {
    std::vector<float> x(static_cast<size_t>(frames));
    for (int i = 0; i < frames; ++i) {
        x[static_cast<size_t>(i)] = amplitude * static_cast<float>(std::sin(2.0 * kPiD * hz * i / kRate));
    }
    return x;
}

double ladderGainDb(float cutoff, float reso, double hz, float amplitude = 0.05f) {
    LadderFilter f;
    f.setSampleRate(kRate);
    f.setCutoff(cutoff);
    f.setResonance(reso);
    auto x = sine(hz, amplitude, 48000);
    for (float& s : x) s = f.process(s);
    return toDb(goertzelAmplitude(x, kRate, hz, 24000, 24000) / amplitude);
}

}  // namespace

TEST(OnePoleHighPass, PassesHighsAndBlocksDc) {
    OnePoleHighPass hp;
    hp.setCutoff(100.0f, kRate);
    std::vector<float> dc(48000, 1.0f);
    for (float& s : dc) s = hp.process(s);
    EXPECT_LT(std::fabs(dc.back()), 1e-4f);

    hp.reset();
    auto x = sine(5000.0, 0.5f, 48000);
    for (float& s : x) s = hp.process(s);
    EXPECT_NEAR(goertzelAmplitude(x, kRate, 5000.0, 24000, 24000), 0.5, 0.01);
}

TEST(OnePoleHighPass, CutoffIsMinus3dB) {
    for (float fc : {20.0f, 200.0f, 2000.0f}) {
        OnePoleHighPass hp;
        hp.setCutoff(fc, kRate);
        auto x = sine(fc, 0.5f, 96000);
        for (float& s : x) s = hp.process(s);
        EXPECT_NEAR(toDb(goertzelAmplitude(x, kRate, fc, 48000, 48000) / 0.5), -3.01, 0.1) << fc;
    }
}

TEST(LadderFilter, UnityPassbandAtZeroResonance) {
    EXPECT_NEAR(ladderGainDb(2000.0f, 0.0f, 100.0), 0.0, 0.2);
}

TEST(LadderFilter, CutoffIsMinus12dBPoint) {
    // Four identical poles: -3 dB each at the cutoff.
    for (float fc : {200.0f, 1000.0f, 5000.0f}) EXPECT_NEAR(ladderGainDb(fc, 0.0f, fc), -12.04, 0.3) << fc;
}

// F1.5: cutoff 1 kHz, no resonance: a 4 kHz sine loses at least 40 dB.
TEST(LadderFilter, Attenuates4kHzBy40dBAt1kHzCutoff) {
    const double db = ladderGainDb(1000.0f, 0.0f, 4000.0, 0.5f);
    std::printf("4 kHz through a 1 kHz ladder: %.1f dB\n", db);
    EXPECT_LT(db, -40.0);
}

TEST(LadderFilter, RollsOff24dBPerOctave) {
    // Well below Nyquist, where the bilinear transform's extra roll-off is negligible.
    const double a = ladderGainDb(200.0f, 0.0f, 1600.0);
    const double b = ladderGainDb(200.0f, 0.0f, 3200.0);
    EXPECT_NEAR(a - b, 24.0, 2.0);
}

TEST(LadderFilter, ResonancePeaksNearCutoff) {
    EXPECT_GT(ladderGainDb(1000.0f, 0.8f, 1000.0, 0.01f), ladderGainDb(1000.0f, 0.0f, 1000.0, 0.01f) + 10.0);
}

// F1.5: at maximum resonance the filter self-oscillates within +-3% of the cutoff.
TEST(LadderFilter, SelfOscillatesAtCutoffAtMaxResonance) {
    for (float fc : {110.0f, 440.0f, 1000.0f, 3000.0f, 8000.0f}) {
        LadderFilter f;
        f.setSampleRate(kRate);
        f.setCutoff(fc);
        f.setResonance(1.0f);
        std::vector<float> y(96000);
        for (size_t i = 0; i < y.size(); ++i) y[i] = f.process(i == 0 ? 0.5f : 0.0f);  // one kick, then silence
        const double hz = zeroCrossingHz(y, kRate, 48000);
        std::printf("self-oscillation at cutoff %.0f Hz: %.1f Hz (%+.2f%%), level %.3f\n", fc, hz,
                    100.0 * (hz / fc - 1.0), peakAbs(y, 48000));
        EXPECT_NEAR(hz / fc, 1.0, 0.03) << fc;
        EXPECT_GT(peakAbs(y, 90000), 0.05f) << fc;  // sustained, not decaying
    }
}

TEST(LadderFilter, StableForHugeInputAtAnySetting) {
    for (float reso : {0.0f, 0.5f, 1.0f}) {
        for (float drive : {1.0f, 16.0f}) {
            LadderFilter f;
            f.setSampleRate(kRate);
            f.configure(groove::onePoleCoefficient(20000.0f, kRate), reso, drive);
            groove::dsp::XorShift32 rng(7);
            for (int i = 0; i < 48000; ++i) {
                const float y = f.process(100.0f * rng.nextBipolar());
                ASSERT_TRUE(std::isfinite(y));
                ASSERT_LT(std::fabs(y), 4.0f);
            }
        }
    }
}

TEST(LadderFilter, DriveSaturates) {
    LadderFilter clean, driven;
    for (LadderFilter* f : {&clean, &driven}) {
        f->setSampleRate(kRate);
        f->setCutoff(18000.0f);
    }
    driven.setDrive(16.0f);
    auto x = sine(200.0, 0.5f, 48000);
    std::vector<float> a(x.size()), b(x.size());
    for (size_t i = 0; i < x.size(); ++i) {
        a[i] = clean.process(x[i]);
        b[i] = driven.process(x[i]);
    }
    // Driven: strong 3rd harmonic.
    const double h3Clean = goertzelAmplitude(a, kRate, 600.0, 24000, 24000) / goertzelAmplitude(a, kRate, 200.0, 24000, 24000);
    const double h3Driven = goertzelAmplitude(b, kRate, 600.0, 24000, 24000) / goertzelAmplitude(b, kRate, 200.0, 24000, 24000);
    EXPECT_GT(toDb(h3Driven), toDb(h3Clean) + 15.0);
}
