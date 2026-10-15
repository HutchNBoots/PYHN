#include "groove/SynthEngine.h"

#include <gtest/gtest.h>

#include <atomic>
#include <cmath>
#include <thread>
#include <vector>

#include "Analysis.h"
#include "EngineTestUtil.h"

using namespace groove;
using namespace groove::test;

namespace {

constexpr float kRate = 48000.0f;
constexpr int kBlock = 96;  // 2 ms, as on the S25+

double noteHz(double note) { return 440.0 * std::pow(2.0, (note - 69.0) / 12.0); }

int activeVoices(const SynthEngine& s) {
    int n = 0;
    for (int i = 0; i < SynthEngine::kVoiceCount; ++i) n += s.voiceInfo(i).active ? 1 : 0;
    return n;
}

int voiceWithNote(const SynthEngine& s, int note) {
    for (int i = 0; i < SynthEngine::kVoiceCount; ++i) {
        if (s.voiceInfo(i).active && s.voiceInfo(i).note == note) return i;
    }
    return -1;
}

}  // namespace

class SynthEngineTest : public ::testing::Test {
protected:
    void SetUp() override {
        setCleanSine(synth);
        synth.prepare(kRate);  // snaps smoothing to the patch
    }
    std::vector<float> render(int frames, int block = kBlock) { return renderLeft(synth, frames, block); }
    void setMode(SynthEngine::VoiceMode m) {
        synth.setParam(ParamId::OscVoiceMode, static_cast<float>(m));
        render(kBlock);
    }

    SynthEngine synth;
};

// --- basics, carried over from the latency spike ---

TEST_F(SynthEngineTest, SilentWithoutNotes) { EXPECT_EQ(peakAbs(render(4800)), 0.0f); }

TEST_F(SynthEngineTest, NoteOnSoundsWithinFirstMillisecondOfNextBlock) {
    ASSERT_TRUE(synth.noteOn(69, 1.0f, 0));
    auto x = render(kBlock);
    EXPECT_GT(peakAbs(x, 0, 48), 0.0f);
    EXPECT_GT(peakAbs(x), 0.05f);
}

TEST_F(SynthEngineTest, NoteOffDecaysToExactSilence) {
    synth.noteOn(60, 1.0f, 0);
    render(4800);
    synth.noteOff(60);
    auto x = render(4800);
    EXPECT_EQ(peakAbs(x, 2400), 0.0f);
    EXPECT_EQ(activeVoices(synth), 0);
}

TEST_F(SynthEngineTest, AllNotesOffSilencesEverything) {
    for (int n : {60, 64, 67, 71}) synth.noteOn(n, 1.0f, 0);
    render(kBlock);
    synth.allNotesOff();
    EXPECT_EQ(peakAbs(render(4800), 2400), 0.0f);
}

TEST_F(SynthEngineTest, RejectsNotesOutsideMidiRange) {
    EXPECT_FALSE(synth.noteOn(-1, 1.0f, 0));
    EXPECT_FALSE(synth.noteOn(128, 1.0f, 0));
    EXPECT_FALSE(synth.noteOff(200));
    EXPECT_TRUE(synth.noteOn(0, std::nanf(""), 0));  // bad velocity is sanitised
    EXPECT_TRUE(synth.noteOn(127, 7.0f, 0));
    auto x = render(4800);
    for (float s : x) ASSERT_TRUE(std::isfinite(s));
}

TEST_F(SynthEngineTest, MeasuredNoteOnReportsStartFrame) {
    render(kBlock);
    render(kBlock);
    ASSERT_TRUE(synth.noteOn(60, 0.7f, 123456789));
    render(kBlock);
    NoteOnRender rec;
    ASSERT_TRUE(synth.popNoteOnRender(rec));
    EXPECT_EQ(rec.tapTimeNs, 123456789);
    EXPECT_EQ(rec.frameIndex, 2 * kBlock);
    EXPECT_FALSE(synth.popNoteOnRender(rec));
    EXPECT_EQ(synth.framesRendered(), 3 * kBlock);
}

