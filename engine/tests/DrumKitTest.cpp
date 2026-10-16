// F2.1 / F2.4: the built-in kit. 16 original synthesised sounds, rendered into memory at
// the stream rate: non-silent, distinct, peak-normalised, clean at both ends, deterministic
// and quick to build.

#include <gtest/gtest.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <set>
#include <string>
#include <vector>

#include "Analysis.h"
#include "groove/DrumKit.h"
#include "groove/DrumMachine.h"

using namespace groove;
using groove::test::magnitudeSpectrum;

namespace {

constexpr float kFs = 48000.0f;

std::vector<float> sound(const DrumKit& kit, int key) {
    return std::vector<float>(kit.data(key), kit.data(key) + kit.length(key));
}

double spectralCentroid(const std::vector<float>& x, double fs) {
    std::vector<float> padded(8192, 0.0f);
    std::copy(x.begin(), x.begin() + static_cast<std::ptrdiff_t>(std::min<std::size_t>(x.size(), padded.size())),
              padded.begin());
    const auto mag = magnitudeSpectrum(padded, 0, padded.size());
    const double binHz = fs / static_cast<double>(padded.size());
    double num = 0.0, den = 0.0;
    for (std::size_t b = 0; b < mag.size(); ++b) {
        num += mag[b] * static_cast<double>(b) * binHz;
        den += mag[b];
    }
    return num / den;
}

double strongestHz(const std::vector<float>& x, double fs) {
    std::vector<float> padded(16384, 0.0f);
    std::copy(x.begin(), x.begin() + static_cast<std::ptrdiff_t>(std::min<std::size_t>(x.size(), padded.size())),
              padded.begin());
    const auto mag = magnitudeSpectrum(padded, 0, padded.size());
    std::size_t best = 1;
    for (std::size_t b = 1; b < mag.size(); ++b) {
        if (mag[b] > mag[best]) best = b;
    }
    return static_cast<double>(best) * fs / static_cast<double>(padded.size());
}

const DrumKit& kit48() {
    static const DrumKit kit = [] {
        DrumKit k;
        k.render(kFs);
        return k;
    }();
    return kit;
}

}  // namespace

TEST(DrumKit, HasSixteenNamedSoundsInSpecOrder) {
    std::set<std::string> names;
    for (int k = 0; k < DrumKit::kSoundCount; ++k) {
        const std::string n = DrumMachine::soundName(k);
        EXPECT_FALSE(n.empty()) << k;
        names.insert(n);
    }
    EXPECT_EQ(names.size(), 16u) << "names must be unique";
    EXPECT_STREQ(DrumMachine::soundName(-1), "");
    EXPECT_STREQ(DrumMachine::soundName(16), "");
    // F2.1 order: kick x2, snare x2, clap, rim, closed hat x2, open hat, crash, ride,
    // toms x3, perc x2.
    EXPECT_NE(std::string(DrumMachine::soundName(0)).find("Kick"), std::string::npos);
    EXPECT_NE(std::string(DrumMachine::soundName(1)).find("Kick"), std::string::npos);
    EXPECT_NE(std::string(DrumMachine::soundName(2)).find("Snare"), std::string::npos);
    EXPECT_NE(std::string(DrumMachine::soundName(3)).find("Snare"), std::string::npos);
    EXPECT_STREQ(DrumMachine::soundName(4), "Clap");
    EXPECT_STREQ(DrumMachine::soundName(5), "Rim");
    EXPECT_NE(std::string(DrumMachine::soundName(6)).find("Hat"), std::string::npos);
    EXPECT_NE(std::string(DrumMachine::soundName(7)).find("Hat"), std::string::npos);
    EXPECT_STREQ(DrumMachine::soundName(8), "Open Hat");
    EXPECT_STREQ(DrumMachine::soundName(9), "Crash");
    EXPECT_STREQ(DrumMachine::soundName(10), "Ride");
    for (int k = 11; k <= 13; ++k) EXPECT_NE(std::string(DrumMachine::soundName(k)).find("Tom"), std::string::npos);
}

TEST(DrumKit, EverySoundIsAudiblePeakNormalisedAndCleanAtBothEnds) {
    const DrumKit& kit = kit48();
    const float targetPeak = std::pow(10.0f, DrumKit::kPeakDb / 20.0f);
    for (int k = 0; k < DrumKit::kSoundCount; ++k) {
        SCOPED_TRACE(DrumKit::name(k));
        const auto x = sound(kit, k);
        ASSERT_GT(x.size(), static_cast<std::size_t>(0.08 * kFs));
        ASSERT_LT(x.size(), static_cast<std::size_t>(3.0 * kFs));
        const float peak = groove::test::peakAbs(x);
        EXPECT_NEAR(peak, targetPeak, 1e-4f);
        EXPECT_GT(groove::test::rms(x, 0, std::min<std::size_t>(x.size(), 4800)), 0.02) << "first 100 ms too quiet";
        // Starts from zero and ends at zero, so neither forward nor reverse playback clicks.
        EXPECT_EQ(x.front(), 0.0f);
        EXPECT_LT(std::fabs(x.back()), 1e-6f);
        EXPECT_LT(std::fabs(x[1]), 0.1f) << "onset ramp";
        EXPECT_LT(groove::test::peakAbs(x, x.size() - 48), 0.01f) << "tail must fade out";
        // No DC offset worth speaking of.
        double mean = 0.0;
        for (float v : x) mean += v;
        EXPECT_LT(std::fabs(mean / static_cast<double>(x.size())), 0.005);
        // Guard samples either side are zero (the interpolator reads them).
        for (int g = 1; g <= DrumKit::kGuard; ++g) {
            EXPECT_EQ(kit.data(k)[-g], 0.0f);
            EXPECT_EQ(kit.data(k)[kit.length(k) - 1 + g], 0.0f);
        }
    }
}

