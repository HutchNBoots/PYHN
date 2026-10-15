#include "groove/Presets.h"

#include <array>
#include <initializer_list>

namespace groove {

namespace {

struct Setting {
    ParamId id;
    float value;
};

// Starts from the parameter defaults, so every preset is a full value set.
constexpr ParamValues values(std::initializer_list<Setting> settings) {
    ParamValues v = defaultParamValues();
    for (const Setting& s : settings) v[index(s.id)] = s.value;
    return v;
}

using P = ParamId;

// Voice modes and option indices, as in SynthParams.def.
constexpr float kMono = 1.0f, kUnison = 2.0f;
constexpr float kSine = 0.0f, kTri = 1.0f, kSampleHold = 3.0f;
constexpr float kToPitch = 0.0f, kToCutoff = 1.0f, kToPw = 2.0f;

constexpr std::array<Preset, 16> kPresets = {{
    {"First Light", "init",
     values({{P::OscWave, 0.15f}, {P::FltCutoff, 1800.0f}, {P::FltReso, 0.25f}, {P::FltEnvAmount, 0.35f},
             {P::AmpAttack, 0.004f}, {P::AmpDecay, 0.6f}, {P::AmpSustain, 0.7f}, {P::AmpRelease, 0.35f},
             {P::FenvAttack, 0.003f}, {P::FenvDecay, 0.45f}, {P::FenvSustain, 0.25f}, {P::FenvRelease, 0.4f},
             {P::LfoRate, 0.35f}, {P::LfoDepth, 0.12f}, {P::LfoDest, kToCutoff}, {P::LfoRetrigger, 0.0f}})},

    // --- bass ---
    {"Rubber Floor", "bass",
     values({{P::OscWave, 0.0f}, {P::OscSub, 0.6f}, {P::OscOctave, -1.0f}, {P::OscVoiceMode, kMono},
             {P::OscGlide, 0.04f}, {P::FltCutoff, 300.0f}, {P::FltReso, 0.35f}, {P::FltEnvAmount, 0.45f},
             {P::FltKeyTrack, 0.3f}, {P::FltDrive, 6.0f}, {P::FltVelocity, 0.4f}, {P::AmpAttack, 0.001f},
             {P::AmpDecay, 0.4f}, {P::AmpSustain, 0.6f}, {P::AmpRelease, 0.08f}, {P::FenvAttack, 0.001f},
             {P::FenvDecay, 0.22f}, {P::FenvSustain, 0.05f}, {P::FenvRelease, 0.1f}})},
    {"Deep Current", "bass",
     values({{P::OscWave, 2.6f}, {P::OscSub, 0.5f}, {P::OscOctave, -1.0f}, {P::OscVoiceMode, kMono},
             {P::OscGlide, 0.08f}, {P::FltCutoff, 900.0f}, {P::FltReso, 0.1f}, {P::FltEnvAmount, 0.15f},
             {P::FltDrive, 3.0f}, {P::AmpAttack, 0.003f}, {P::AmpDecay, 1.0f}, {P::AmpSustain, 0.9f},
             {P::AmpRelease, 0.12f}, {P::FenvDecay, 0.3f}, {P::FenvSustain, 0.4f}})},
    {"Gravel Bass", "bass",
     values({{P::OscWave, 0.6f}, {P::OscPulseWidth, 0.4f}, {P::OscSub, 0.3f}, {P::OscOctave, -1.0f},
             {P::OscVoiceMode, kMono}, {P::FltCutoff, 220.0f}, {P::FltReso, 0.6f}, {P::FltEnvAmount, 0.55f},
             {P::FltDrive, 15.0f}, {P::FltHighPass, 40.0f}, {P::FltVelocity, 0.5f}, {P::AmpAttack, 0.001f},
             {P::AmpDecay, 0.3f}, {P::AmpSustain, 0.5f}, {P::AmpRelease, 0.06f}, {P::FenvAttack, 0.001f},
             {P::FenvDecay, 0.18f}, {P::FenvSustain, 0.1f}, {P::FenvRelease, 0.08f}})},

    // --- lead ---
    {"Glass Wire", "lead",
     values({{P::OscWave, 1.0f}, {P::OscPulseWidth, 0.35f}, {P::OscVoiceMode, kMono}, {P::OscGlide, 0.09f},
             {P::FltCutoff, 3200.0f}, {P::FltReso, 0.3f}, {P::FltEnvAmount, 0.25f}, {P::FltKeyTrack, 0.6f},
             {P::AmpAttack, 0.005f}, {P::AmpDecay, 0.5f}, {P::AmpSustain, 0.85f}, {P::AmpRelease, 0.25f},
             {P::FenvDecay, 0.5f}, {P::FenvSustain, 0.5f}, {P::LfoRate, 4.5f}, {P::LfoDepth, 0.35f},
             {P::LfoWave, kSine}, {P::LfoDest, kToPw}, {P::LfoRetrigger, 0.0f}})},
    {"Copper Lead", "lead",
     values({{P::OscWave, 0.0f}, {P::OscVoiceMode, kUnison}, {P::OscGlide, 0.05f}, {P::FltCutoff, 2600.0f},
             {P::FltReso, 0.25f}, {P::FltEnvAmount, 0.3f}, {P::FltKeyTrack, 0.6f}, {P::FltDrive, 4.0f},
             {P::AmpAttack, 0.003f}, {P::AmpDecay, 0.4f}, {P::AmpSustain, 0.8f}, {P::AmpRelease, 0.3f},
             {P::FenvAttack, 0.002f}, {P::FenvDecay, 0.35f}, {P::FenvSustain, 0.4f}, {P::FenvRelease, 0.3f},
             {P::LfoRate, 5.5f}, {P::LfoDepth, 0.15f}, {P::LfoDest, kToPitch}, {P::LfoDelay, 0.6f}})},
    {"Whistle Pine", "lead",
     values({{P::OscWave, 2.7f}, {P::OscNoise, 0.04f}, {P::OscVoiceMode, kMono}, {P::OscGlide, 0.12f},
             {P::FltCutoff, 6000.0f}, {P::FltReso, 0.0f}, {P::FltEnvAmount, 0.1f}, {P::FltHighPass, 120.0f},
             {P::AmpAttack, 0.02f}, {P::AmpDecay, 0.3f}, {P::AmpSustain, 0.9f}, {P::AmpRelease, 0.3f},
             {P::LfoRate, 5.0f}, {P::LfoDepth, 0.18f}, {P::LfoDest, kToPitch}, {P::LfoDelay, 0.8f}})},

    // --- pad ---
    {"Slow Tide", "pad",
     values({{P::OscWave, 0.0f}, {P::FltCutoff, 900.0f}, {P::FltReso, 0.3f}, {P::FltEnvAmount, 0.25f},
             {P::FltKeyTrack, 0.4f}, {P::FltVelocity, 0.2f}, {P::AmpAttack, 1.2f}, {P::AmpDecay, 2.0f},
             {P::AmpSustain, 0.85f}, {P::AmpRelease, 2.5f}, {P::FenvAttack, 1.5f}, {P::FenvDecay, 2.5f},
             {P::FenvSustain, 0.6f}, {P::FenvRelease, 2.5f}, {P::LfoRate, 0.15f}, {P::LfoDepth, 0.35f},
             {P::LfoWave, kTri}, {P::LfoDest, kToCutoff}, {P::LfoRetrigger, 0.0f}})},
    {"Velvet Fog", "pad",
     values({{P::OscWave, 1.0f}, {P::OscSub, 0.2f}, {P::OscNoise, 0.03f}, {P::FltCutoff, 1500.0f},
             {P::FltReso, 0.15f}, {P::FltEnvAmount, 0.15f}, {P::AmpAttack, 0.6f}, {P::AmpDecay, 1.5f},
             {P::AmpSustain, 0.9f}, {P::AmpRelease, 1.8f}, {P::FenvAttack, 0.8f}, {P::FenvSustain, 0.7f},
             {P::LfoRate, 0.6f}, {P::LfoDepth, 0.6f}, {P::LfoDest, kToPw}, {P::LfoRetrigger, 0.0f}})},
    {"Northern Hall", "pad",
     values({{P::OscWave, 0.3f}, {P::OscVoiceMode, kUnison}, {P::FltCutoff, 1200.0f}, {P::FltReso, 0.2f},
             {P::FltEnvAmount, 0.2f}, {P::AmpAttack, 0.9f}, {P::AmpDecay, 2.0f}, {P::AmpSustain, 0.8f},
             {P::AmpRelease, 3.0f}, {P::FenvAttack, 1.2f}, {P::FenvDecay, 3.0f}, {P::FenvSustain, 0.5f},
             {P::FenvRelease, 3.0f}, {P::LfoRate, 0.25f}, {P::LfoDepth, 0.2f}, {P::LfoDest, kToCutoff},
             {P::LfoRetrigger, 0.0f}})},

    // --- pluck ---
    {"Rain Glass", "pluck",
     values({{P::OscWave, 2.0f}, {P::FltCutoff, 1200.0f}, {P::FltReso, 0.35f}, {P::FltEnvAmount, 0.5f},
             {P::FltKeyTrack, 0.8f}, {P::FltVelocity, 0.6f}, {P::AmpAttack, 0.001f}, {P::AmpDecay, 0.45f},
             {P::AmpSustain, 0.0f}, {P::AmpRelease, 0.4f}, {P::FenvAttack, 0.001f}, {P::FenvDecay, 0.18f},
             {P::FenvSustain, 0.0f}, {P::FenvRelease, 0.3f}})},
    {"Thumb Piano", "pluck",
     values({{P::OscWave, 2.5f}, {P::OscNoise, 0.05f}, {P::FltCutoff, 2500.0f}, {P::FltReso, 0.1f},
             {P::FltEnvAmount, 0.3f}, {P::FltKeyTrack, 1.0f}, {P::FltHighPass, 150.0f}, {P::AmpAttack, 0.001f},
             {P::AmpDecay, 0.9f}, {P::AmpSustain, 0.0f}, {P::AmpRelease, 0.6f}, {P::FenvAttack, 0.001f},
             {P::FenvDecay, 0.08f}, {P::FenvSustain, 0.0f}})},
    {"Paper Harp", "pluck",
     values({{P::OscWave, 0.0f}, {P::FltCutoff, 600.0f}, {P::FltReso, 0.65f}, {P::FltEnvAmount, 0.55f},
             {P::FltKeyTrack, 0.7f}, {P::FltDrive, 3.0f}, {P::FltVelocity, 0.5f}, {P::AmpAttack, 0.001f},
             {P::AmpDecay, 0.7f}, {P::AmpSustain, 0.0f}, {P::AmpRelease, 0.5f}, {P::FenvAttack, 0.001f},
             {P::FenvDecay, 0.25f}, {P::FenvSustain, 0.0f}, {P::FenvRelease, 0.3f}})},

    // --- fx ---
    {"Siren Loop", "fx",
     values({{P::OscWave, 1.0f}, {P::OscVoiceMode, kMono}, {P::FltCutoff, 3000.0f}, {P::FltReso, 0.4f},
             {P::FltEnvAmount, 0.0f}, {P::AmpAttack, 0.01f}, {P::AmpDecay, 0.5f}, {P::AmpSustain, 1.0f},
             {P::AmpRelease, 0.6f}, {P::LfoRate, 0.8f}, {P::LfoDepth, 0.7f}, {P::LfoWave, kTri},
             {P::LfoDest, kToPitch}})},
    {"Static Garden", "fx",
     values({{P::OscWave, 3.0f}, {P::OscNoise, 0.6f}, {P::FltCutoff, 1500.0f}, {P::FltReso, 0.75f},
             {P::FltEnvAmount, 0.0f}, {P::FltKeyTrack, 1.0f}, {P::AmpAttack, 0.05f}, {P::AmpDecay, 1.0f},
             {P::AmpSustain, 0.9f}, {P::AmpRelease, 1.2f}, {P::LfoRate, 8.0f}, {P::LfoDepth, 0.7f},
             {P::LfoWave, kSampleHold}, {P::LfoDest, kToCutoff}, {P::LfoSync, 1.0f}})},
    {"Laser Drip", "fx",
     values({{P::OscWave, 2.0f}, {P::FltCutoff, 300.0f}, {P::FltReso, 0.97f}, {P::FltEnvAmount, 0.8f},
             {P::FltKeyTrack, 1.0f}, {P::FltDrive, 6.0f}, {P::FltHighPass, 80.0f}, {P::AmpAttack, 0.001f},
             {P::AmpDecay, 0.6f}, {P::AmpSustain, 0.0f}, {P::AmpRelease, 0.4f}, {P::FenvAttack, 0.001f},
             {P::FenvDecay, 0.35f}, {P::FenvSustain, 0.0f}, {P::FenvRelease, 0.3f}})},
}};

}  // namespace

int presetCount() { return static_cast<int>(kPresets.size()); }

const Preset& preset(int index) {
    if (index < 0 || index >= presetCount()) index = 0;
    return kPresets[static_cast<std::size_t>(index)];
}

}  // namespace groove