TEST_F(SynthEngineTest, UnmeasuredEventsProduceNoRecord) {
    synth.noteOn(60, 1.0f, 0);
    synth.noteOff(60);
    render(kBlock);
    NoteOnRender rec;
    EXPECT_FALSE(synth.popNoteOnRender(rec));
}

TEST_F(SynthEngineTest, PrepareRestartsFrameCounter) {
    render(kBlock);
    synth.prepare(kRate);
    EXPECT_EQ(synth.framesRendered(), 0);
}

TEST_F(SynthEngineTest, AnyBlockSizeWorks) {
    synth.noteOn(60, 1.0f, 0);
    for (int n : {1, 7, 16, 17, 100, 333, 1024, 4096}) {
        auto x = render(n, n);
        for (float s : x) ASSERT_TRUE(std::isfinite(s));
    }
    EXPECT_GT(synth.framesRendered(), 5000);
}

TEST_F(SynthEngineTest, VelocityScalesLevelGently) {
    synth.noteOn(60, 1.0f, 0);
    const double loud = rms(render(9600), 4800);
    synth.allNotesOff();
    render(4800);
    synth.noteOn(60, 0.5f, 0);
    const double soft = rms(render(9600), 4800);
    EXPECT_NEAR(soft / loud, 0.8 / 1.0, 0.02);  // gain 0.6 + 0.4 * velocity
}

TEST_F(SynthEngineTest, PolyOutputIsTheSameOnBothChannels) {
    synth.setParam(ParamId::OscWave, 0.0f);
    synth.noteOn(60, 1.0f, 0);
    synth.noteOn(67, 1.0f, 0);
    auto b = renderStereo(synth, 4800);
    EXPECT_EQ(b.left, b.right);
}

// --- F1.1 polyphony and voice stealing ---

TEST_F(SynthEngineTest, FourVoicePolyphony) {
    const int notes[] = {48, 55, 60, 64};
    for (int n : notes) synth.noteOn(n, 1.0f, 0);
    render(kBlock);
    EXPECT_EQ(activeVoices(synth), 4);
    for (int n : notes) {
        const int v = voiceWithNote(synth, n);
        ASSERT_GE(v, 0) << n;
        EXPECT_NEAR(cents(synth.voiceInfo(v).frequencyHz, noteHz(n)), 0.0, 0.01);
    }
}

TEST_F(SynthEngineTest, StealsOldestReleasedVoiceFirst) {
    synth.setParam(ParamId::AmpRelease, 5.0f);  // released voices keep sounding
    synth.prepare(kRate);
    for (int n : {48, 52, 55, 59}) synth.noteOn(n, 1.0f, 0);
    render(kBlock);
    synth.noteOff(55);  // released first: the oldest released voice
    render(kBlock);
    synth.noteOff(52);
    render(kBlock);
    synth.noteOn(72, 1.0f, 0);
    render(4 * kBlock);
    EXPECT_EQ(voiceWithNote(synth, 55), -1);
    EXPECT_GE(voiceWithNote(synth, 52), 0);
    EXPECT_GE(voiceWithNote(synth, 48), 0);
    EXPECT_GE(voiceWithNote(synth, 59), 0);
    EXPECT_GE(voiceWithNote(synth, 72), 0);
}

TEST_F(SynthEngineTest, StealsOldestHeldVoiceWhenNoneReleased) {
    for (int n : {48, 52, 55, 59}) {
        synth.noteOn(n, 1.0f, 0);
        render(kBlock);
    }
    synth.noteOn(72, 1.0f, 0);
    render(4 * kBlock);
    EXPECT_EQ(voiceWithNote(synth, 48), -1);
    for (int n : {52, 55, 59, 72}) EXPECT_GE(voiceWithNote(synth, n), 0) << n;
    synth.noteOn(74, 1.0f, 0);
    render(4 * kBlock);
    EXPECT_EQ(voiceWithNote(synth, 52), -1);
}