TEST(DrumKit, SoundsAreDistinct) {
    const DrumKit& kit = kit48();
    std::vector<std::vector<double>> spectra;
    for (int k = 0; k < DrumKit::kSoundCount; ++k) {
        auto x = sound(kit, k);
        x.resize(8192, 0.0f);
        auto mag = magnitudeSpectrum(x, 0, x.size());
        double norm = 0.0;
        for (double m : mag) norm += m * m;
        norm = std::sqrt(norm);
        for (double& m : mag) m /= norm;
        spectra.push_back(mag);
    }
    for (int a = 0; a < DrumKit::kSoundCount; ++a) {
        for (int b = a + 1; b < DrumKit::kSoundCount; ++b) {
            double dot = 0.0;
            for (std::size_t i = 0; i < spectra[0].size(); ++i) dot += spectra[a][i] * spectra[b][i];
            EXPECT_LT(dot, 0.96) << DrumKit::name(a) << " vs " << DrumKit::name(b);
        }
    }
}

TEST(DrumKit, SoundsHaveTheirRoleCharacter) {
    const DrumKit& kit = kit48();
    auto centroid = [&](int k) { return spectralCentroid(sound(kit, k), kFs); };
    auto seconds = [&](int k) { return static_cast<double>(kit.length(k)) / kFs; };
    EXPECT_LT(strongestHz(sound(kit, 0), kFs), 80.0);  // kicks: low fundamental
    EXPECT_LT(strongestHz(sound(kit, 1), kFs), 80.0);
    EXPECT_LT(centroid(0), 300.0);
    EXPECT_LT(centroid(1), 300.0);
    for (int k : {6, 7, 8}) EXPECT_GT(centroid(k), 8000.0) << DrumKit::name(k);  // hats bright
    EXPECT_GT(centroid(9), 5000.0);
    EXPECT_GT(seconds(8), 3.0 * seconds(6));  // open hat rings, closed hats don't
    EXPECT_GT(seconds(8), 3.0 * seconds(7));
    EXPECT_GT(seconds(9), 1.5);  // cymbals ring long
    EXPECT_GT(seconds(10), 1.5);
    // Toms rise in pitch low -> mid -> high.
    const double low = strongestHz(sound(kit, 11), kFs), mid = strongestHz(sound(kit, 12), kFs),
                 high = strongestHz(sound(kit, 13), kFs);
    EXPECT_LT(low, mid);
    EXPECT_LT(mid, high);
    EXPECT_GT(low, 60.0);
    EXPECT_LT(high, 300.0);
}

TEST(DrumKit, RenderIsDeterministic) {
    DrumKit a, b;
    a.render(kFs);
    b.render(kFs);
    for (int k = 0; k < DrumKit::kSoundCount; ++k) {
        ASSERT_EQ(a.length(k), b.length(k));
        EXPECT_EQ(std::memcmp(a.data(k), b.data(k), sizeof(float) * static_cast<std::size_t>(a.length(k))), 0)
            << DrumKit::name(k);
    }
}

TEST(DrumKit, SoundsKeepTheirTimingAtOtherSampleRates) {
    const DrumKit& base = kit48();
    for (float rate : {44100.0f, 96000.0f}) {
        DrumKit kit;
        kit.render(rate);
        EXPECT_EQ(kit.sampleRate(), rate);
        for (int k = 0; k < DrumKit::kSoundCount; ++k) {
            const double s48 = static_cast<double>(base.length(k)) / kFs;
            const double s = static_cast<double>(kit.length(k)) / static_cast<double>(rate);
            EXPECT_NEAR(s, s48, 0.03 * s48 + 0.003) << DrumKit::name(k) << " at " << rate;
        }
    }
}

TEST(DrumKit, RendersQuickly) {
    DrumKit kit;
    const auto t0 = std::chrono::steady_clock::now();
    kit.render(kFs);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    std::printf("[ kit render ] %.1f ms at 48 kHz\n", ms);
    RecordProperty("kit_render_ms", std::to_string(ms));
#if defined(__OPTIMIZE__) && !defined(__SANITIZE_ADDRESS__) && !defined(__SANITIZE_THREAD__)
    EXPECT_LT(ms, 100.0);  // optimised builds (the app ships optimised): about 20 ms here
#else
    // Unoptimised and sanitizer builds run the same code several times slower; this
    // bound only catches an accidental blow-up.
    EXPECT_LT(ms, 2000.0);
#endif
}
