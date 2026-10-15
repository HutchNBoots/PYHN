#include "groove/Presets.h"

#include <gtest/gtest.h>

#include <cmath>
#include <set>
#include <string>
#include <vector>

#include "Analysis.h"
#include "groove/AudioGraph.h"

using namespace groove;
using namespace groove::test;

TEST(Presets, SixteenUniqueNamedPresetsCoveringEveryCategory) {
    ASSERT_EQ(presetCount(), 16);
    std::set<std::string> names, categories;
    for (int i = 0; i < presetCount(); ++i) {
        const std::string name = preset(i).name;
        EXPECT_FALSE(name.empty());
        EXPECT_LE(name.size(), 16u) << name;  // fits a preset strip cell
        EXPECT_TRUE(names.insert(name).second) << "duplicate " << name;
        categories.insert(preset(i).category);
    }
    for (const char* c : {"bass", "lead", "pad", "pluck", "fx"}) EXPECT_TRUE(categories.count(c)) << c;
}

TEST(Presets, EveryValueIsInRange) {
    for (int i = 0; i < presetCount(); ++i) {
        const Preset& p = preset(i);
        for (std::size_t k = 0; k < kParamCount; ++k) {
            const auto id = static_cast<ParamId>(k);
            EXPECT_EQ(p.values[k], sanitizeParam(id, p.values[k])) << p.name << " " << kParamInfo[k].key;
        }
    }
}

TEST(Presets, OutOfRangeIndexFallsBackToFirst) {
    EXPECT_STREQ(preset(-1).name, preset(0).name);
    EXPECT_STREQ(preset(16).name, preset(0).name);
    SynthEngine synth;
    EXPECT_FALSE(synth.loadPreset(16));
    EXPECT_FALSE(synth.loadPreset(-1));
}

TEST(Presets, LoadSetsEveryParameter) {
    SynthEngine synth;
    for (int i = 0; i < presetCount(); ++i) {
        ASSERT_TRUE(synth.loadPreset(i));
        for (std::size_t k = 0; k < kParamCount; ++k) {
            EXPECT_EQ(synth.param(static_cast<ParamId>(k)), preset(i).values[k]) << preset(i).name;
        }
    }
}

// Every preset makes a healthy sound: audible, finite, under the limiter's ceiling,
// and silent again after release.
TEST(Presets, EveryPresetSoundsAndStops) {
    for (int i = 0; i < presetCount(); ++i) {
        AudioGraph graph;
        graph.prepare(48000.0f);
        graph.synth().loadPreset(i);
        std::vector<float> out(2 * 48000);
        graph.synth().noteOn(48, 0.8f, 0);
        graph.synth().noteOn(55, 0.8f, 0);
        graph.synth().noteOn(60, 0.8f, 0);
        graph.render(out.data(), 48000, 2);
        double sum = 0.0;
        float peak = 0.0f;
        for (float s : out) {
            ASSERT_TRUE(std::isfinite(s)) << preset(i).name;
            sum += static_cast<double>(s) * s;
            peak = std::max(peak, std::fabs(s));
        }
        const double rmsDb = 10.0 * std::log10(sum / static_cast<double>(out.size()));
        std::printf("%-14s %-5s rms %6.1f dBFS  peak %6.1f dBFS\n", preset(i).name, preset(i).category, rmsDb,
                    20.0 * std::log10(peak));
        EXPECT_GT(rmsDb, -40.0) << preset(i).name;
        EXPECT_LE(peak, std::pow(10.0f, -0.3f / 20.0f)) << preset(i).name;

        graph.synth().allNotesOff();
        for (int s = 0; s < 8; ++s) graph.render(out.data(), 48000, 2);  // longest release is 3 s
        EXPECT_EQ(peakAbs(out), 0.0f) << preset(i).name;
    }
}