TEST_F(SynthEngineTest, RepeatedNoteReusesItsVoice) {
    synth.setParam(ParamId::AmpRelease, 5.0f);
    synth.prepare(kRate);
    for (int i = 0; i < 6; ++i) {
        synth.noteOn(60, 1.0f, 0);
        render(kBlock);
        synth.noteOff(60);
        render(kBlock);
    }
    EXPECT_EQ(activeVoices(synth), 1);
}

// The stolen voice is gone within 2 ms, and the steal adds no click: the largest
// sample-to-sample step stays at the level of the steady signal around it.
TEST_F(SynthEngineTest, StolenVoiceFadesWithin2msWithoutClick) {
    synth.setParam(ParamId::AmpAttack, 0.005f);
    synth.prepare(kRate);
    std::vector<float> x;
    for (int n : {57, 61, 64, 69}) {
        synth.noteOn(n, 1.0f, 0);
        append(x, render(kBlock));
    }
    append(x, render(4800));
    const size_t stealAt = x.size();
    synth.noteOn(76, 1.0f, 0);
    // Render in sub-blocks to see when the voice switches.
    int switchedAfter = -1;
    for (int done = 0; done < 9600; done += 16) {
        append(x, renderLeft(synth, 16, 16));
        if (switchedAfter < 0 && voiceWithNote(synth, 57) < 0) switchedAfter = done + 16;
    }
    EXPECT_GT(switchedAfter, 0);
    EXPECT_LE(switchedAfter, 96) << "stolen voice must be gone within 2 ms";

    const float before = maxStep(x, stealAt - 4800, stealAt);
    const float after = maxStep(x, stealAt + 4800, x.size());
    const float during = maxStep(x, stealAt, stealAt + 480);
    std::printf("max step before %.4f, during steal %.4f, after %.4f\n", before, during, after);
    EXPECT_LE(during, 1.1f * std::max(before, after));
}

// --- F1.2 mono, legato, glide, unison ---

TEST_F(SynthEngineTest, MonoUsesLastNotePriority) {
    setMode(SynthEngine::VoiceMode::Mono);
    synth.noteOn(60, 1.0f, 0);
    synth.noteOn(64, 1.0f, 0);
    synth.noteOn(67, 1.0f, 0);
    render(kBlock);
    EXPECT_EQ(activeVoices(synth), 1);
    EXPECT_EQ(synth.voiceInfo(0).note, 67);
    synth.noteOff(64);  // not the top note: no change
    render(kBlock);
    EXPECT_EQ(synth.voiceInfo(0).note, 67);
    synth.noteOff(67);  // back to the last held note
    render(kBlock);
    EXPECT_EQ(synth.voiceInfo(0).note, 60);
    EXPECT_NEAR(cents(synth.voiceInfo(0).frequencyHz, noteHz(60)), 0.0, 0.01);
    synth.noteOff(60);
    render(4800);
    EXPECT_EQ(activeVoices(synth), 0);
}

TEST_F(SynthEngineTest, MonoIsLegatoWhileHeldAndRetriggersAfterRelease) {
    synth.setParam(ParamId::AmpDecay, 0.05f);
    synth.setParam(ParamId::AmpSustain, 0.5f);
    synth.setParam(ParamId::AmpRelease, 2.0f);
    synth.setParam(ParamId::OscVoiceMode, 1.0f);
    synth.prepare(kRate);
    synth.noteOn(60, 1.0f, 0);
    render(24000);
    ASSERT_NEAR(synth.voiceInfo(0).ampLevel, 0.5f, 1e-3f);
    synth.noteOn(64, 1.0f, 0);  // legato: no retrigger
    render(4800);
    EXPECT_NEAR(synth.voiceInfo(0).ampLevel, 0.5f, 1e-3f);
    EXPECT_EQ(synth.voiceInfo(0).note, 64);

    synth.noteOff(64);
    synth.noteOff(60);
    render(kBlock);
    const float released = synth.voiceInfo(0).ampLevel;
    synth.noteOn(62, 1.0f, 0);  // nothing held: retrigger from the current level
    float peak = 0.0f;
    for (int i = 0; i < 30; ++i) {
        render(16, 16);
        peak = std::max(peak, synth.voiceInfo(0).ampLevel);
    }
    EXPECT_GT(released, 0.4f);
    EXPECT_GT(peak, 0.99f);  // the attack ran again
}

