#include "groove/SynthParams.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <set>
#include <string>

using namespace groove;

TEST(SynthParams, TableMatchesTheDefFile) {
    EXPECT_EQ(kParamCount, 31u);
    EXPECT_STREQ(paramInfo(ParamId::OscWave).key, "osc.wave");
    EXPECT_STREQ(paramInfo(ParamId::LfoRetrigger).key, "lfo.retrig");
    EXPECT_EQ(paramInfo(ParamId::FltCutoff).curve, Curve::Exp);
    EXPECT_FLOAT_EQ(paramInfo(ParamId::FltCutoff).max, 18000.0f);
    EXPECT_STREQ(paramInfo(ParamId::OscVoiceMode).unit, "poly|mono|unison");
    EXPECT_EQ(static_cast<int>(Curve::Enum), 4);
}

TEST(SynthParams, EveryRowIsWellFormed) {
    std::set<std::string> keys;
    for (const ParamInfo& p : kParamInfo) {
        EXPECT_TRUE(keys.insert(p.key).second) << "duplicate " << p.key;
        EXPECT_LT(p.min, p.max) << p.key;
        EXPECT_GE(p.def, p.min) << p.key;
        EXPECT_LE(p.def, p.max) << p.key;
        if (p.curve == Curve::Exp) EXPECT_GT(p.min, 0.0f) << p.key;
        if (p.discrete()) EXPECT_EQ(p.def, std::nearbyint(p.def)) << p.key;
        if (p.curve == Curve::Enum) {
            const std::string unit = p.unit;
            const long options = 1 + std::count(unit.begin(), unit.end(), '|');
            EXPECT_EQ(options, static_cast<long>(p.max - p.min) + 1) << p.key;
        }
    }
}

TEST(SynthParams, StoreStartsAtDefaultsAndClamps) {
    ParamStore store;
    for (std::size_t i = 0; i < kParamCount; ++i) EXPECT_EQ(store.get(static_cast<ParamId>(i)), kParamInfo[i].def);
    store.set(ParamId::FltCutoff, 1e9f);
    EXPECT_EQ(store.get(ParamId::FltCutoff), 18000.0f);
    store.set(ParamId::FltCutoff, -5.0f);
    EXPECT_EQ(store.get(ParamId::FltCutoff), 20.0f);
    store.set(ParamId::OscOctave, 1.4f);
    EXPECT_EQ(store.get(ParamId::OscOctave), 1.0f);
    store.set(ParamId::OscVoiceMode, 1.6f);
    EXPECT_EQ(store.get(ParamId::OscVoiceMode), 2.0f);
    store.set(ParamId::FltReso, std::nanf(""));
    EXPECT_EQ(store.get(ParamId::FltReso), paramInfo(ParamId::FltReso).def);
    store.set(ParamId::FltReso, INFINITY);
    EXPECT_EQ(store.get(ParamId::FltReso), 1.0f);
}

TEST(SynthParams, SnapshotCopiesEveryValue) {
    ParamStore store;
    store.set(ParamId::AmpRelease, 2.0f);
    ParamValues v{};
    store.snapshot(v);
    EXPECT_EQ(v[index(ParamId::AmpRelease)], 2.0f);
    EXPECT_EQ(v[index(ParamId::OscWave)], 0.0f);
}