TEST_F(SynthEngineTest, GlideIsConstantTimeInMono) {
    synth.setParam(ParamId::OscGlide, 0.2f);
    synth.setParam(ParamId::OscVoiceMode, 1.0f);
    synth.prepare(kRate);
    synth.noteOn(48, 1.0f, 0);
    render(4800);
    EXPECT_NEAR(cents(synth.voiceInfo(0).frequencyHz, noteHz(48)), 0.0, 0.01);  // first note: no glide
    synth.noteOn(60, 1.0f, 0);
    render(4800);  // halfway
    const double halfway = 48.0 + cents(synth.voiceInfo(0).frequencyHz, noteHz(48)) / 100.0;
    EXPECT_NEAR(halfway, 54.0, 0.2);
    render(4800 + 32);
    EXPECT_NEAR(cents(synth.voiceInfo(0).frequencyHz, noteHz(60)), 0.0, 0.01);
}

TEST_F(SynthEngineTest, NoGlideInPoly) {
    synth.setParam(ParamId::OscGlide, 1.0f);
    synth.prepare(kRate);
    synth.noteOn(48, 1.0f, 0);
    render(kBlock);
    synth.noteOff(48);
    synth.noteOn(60, 1.0f, 0);
    render(kBlock);
    EXPECT_NEAR(cents(synth.voiceInfo(voiceWithNote(synth, 60)).frequencyHz, noteHz(60)), 0.0, 0.01);
}

TEST_F(SynthEngineTest, UnisonStacksFourVoicesDetunedTenCents) {
    setMode(SynthEngine::VoiceMode::Unison);
    synth.noteOn(57, 1.0f, 0);
    render(kBlock);
    ASSERT_EQ(activeVoices(synth), 4);
    std::vector<double> c;
    for (int i = 0; i < 4; ++i) {
        EXPECT_EQ(synth.voiceInfo(i).note, 57);
        c.push_back(cents(synth.voiceInfo(i).frequencyHz, noteHz(57)));
    }
    std::sort(c.begin(), c.end());
    EXPECT_NEAR(c[0], -10.0, 0.01);
    EXPECT_NEAR(c[1], -10.0 / 3.0, 0.01);
    EXPECT_NEAR(c[2], 10.0 / 3.0, 0.01);
    EXPECT_NEAR(c[3], 10.0, 0.01);
    // A small stereo spread, otherwise the same sound on both sides.
    auto b = renderStereo(synth, 96000);
    EXPECT_NE(b.left, b.right);
    EXPECT_NEAR(rms(b.left) / rms(b.right), 1.0, 0.25);
}

TEST_F(SynthEngineTest, UnisonIsLegatoAndGlides) {
    synth.setParam(ParamId::OscGlide, 0.1f);
    synth.setParam(ParamId::OscVoiceMode, 2.0f);
    synth.prepare(kRate);
    synth.noteOn(48, 1.0f, 0);
    render(4800);
    synth.noteOn(60, 1.0f, 0);
    render(2400);  // halfway through the glide
    for (int i = 0; i < 4; ++i) {
        const double semis = 48.0 + cents(synth.voiceInfo(i).frequencyHz, noteHz(48)) / 100.0;
        EXPECT_NEAR(semis, 54.0, 0.4) << i;
    }
    synth.noteOff(60);
    render(4800 + 32);
    for (int i = 0; i < 4; ++i) EXPECT_NEAR(cents(synth.voiceInfo(i).frequencyHz, noteHz(48)), 0.0, 10.1);
}

TEST_F(SynthEngineTest, ModeChangeFadesHeldVoicesWithoutClick) {
    synth.noteOn(60, 1.0f, 0);
    synth.noteOn(64, 1.0f, 0);
    auto x = render(4800);
    const float steady = maxStep(x, 2400);
    synth.setParam(ParamId::OscVoiceMode, 1.0f);
    auto y = render(4800);
    EXPECT_LE(maxStep(y, 0, 480), 1.1f * steady);
    EXPECT_EQ(activeVoices(synth), 0);
    synth.noteOn(67, 1.0f, 0);
    render(kBlock);
    EXPECT_EQ(activeVoices(synth), 1);
}

// --- F1.3 pitch ---

TEST_F(SynthEngineTest, PitchWithinOneCentFromC0ToC8AtEveryFineSetting) {
    double worst = 0.0;
    for (int note = 12; note <= 108; note += 12) {
        for (float fine : {-50.0f, -25.0f, 0.0f, 13.0f, 50.0f}) {
            synth.allNotesOff();
            synth.setParam(ParamId::OscFine, fine);
            synth.prepare(kRate);
            synth.noteOn(note, 1.0f, 0);
            const double expected = noteHz(note + fine / 100.0);
            const int frames = static_cast<int>(std::max(0.5, 40.0 / expected) * kRate);
            auto x = render(4800 + frames, 480);
            const double c = cents(zeroCrossingHz(x, kRate, 4800), expected);
            worst = std::max(worst, std::fabs(c));
            EXPECT_NEAR(c, 0.0, 1.0) << "note " << note << " fine " << fine;
        }
    }
    std::printf("worst pitch error C0..C8: %.4f cents\n", worst);
}

TEST_F(SynthEngineTest, PitchWithinOneCentAtEveryOctaveSetting) {
    for (int octave = -2; octave <= 2; ++octave) {
        for (float fine : {-50.0f, 0.0f, 50.0f}) {
            for (int note : {36, 60, 84}) {
                synth.allNotesOff();
                synth.setParam(ParamId::OscOctave, static_cast<float>(octave));
                synth.setParam(ParamId::OscFine, fine);
                synth.prepare(kRate);
                synth.noteOn(note, 1.0f, 0);
                const double expected = noteHz(note + 12 * octave + fine / 100.0);
                const int frames = static_cast<int>(std::max(0.5, 40.0 / expected) * kRate);
                auto x = render(4800 + frames, 480);
                EXPECT_NEAR(cents(zeroCrossingHz(x, kRate, 4800), expected), 0.0, 1.0)
                    << "note " << note << " octave " << octave << " fine " << fine;
            }
        }
    }
}

// --- modulation ---

TEST_F(SynthEngineTest, KeyTrackingKeepsBrightnessAcrossTheKeyboard) {
    auto brightness = [&](int note, float keyTrack) {
        synth.allNotesOff();
        synth.setParam(ParamId::OscWave, 0.0f);
        synth.setParam(ParamId::FltCutoff, 800.0f);
        synth.setParam(ParamId::FltKeyTrack, keyTrack);
        synth.prepare(kRate);
        synth.noteOn(note, 1.0f, 0);
        auto x = render(24000);
        const double f = noteHz(note);
        return toDb(goertzelAmplitude(x, kRate, 3 * f, 4800, 16384) / goertzelAmplitude(x, kRate, f, 4800, 16384));
    };
    EXPECT_NEAR(brightness(72, 1.0f), brightness(48, 1.0f), 3.0);
    EXPECT_LT(brightness(72, 0.0f), brightness(48, 0.0f) - 12.0);
}

TEST_F(SynthEngineTest, VelocityOpensTheFilter) {
    auto brightness = [&](float velocity) {
        synth.allNotesOff();
        synth.setParam(ParamId::OscWave, 0.0f);
        synth.setParam(ParamId::FltCutoff, 800.0f);
        synth.setParam(ParamId::FltVelocity, 1.0f);
        synth.prepare(kRate);
        synth.noteOn(48, velocity, 0);
        auto x = render(24000);
        const double f = noteHz(48);
        return toDb(goertzelAmplitude(x, kRate, 5 * f, 4800, 16384) / goertzelAmplitude(x, kRate, f, 4800, 16384));
    };
    EXPECT_GT(brightness(1.0f), brightness(0.5f) + 10.0);
}

TEST_F(SynthEngineTest, FilterEnvelopeAmountIsBipolar) {
    auto onsetBrightness = [&](float amount) {
        synth.allNotesOff();
        synth.setParam(ParamId::OscWave, 0.0f);
        synth.setParam(ParamId::FltCutoff, 1000.0f);
        synth.setParam(ParamId::FltEnvAmount, amount);
        synth.setParam(ParamId::FenvAttack, 0.001f);
        synth.setParam(ParamId::FenvDecay, 10.0f);
        synth.setParam(ParamId::FenvSustain, 1.0f);
        synth.prepare(kRate);
        synth.noteOn(48, 1.0f, 0);
        auto x = render(14400);
        const double f = noteHz(48);
        return toDb(goertzelAmplitude(x, kRate, 15 * f, 4800, 8192) / goertzelAmplitude(x, kRate, f, 4800, 8192));
    };
    const double none = onsetBrightness(0.0f);
    EXPECT_GT(onsetBrightness(0.5f), none + 15.0);
    EXPECT_LT(onsetBrightness(-0.5f), none - 15.0);
}

TEST_F(SynthEngineTest, LfoModulatesPitchWithDepthDelayAndRetrigger) {
    synth.setParam(ParamId::LfoDest, 0.0f);  // pitch
    synth.setParam(ParamId::LfoWave, 2.0f);  // square: easy to read
    synth.setParam(ParamId::LfoRate, 1.0f);
    synth.setParam(ParamId::LfoDepth, 1.0f);  // +-1 octave
    synth.prepare(kRate);
    synth.noteOn(60, 1.0f, 0);
    render(4800);  // 0.1 s: first half of the square, up an octave
    EXPECT_NEAR(cents(synth.voiceInfo(0).frequencyHz, noteHz(60)), 1200.0, 1.0);
    render(24000);  // 0.6 s: second half, down an octave
    EXPECT_NEAR(cents(synth.voiceInfo(0).frequencyHz, noteHz(60)), -1200.0, 1.0);

    // Retrigger: a new note starts its own cycle from the top.
    synth.noteOn(64, 1.0f, 0);
    render(kBlock);
    EXPECT_NEAR(cents(synth.voiceInfo(voiceWithNote(synth, 64)).frequencyHz, noteHz(64)), 1200.0, 1.0);

    // Delay: the modulation fades in.
    synth.allNotesOff();
    synth.setParam(ParamId::LfoDelay, 1.0f);
    synth.prepare(kRate);
    synth.noteOn(60, 1.0f, 0);
    render(4800);
    const double early = cents(synth.voiceInfo(0).frequencyHz, noteHz(60));
    EXPECT_GT(early, 50.0);
    EXPECT_LT(early, 200.0);  // ~10% into a 1 s fade
}

TEST_F(SynthEngineTest, FreeRunningLfoIsSharedWhenRetriggerIsOff) {
    synth.setParam(ParamId::LfoDest, 0.0f);
    synth.setParam(ParamId::LfoRetrigger, 0.0f);
    synth.setParam(ParamId::LfoRate, 3.0f);
    synth.setParam(ParamId::LfoDepth, 0.5f);
    synth.prepare(kRate);
    synth.noteOn(60, 1.0f, 0);
    render(7000);
    synth.noteOn(72, 1.0f, 0);
    render(kBlock);
    const double a = cents(synth.voiceInfo(voiceWithNote(synth, 60)).frequencyHz, noteHz(60));
    const double b = cents(synth.voiceInfo(voiceWithNote(synth, 72)).frequencyHz, noteHz(72));
    EXPECT_NEAR(a, b, 0.5);
}

TEST_F(SynthEngineTest, LfoToAmpIsTremolo) {
    synth.setParam(ParamId::LfoDest, 3.0f);
    synth.setParam(ParamId::LfoWave, 2.0f);
    synth.setParam(ParamId::LfoRate, 2.0f);
    synth.setParam(ParamId::LfoDepth, 0.5f);
    synth.prepare(kRate);
    synth.noteOn(69, 1.0f, 0);
    auto x = render(48000);
    // First half-cycle at full level, second at 1 - depth.
    EXPECT_NEAR(rms(x, 36000, 47000) / rms(x, 2000, 11000), 0.5, 0.02);
}

TEST_F(SynthEngineTest, TempoSyncSnapsTheRateToADivision) {
    synth.setParam(ParamId::LfoDest, 0.0f);
    synth.setParam(ParamId::LfoWave, 2.0f);
    synth.setParam(ParamId::LfoDepth, 1.0f);
    synth.setParam(ParamId::LfoRate, 2.3f);  // nearest division at 120 BPM: 1/4 = 2 Hz
    synth.setParam(ParamId::LfoSync, 1.0f);
    synth.prepare(kRate);
    synth.noteOn(60, 1.0f, 0);
    render(11000);  // 0.229 s: still in the first half of a 2 Hz square (ends at 0.25 s)
    EXPECT_GT(cents(synth.voiceInfo(0).frequencyHz, noteHz(60)), 1000.0);
    render(2000);  // 0.271 s: second half (a free 2.3 Hz LFO would flip at 0.217 s)
    EXPECT_LT(cents(synth.voiceInfo(0).frequencyHz, noteHz(60)), -1000.0);
}

// --- F0.7 / F1.7 parameter smoothing ---

// Every continuous parameter starts moving within one buffer, never jumps, and is
// within 1% of a full-range change after 10 ms.
TEST_F(SynthEngineTest, EveryParameterIsSmoothedWithin10ms) {
    for (std::size_t i = 0; i < kParamCount; ++i) {
        const auto id = static_cast<ParamId>(i);
        const ParamInfo& p = kParamInfo[i];
        for (float target : {p.max, p.min}) {
            synth.setParam(id, target == p.max ? p.min : p.max);
            synth.prepare(kRate);
            const float from = synth.smoothedParam(id);
            synth.setParam(id, target);
            render(kBlock);  // one buffer
            const float afterOne = synth.smoothedParam(id);
            render(480 - kBlock);  // 10 ms in total
            const float after10 = synth.smoothedParam(id);
            if (p.discrete()) {
                EXPECT_EQ(afterOne, target) << p.key;
                continue;
            }
            auto progress = [&](float v) {
                if (p.curve == Curve::Exp) return (std::log2(v) - std::log2(from)) / (std::log2(target) - std::log2(from));
                return (v - from) / (target - from);
            };
            EXPECT_GT(progress(afterOne), 0.05f) << p.key << " responds within one buffer";
            EXPECT_LT(progress(afterOne), 0.95f) << p.key << " must not jump";
            EXPECT_GT(progress(after10), 0.99f) << p.key << " settles within 10 ms";
        }
        synth.setParam(id, p.def);
    }
}

// End to end: jumping any parameter while a note sounds never makes a sample-to-sample
// step bigger than the steady sound on either side of the change.
TEST_F(SynthEngineTest, ParameterJumpsDoNotClick) {
    for (std::size_t i = 0; i < kParamCount; ++i) {
        const auto id = static_cast<ParamId>(i);
        const ParamInfo& p = kParamInfo[i];
        if (p.discrete()) continue;  // voice mode, octave, LFO options: covered elsewhere
        // LFO-driven: the sound keeps changing after the jump, so "steady after" is undefined.
        if (id == ParamId::LfoDepth || id == ParamId::LfoRate || id == ParamId::LfoDelay ||
            id == ParamId::FltLfoAmount) {
            continue;
        }
        for (float target : {p.min, p.max}) {
            synth.allNotesOff();
            setCleanSine(synth);
            synth.setParam(ParamId::FltCutoff, 3000.0f);
            synth.prepare(kRate);
            synth.noteOn(57, 1.0f, 0);
            auto x = render(9600);
            synth.setParam(id, target);
            append(x, render(9600));
            const float before = maxStep(x, 4800, 9600);
            const float during = maxStep(x, 9600, 9600 + 960);
            const float after = maxStep(x, 9600 + 4800, x.size());
            EXPECT_LE(during, 1.25f * std::max(before, after) + 0.002f)
                << p.key << " -> " << target << " (before " << before << ", after " << after << ")";
        }
        synth.setParam(id, p.def);
    }
}

// --- threading ---

TEST_F(SynthEngineTest, ControlAndAudioThreadsRunConcurrently) {
    std::atomic<bool> done{false};
    std::thread audio([&] {
        std::vector<float> l(kBlock), r(kBlock);
        while (!done.load()) synth.renderStereo(l.data(), r.data(), kBlock);
    });
    int records = 0;
    for (int i = 0; i < 2000; ++i) {
        while (!synth.noteOn(60 + i % 12, 0.5f + 0.5f * static_cast<float>(i % 2), i + 1)) std::this_thread::yield();
        synth.setParam(ParamId::FltCutoff, 100.0f + static_cast<float>(i));
        if (i % 100 == 0) synth.loadPreset((i / 100) % 16);
        while (!synth.noteOff(60 + i % 12)) std::this_thread::yield();
        NoteOnRender rec;
        while (synth.popNoteOnRender(rec)) ++records;
    }
    done.store(true);
    audio.join();
    NoteOnRender rec;
    while (synth.popNoteOnRender(rec)) ++records;
    EXPECT_GT(records, 0);
}

TEST_F(SynthEngineTest, RepeatedNoteDuringAStealUsesOneVoice) {
    for (int n : {48, 52, 55, 59}) {
        synth.noteOn(n, 1.0f, 0);
        render(kBlock);
    }
    synth.noteOn(72, 1.0f, 0);
    synth.noteOff(72);
    synth.noteOn(72, 1.0f, 0);  // pressed again while its voice is still fading in
    render(4 * kBlock);
    int count = 0;
    for (int i = 0; i < SynthEngine::kVoiceCount; ++i) count += synth.voiceInfo(i).note == 72 ? 1 : 0;
    EXPECT_EQ(count, 1);
    EXPECT_GE(voiceWithNote(synth, 52), 0);  // only one voice was stolen
}

TEST_F(SynthEngineTest, HeldZeroSustainSettlesToExactSilence) {
    synth.setParam(ParamId::AmpDecay, 0.01f);
    synth.setParam(ParamId::AmpSustain, 0.0f);
    synth.prepare(kRate);
    synth.noteOn(60, 1.0f, 0);
    render(4800);
    synth.setParam(ParamId::AmpSustain, 0.5f);
    render(4800);
    synth.setParam(ParamId::AmpSustain, 0.0f);
    auto x = render(48000);
    EXPECT_EQ(synth.voiceInfo(0).ampLevel, 0.0f);
    EXPECT_EQ(peakAbs(x, 24000), 0.0f);
}
