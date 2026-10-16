// F2.2 / F2.3 (and F0.5, F0.7 for the drums): the 8-voice drum sampler. Per-key Pitch,
// Decay, Tone, Level, Pan, Start, Reverse and Choke; retrigger, choke and voice stealing
// without clicks; the UI note path, latency records, motion overrides, real-time safety.

#include <gtest/gtest.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <functional>
#include <limits>
#include <thread>
#include <vector>

#include "AllocationCounter.h"
#include "Analysis.h"
#include "groove/DrumKit.h"
#include "groove/DrumMachine.h"

using namespace groove;
using groove::test::maxStep;
using groove::test::peakAbs;

namespace {

constexpr float kFs = 48000.0f;
constexpr int kCutFrames = 240;  // 5 ms at 48 kHz
constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();
const float kKitPeak = std::pow(10.0f, DrumKit::kPeakDb / 20.0f);
const float kCentre = std::sqrt(0.5f);  // equal-power pan at the centre

int note(int key) { return DrumMachine::kLowestNote + key; }

// A machine in its just-constructed state, prepared at kFs. Machines are reused because
// synthesising the kit is the slow part of prepare() (very slow under TSan), and prepare()
// skips it when the rate is unchanged. Slots 0 and 1 are for tests, slot 2 for renderHit.
DrumMachine& fresh(int slot) {
    static DrumMachine machines[3];
    DrumMachine& m = machines[slot];
    for (int k = 0; k < DrumMachine::kSoundCount; ++k) {
        for (std::size_t i = 0; i < kDrumParamCount; ++i) {
            const auto id = static_cast<DrumParamId>(i);
            m.setParam(k, id, id == DrumParamId::Choke && k >= 6 && k <= 8 ? 1.0f : drumParamInfo(id).def);
        }
    }
    m.clearAllMotion();
    m.prepare(kFs);
    NoteOnRender rec;
    while (m.popNoteOnRender(rec)) {
    }
    return m;
}

struct Stereo {
    std::vector<float> l, r;
};

void renderInto(DrumMachine& m, Stereo& out, int frames, int block = 96) {
    const std::size_t at = out.l.size();
    out.l.resize(at + static_cast<std::size_t>(frames));
    out.r.resize(at + static_cast<std::size_t>(frames));
    for (int done = 0; done < frames; done += block) {
        const int n = std::min(block, frames - done);
        m.renderStereo(out.l.data() + at + static_cast<std::size_t>(done),
                       out.r.data() + at + static_cast<std::size_t>(done), n);
    }
}

Stereo render(DrumMachine& m, int frames, int block = 96) {
    Stereo s;
    renderInto(m, s, frames, block);
    return s;
}

// One hit of `key` from a freshly prepared machine, after `setup`.
Stereo renderHit(int key, int frames, const std::function<void(DrumMachine&)>& setup = {}, float velocity = 1.0f) {
    DrumMachine& m = fresh(2);
    if (setup) setup(m);
    m.seqNoteOn(note(key), velocity);
    return render(m, frames);
}

std::size_t soundingLength(const std::vector<float>& x, float threshold = 0.0f) {
    for (std::size_t i = x.size(); i > 0; --i) {
        if (std::fabs(x[i - 1]) > threshold) return i;
    }
    return 0;
}

int kitLength(int key) {
    static const DrumKit kit = [] {
        DrumKit k;
        k.render(kFs);
        return k;
    }();
    return kit.length(key);
}

double correlation(const std::vector<float>& a, std::size_t aFrom, const std::vector<float>& b, std::size_t bFrom,
                   std::size_t n) {
    double ab = 0.0, aa = 0.0, bb = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
        const double x = a[aFrom + i], y = b[bFrom + i];
        ab += x * y;
        aa += x * x;
        bb += y * y;
    }
    return ab / std::sqrt(aa * bb);
}

double energy(const std::vector<float>& x) {
    double e = 0.0;
    for (float v : x) e += static_cast<double>(v) * v;
    return e;
}

}  // namespace

// --- basics ---

TEST(DrumMachine, SilentWithoutNotesAndCountsFrames) {
    DrumMachine& m = fresh(0);
    EXPECT_EQ(m.framesRendered(), 0);
    std::vector<float> l(500, 1.0f), r(500, -1.0f);  // render writes, it doesn't add
    m.renderStereo(l.data(), r.data(), 500);
    EXPECT_EQ(peakAbs(l), 0.0f);
    EXPECT_EQ(peakAbs(r), 0.0f);
    m.renderStereo(l.data(), r.data(), 0);
    m.renderStereo(l.data(), r.data(), 37);
    EXPECT_EQ(m.framesRendered(), 537);
    m.prepare(kFs);
    EXPECT_EQ(m.framesRendered(), 0);
}

TEST(DrumMachine, StartsWithNeutralParamsAndHatsInChokeGroupA) {
    DrumMachine m;
    for (int k = 0; k < DrumMachine::kSoundCount; ++k) {
        EXPECT_EQ(m.param(k, DrumParamId::Pitch), 0.0f);
        EXPECT_EQ(m.param(k, DrumParamId::Decay), 1.0f);
        EXPECT_EQ(m.param(k, DrumParamId::Level), drumParamInfo(DrumParamId::Level).def);
        EXPECT_EQ(m.param(k, DrumParamId::Choke), (k >= 6 && k <= 8) ? 1.0f : 0.0f) << k;
    }
    m.setParam(3, DrumParamId::Pitch, 40.0f);  // clamped
    EXPECT_EQ(m.param(3, DrumParamId::Pitch), 12.0f);
    m.setParam(3, DrumParamId::Choke, 1.6f);  // rounded enum
    EXPECT_EQ(m.param(3, DrumParamId::Choke), 2.0f);
    m.setParam(3, DrumParamId::Level, kNaN);  // NaN -> default
    EXPECT_EQ(m.param(3, DrumParamId::Level), drumParamInfo(DrumParamId::Level).def);
}

TEST(DrumMachine, EveryKeyPlaysItsSoundOnceAndStops) {
    for (int k = 0; k < DrumMachine::kSoundCount; ++k) {
        SCOPED_TRACE(DrumMachine::soundName(k));
        const int len = kitLength(k);
        const Stereo s = renderHit(k, len + 4800);
        // Default level 0.8, centre pan: the kit's peak scaled by both.
        // Tone fully open is transparent and Pitch 0 reads the samples exactly.
        EXPECT_NEAR(peakAbs(s.l), kKitPeak * 0.8f * kCentre, 1e-5f);
        EXPECT_EQ(s.l, s.r);
        const std::size_t sounding = soundingLength(s.l);
        EXPECT_LE(sounding, static_cast<std::size_t>(len) + 1);
        EXPECT_GT(sounding, static_cast<std::size_t>(len) * 9 / 10);
    }
}

TEST(DrumMachine, UiNoteOnPlaysTheSameAsTheSequencer) {
    const Stereo seq = renderHit(2, 9600);
    DrumMachine& m = fresh(0);
    EXPECT_TRUE(m.noteOn(note(2), 1.0f, 0));
    const Stereo ui = render(m, 9600);
    EXPECT_EQ(ui.l, seq.l);
    EXPECT_EQ(ui.r, seq.r);
}

TEST(DrumMachine, IgnoresNotesKeysAndTargetsOutOfRange) {
    DrumMachine& m = fresh(0);
    EXPECT_FALSE(m.noteOn(note(-1), 1.0f, 5));
    EXPECT_FALSE(m.noteOn(note(16), 1.0f, 5));
    EXPECT_FALSE(m.noteOn(-3, 1.0f, 5));
    EXPECT_FALSE(m.noteOn(200, 1.0f, 5));
    for (int n : {-1, 0, 47, 64, 100, 127, 128}) m.seqNoteOn(n, 1.0f);
    m.setParam(-1, DrumParamId::Level, 0.1f);
    m.setParam(16, DrumParamId::Level, 0.1f);
    EXPECT_EQ(m.param(-1, DrumParamId::Level), 0.0f);
    EXPECT_EQ(m.param(16, DrumParamId::Level), 0.0f);
    m.setMotion(-1, 1.0f);
    m.setMotion(DrumMachine::kSoundCount * static_cast<int>(kDrumParamCount), 1.0f);
    m.setMotion(1 << 30, 1.0f);
    const Stereo s = render(m, 4800);
    EXPECT_EQ(peakAbs(s.l), 0.0f);
    EXPECT_EQ(peakAbs(s.r), 0.0f);
    NoteOnRender rec;
    EXPECT_FALSE(m.popNoteOnRender(rec));
}

TEST(DrumMachine, NoteOffIsIgnoredForOneShots) {
    const Stereo ref = renderHit(9, 24000);
    DrumMachine& m = fresh(0);
    m.seqNoteOn(note(9), 1.0f);
    Stereo s = render(m, 4800);
    m.seqNoteOff(note(9));
    EXPECT_TRUE(m.noteOff(note(9)));
    renderInto(m, s, 19200);
    EXPECT_EQ(s.l, ref.l);
}

// --- per-key parameters (F2.2) ---

TEST(DrumMachine, VelocityAndLevelScaleTheHit) {
    const float full = peakAbs(renderHit(0, 9600).l);
    EXPECT_NEAR(peakAbs(renderHit(0, 9600, {}, 0.5f).l) / full, 0.5f, 1e-3f);
    EXPECT_NEAR(peakAbs(renderHit(0, 9600, {}, kNaN).l) / full, 1.0f, 1e-6f);
    EXPECT_EQ(peakAbs(renderHit(0, 9600, {}, 0.0f).l), 0.0f);
    const auto quiet = renderHit(0, 9600, [](DrumMachine& m) { m.setParam(0, DrumParamId::Level, 0.4f); });
    EXPECT_NEAR(peakAbs(quiet.l) / full, 0.5f, 1e-3f);
    const auto mute = renderHit(0, 9600, [](DrumMachine& m) { m.setParam(0, DrumParamId::Level, 0.0f); });
    EXPECT_EQ(peakAbs(mute.l), 0.0f);
}

TEST(DrumMachine, PanIsEqualPower) {
    auto hitPanned = [](float pan) {
        return renderHit(12, 24000, [pan](DrumMachine& m) { m.setParam(12, DrumParamId::Pan, pan); });
    };
    const Stereo left = hitPanned(-1.0f), centre = hitPanned(0.0f), right = hitPanned(1.0f),
                 part = hitPanned(0.5f);
    EXPECT_LT(peakAbs(left.r), 1e-6f * peakAbs(left.l));
    EXPECT_LT(peakAbs(right.l), 1e-6f * peakAbs(right.r));
    EXPECT_NEAR(peakAbs(left.l), peakAbs(right.r), 1e-6f);
    EXPECT_EQ(centre.l, centre.r);
    EXPECT_NEAR(peakAbs(centre.l) / peakAbs(left.l), kCentre, 1e-4f);
    const double total = energy(left.l);
    for (const Stereo* s : {&centre, &part, &right}) {
        EXPECT_NEAR((energy(s->l) + energy(s->r)) / total, 1.0, 1e-4);
    }
    EXPECT_GT(energy(part.r), 3.0 * energy(part.l));  // half right is mostly right
}

TEST(DrumMachine, PitchUpAnOctaveHalvesTheLengthAndDoublesTheFrequency) {
    const int key = 12;  // Mid Tom
    const int len = kitLength(key);
    auto hitAt = [&](float st) {
        return renderHit(key, len * 2 + 4800, [&](DrumMachine& m) { m.setParam(key, DrumParamId::Pitch, st); }).l;
    };
    const auto base = hitAt(0.0f), up = hitAt(12.0f), down = hitAt(-12.0f);
    EXPECT_NEAR(static_cast<double>(soundingLength(up)), len / 2.0, 2.0);
    EXPECT_NEAR(static_cast<double>(soundingLength(down)), len * 2.0, 3.0);
    const double f0 = groove::test::zeroCrossingHz(base, kFs, 4800, 4800 + 19200);
    const double f12 = groove::test::zeroCrossingHz(up, kFs, 2400, 2400 + 9600);
    const double fm12 = groove::test::zeroCrossingHz(down, kFs, 9600, 9600 + 38400);
    ASSERT_GT(f0, 50.0);
    EXPECT_NEAR(f12 / f0, 2.0, 0.006);
    EXPECT_NEAR(fm12 / f0, 0.5, 0.0015);
    // Good interpolation: the octave-up render is the original time-compressed.
    std::vector<float> decimated;
    for (std::size_t i = 0; i < base.size(); i += 2) decimated.push_back(base[i]);
    EXPECT_GT(correlation(up, 0, decimated, 0, static_cast<std::size_t>(len / 2 - 10)), 0.9999);
}

TEST(DrumMachine, DecayShortensTheHitWithoutAClick) {
    const int key = 9;  // Crash: long and loud right to the cut
    const int len = kitLength(key);
    const auto full = renderHit(key, len + 4800).l;
    for (float decay : {0.6f, 0.25f, 0.05f}) {
        SCOPED_TRACE(decay);
        const auto cut = renderHit(key, len + 4800, [&](DrumMachine& m) { m.setParam(key, DrumParamId::Decay, decay); }).l;
        const double expected = decay * static_cast<double>(len);
        EXPECT_NEAR(static_cast<double>(soundingLength(cut)), expected, 2.0);
        // The envelope reaches zero smoothly: quiet in the last 2 ms, no step bigger than
        // the sound's own.
        const auto end = static_cast<std::size_t>(expected);
        EXPECT_LT(peakAbs(cut, end - 96, end + 10), 0.002f);
        EXPECT_LE(maxStep(cut), maxStep(full, 1, end + 1) + 1e-3f);
        // The attack is untouched.
        EXPECT_NEAR(peakAbs(cut, 0, 2400), peakAbs(full, 0, 2400), 0.02f);
    }
    // Decay 0 still makes a short (5 ms) tick, not silence or a click.
    const auto tick = renderHit(key, 4800, [&](DrumMachine& m) { m.setParam(key, DrumParamId::Decay, 0.0f); }).l;
    EXPECT_GT(peakAbs(tick), 0.05f);
    EXPECT_LE(soundingLength(tick), static_cast<std::size_t>(kCutFrames) + 1);
}

TEST(DrumMachine, ReversePlaysTheSoundBackwards) {
    const int key = 0;  // kick: the attack ends up at the very end
    const int len = kitLength(key);
    const auto fwd = renderHit(key, len + 4800).l;
    const auto rev = renderHit(key, len + 4800, [&](DrumMachine& m) { m.setParam(key, DrumParamId::Reverse, 1.0f); }).l;
    const std::size_t revLen = soundingLength(rev);
    EXPECT_NEAR(static_cast<double>(revLen), static_cast<double>(len), 2.0);
    std::vector<float> flipped(fwd.begin(), fwd.begin() + len);
    std::reverse(flipped.begin(), flipped.end());
    // rev[n] plays kit[len - n]; flipped[n] is kit[len - 1 - n].
    EXPECT_GT(correlation(rev, 1, flipped, 0, static_cast<std::size_t>(len - 1)), 0.999);
    const auto peakAt = static_cast<std::size_t>(
        std::max_element(rev.begin(), rev.end(), [](float a, float b) { return std::fabs(a) < std::fabs(b); }) -
        rev.begin());
    EXPECT_GT(peakAt, static_cast<std::size_t>(len) * 9 / 10);
    EXPECT_LE(maxStep(rev), maxStep(fwd) + 1e-3f);

    // With Decay, reverse plays the shortened region backwards: a short swell into the hit.
    const auto revShort = renderHit(key, len + 4800, [&](DrumMachine& m) {
        m.setParam(key, DrumParamId::Reverse, 1.0f);
        m.setParam(key, DrumParamId::Decay, 0.3f);
    }).l;
    EXPECT_NEAR(static_cast<double>(soundingLength(revShort)), 0.3 * len, 2.0);
    EXPECT_LT(std::fabs(revShort[0]), 1e-4f);
    EXPECT_LE(maxStep(revShort), maxStep(fwd) + 1e-3f);
}

TEST(DrumMachine, StartOffsetSkipsIntoTheSoundWithAShortFadeIn) {
    const int key = 11;
    const int len = kitLength(key);
    const auto fwd = renderHit(key, len + 4800).l;
    const auto off = renderHit(key, len + 4800, [&](DrumMachine& m) { m.setParam(key, DrumParamId::Start, 0.25f); }).l;
    const auto skip = static_cast<std::size_t>(0.25 * len);
    EXPECT_NEAR(static_cast<double>(soundingLength(off)), static_cast<double>(len) - static_cast<double>(skip), 2.0);
    EXPECT_GT(correlation(off, 48, fwd, skip + 48, static_cast<std::size_t>(len) - skip - 100), 0.9999);
    EXPECT_LT(std::fabs(off[0]), 1e-3f);
    // The 1 ms fade-in adds at most the ramp's own slope to the sound's steps.
    EXPECT_LE(maxStep(off, 1, 96), maxStep(fwd, skip, skip + 96) + 1.1f * peakAbs(fwd, skip, skip + 96) / 48.0f);
}

TEST(DrumMachine, ToneLowPassDarkensTheSound) {
    const int key = 9;
    const auto open = renderHit(key, 24000).l;
    const auto dark = renderHit(key, 24000, [&](DrumMachine& m) { m.setParam(key, DrumParamId::Tone, 400.0f); }).l;
    const auto magOpen = groove::test::magnitudeSpectrum(open, 2400, 16384);
    const auto magDark = groove::test::magnitudeSpectrum(dark, 2400, 16384);
    const double binHz = kFs / 16384.0;
    double hiOpen = 0.0, hiDark = 0.0;
    for (std::size_t b = static_cast<std::size_t>(4000.0 / binHz); b < magOpen.size(); ++b) {
        hiOpen += magOpen[b] * magOpen[b];
        hiDark += magDark[b] * magDark[b];
    }
    EXPECT_LT(groove::test::toDb(std::sqrt(hiDark / hiOpen)), -40.0);
    // A kick, mostly below the cutoff, barely changes at 2 kHz.
    const auto kick = renderHit(0, 9600).l;
    const auto kickTone =
        renderHit(0, 9600, [](DrumMachine& m) { m.setParam(0, DrumParamId::Tone, 2000.0f); }).l;
    EXPECT_GT(groove::test::rms(kickTone) / groove::test::rms(kick), 0.97);
}

TEST(DrumMachine, LiveParameterChangesAreSmoothed) {
    // F0.7: jumping Level, Pan, Tone and Pitch on a ringing ride makes no click, and
    // the new values settle within 10 ms.
    const int key = 10;
    DrumMachine& a = fresh(0);
    DrumMachine& b = fresh(1);
    a.seqNoteOn(note(key), 1.0f);
    b.seqNoteOn(note(key), 1.0f);
    Stereo sa = render(a, 9600), sb = render(b, 9600);
    a.setParam(key, DrumParamId::Level, 0.1f);
    a.setParam(key, DrumParamId::Pan, 1.0f);
    a.setParam(key, DrumParamId::Tone, 1000.0f);
    a.setParam(key, DrumParamId::Pitch, -7.0f);
    renderInto(a, sa, 4800);
    renderInto(b, sb, 4800);
    EXPECT_LE(maxStep(sa.l, 9500, 10000), maxStep(sb.l, 9500, 10000) * 1.05f + 1e-4f);
    EXPECT_LE(maxStep(sa.r, 9500, 10000), maxStep(sb.r, 9500, 10000) * 1.05f + 1e-4f);
    // Hard right after 10 ms: the left channel is (almost) gone.
    EXPECT_LT(peakAbs(sa.l, 9600 + 480, 14400), 0.02f * peakAbs(sb.l, 9600 + 480, 14400));
}

// --- voices, retrigger, choke, steal (F2.3) ---

TEST(DrumMachine, RetriggerRestartsTheKey) {
    const int key = 10;
    const Stereo ref = renderHit(key, 48000);
    DrumMachine& m = fresh(0);
    m.seqNoteOn(note(key), 1.0f);
    Stereo s = render(m, 4800);
    m.seqNoteOn(note(key), 1.0f);
    renderInto(m, s, 43200);
    // The old hit is gone 5 ms after the new one; from then on it's a fresh hit.
    for (std::size_t n = kCutFrames + 1; n < 43200; ++n) {
        ASSERT_NEAR(s.l[4800 + n], ref.l[n], 1e-5f) << n;
    }
    // During those 5 ms the old hit fades out smoothly.
    std::vector<float> tail(kCutFrames + 10);
    for (std::size_t n = 0; n < tail.size(); ++n) tail[n] = s.l[4800 + n] - ref.l[n];
    EXPECT_LE(maxStep(tail), maxStep(ref.l, 4800, 4800 + tail.size()) + 1e-4f);
    EXPECT_LT(peakAbs(tail, kCutFrames + 1), 1e-5f);
}

TEST(DrumMachine, ClosedHatChokesTheOpenHatWithin5ms) {
    const int open = 8;
    const int choke = 9600;
    const Stereo openRef = renderHit(open, choke + 9600);
    for (int closed : {6, 7}) {
        SCOPED_TRACE(closed);
        const Stereo closedRef = renderHit(closed, 9600);
        DrumMachine& m = fresh(0);
        m.seqNoteOn(note(open), 1.0f);
        Stereo s = render(m, choke);
        m.seqNoteOn(note(closed), 1.0f);
        renderInto(m, s, 9600);
        std::vector<float> fade(9600);
        for (std::size_t n = 0; n < fade.size(); ++n) fade[n] = s.l[choke + n] - closedRef.l[n];
        for (std::size_t n = 0; n < kCutFrames; ++n) {
            const float envelope = 1.0f - static_cast<float>(n) / kCutFrames;
            ASSERT_LE(std::fabs(fade[n]), std::fabs(openRef.l[choke + n]) * envelope + 1e-5f) << n;
        }
        EXPECT_LT(peakAbs(fade, kCutFrames + 1), 1e-5f) << "open hat still sounding after 5 ms";
        EXPECT_GT(peakAbs(openRef.l, choke + kCutFrames, choke + 9600), 0.05f) << "the open hat would still ring";
        EXPECT_LE(maxStep(fade), maxStep(openRef.l, choke, choke + kCutFrames + 1) + 1e-4f) << "click";
    }
}

TEST(DrumMachine, ChokeGroupsAreConfigurable) {
    // No group: the closed hat no longer chokes the open hat.
    const Stereo openRef = renderHit(8, 19200);
    const Stereo closedRef = renderHit(6, 9600);
    {
        DrumMachine& m = fresh(0);
        m.setParam(8, DrumParamId::Choke, 0.0f);
        m.seqNoteOn(note(8), 1.0f);
        Stereo s = render(m, 9600);
        m.seqNoteOn(note(6), 1.0f);
        renderInto(m, s, 9600);
        for (std::size_t n = 0; n < 9600; ++n) ASSERT_NEAR(s.l[9600 + n], openRef.l[9600 + n] + closedRef.l[n], 1e-5f);
    }
    // Group B: two kicks choke each other; the hats in group A are unaffected.
    {
        DrumMachine& m = fresh(0);
        m.setParam(0, DrumParamId::Choke, 2.0f);
        m.setParam(1, DrumParamId::Choke, 2.0f);
        m.seqNoteOn(note(0), 1.0f);
        m.seqNoteOn(note(8), 1.0f);
        Stereo s = render(m, 4800);
        m.seqNoteOn(note(1), 1.0f);
        renderInto(m, s, 9600);
        const Stereo kick2 = renderHit(1, 9600);
        for (std::size_t n = kCutFrames + 1; n < 9600; ++n) {
            ASSERT_NEAR(s.l[4800 + n], kick2.l[n] + openRef.l[4800 + n], 1e-5f) << n;
        }
    }
}

TEST(DrumMachine, EightVoicesSoundTogether) {
    const int keys[8] = {0, 2, 4, 9, 10, 11, 12, 13};
    DrumMachine& m = fresh(0);
    for (int k : keys) m.seqNoteOn(note(k), 1.0f);
    const Stereo all = render(m, 24000);
    std::vector<float> sum(24000, 0.0f);
    for (int k : keys) {
        const Stereo one = renderHit(k, 24000);
        for (std::size_t i = 0; i < sum.size(); ++i) sum[i] += one.l[i];
    }
    for (std::size_t i = 0; i < sum.size(); ++i) ASSERT_NEAR(all.l[i], sum[i], 1e-5f) << i;
}

TEST(DrumMachine, NinthHitStealsTheOldestVoiceWithoutAClick) {
    // Eight long sounds one block apart, then a ninth: the first (the crash) is stolen.
    const int keys[8] = {9, 10, 11, 12, 13, 0, 2, 4};
    const int ninth = 8;
    auto play = [&](bool withFirst) {
        DrumMachine& m = fresh(0);
        Stereo s;
        for (int i = 0; i < 8; ++i) {
            if (i > 0 || withFirst) m.seqNoteOn(note(keys[i]), 1.0f);
            renderInto(m, s, 96);
        }
        m.seqNoteOn(note(ninth), 1.0f);
        renderInto(m, s, 24000);
        return s.l;
    };
    const auto a = play(true), b = play(false);
    const Stereo crash = renderHit(9, static_cast<int>(a.size()));
    const std::size_t at = 8 * 96;
    std::vector<float> stolen(a.size());
    for (std::size_t i = 0; i < a.size(); ++i) stolen[i] = a[i] - b[i];
    for (std::size_t i = 0; i < at; ++i) ASSERT_NEAR(stolen[i], crash.l[i], 1e-5f);
    EXPECT_LT(peakAbs(stolen, at + kCutFrames + 1), 1e-5f) << "stolen voice still sounding";
    EXPECT_LE(maxStep(stolen), maxStep(crash.l, 0, at + kCutFrames + 1) + 1e-4f) << "click";
    // The ninth hit itself starts at once (no wait for the fade).
    const Stereo hat = renderHit(ninth, 2400);
    EXPECT_GT(peakAbs(b, at, at + 240), 0.5f * peakAbs(hat.l, 0, 240));
}

TEST(DrumMachine, AllNotesOffFadesEverythingQuickly) {
    for (bool ui : {false, true}) {
        SCOPED_TRACE(ui ? "ui" : "seq");
        DrumMachine& m = fresh(0);
        for (int k : {9, 10, 8, 0}) m.seqNoteOn(note(k), 1.0f);
        Stereo s = render(m, 4800);
        if (ui) {
            EXPECT_TRUE(m.allNotesOff());
        } else {
            m.seqAllNotesOff();
        }
        renderInto(m, s, 4800);
        EXPECT_LT(peakAbs(s.l, 4800 + kCutFrames + 1), 1e-6f);
        EXPECT_LE(maxStep(s.l, 4790, 4800 + kCutFrames + 2), maxStep(s.l, 4000, 4800) * 1.05f + 1e-4f);
        // And it plays again afterwards.
        m.seqNoteOn(note(0), 1.0f);
        EXPECT_GT(peakAbs(render(m, 4800).l), 0.3f);
    }
}

// --- UI path, latency, motion, block sizes ---

TEST(DrumMachine, LatencyRecordsMarkTheBlockWhereTheHitStarts) {
    DrumMachine& m = fresh(0);
    render(m, 100, 100);
    EXPECT_TRUE(m.noteOn(note(0), 1.0f, 12345));
    EXPECT_TRUE(m.noteOn(note(1), 1.0f, 0));  // not measured: no record
    NoteOnRender rec;
    EXPECT_FALSE(m.popNoteOnRender(rec)) << "nothing until the audio thread has played it";
    const Stereo s = render(m, 64, 64);
    EXPECT_GT(peakAbs(s.l), 0.0f) << "the hit starts in this very block";
    ASSERT_TRUE(m.popNoteOnRender(rec));
    EXPECT_EQ(rec.tapTimeNs, 12345);
    EXPECT_EQ(rec.frameIndex, 100);
    EXPECT_FALSE(m.popNoteOnRender(rec));
    // A note that steals still starts at once, so its record is the block start too.
    for (int k = 0; k < 9; ++k) m.noteOn(note(9 + (k % 5)), 1.0f, 1000 + k);
    render(m, 32, 32);
    int count = 0;
    while (m.popNoteOnRender(rec)) {
        EXPECT_EQ(rec.frameIndex, 164);
        ++count;
    }
    EXPECT_EQ(count, 9);
}

TEST(DrumMachine, MotionOverridesAKeysParamUntilCleared) {
    const int key = 12;
    const auto ref = renderHit(key, 9600).l;
    DrumMachine& m = fresh(0);
    auto hit = [&] {
        m.seqNoteOn(note(key), 1.0f);
        const auto out = render(m, 9600).l;
        m.seqAllNotesOff();
        render(m, 480);
        return out;
    };
    m.setMotion(DrumMachine::motionTarget(key, DrumParamId::Level), 0.0f);
    EXPECT_EQ(peakAbs(hit()), 0.0f);
    EXPECT_EQ(m.param(key, DrumParamId::Level), drumParamInfo(DrumParamId::Level).def) << "motion isn't the param";
    // Other keys are unaffected.
    m.seqNoteOn(note(key + 1), 1.0f);
    EXPECT_GT(peakAbs(render(m, 4800).l), 0.1f);
    m.seqAllNotesOff();
    render(m, 480);
    m.setMotion(DrumMachine::motionTarget(key, DrumParamId::Level), kNaN);
    EXPECT_EQ(hit(), ref);

    m.setMotion(DrumMachine::motionTarget(key, DrumParamId::Pitch), 12.0f);
    const auto up = hit();
    EXPECT_NEAR(groove::test::zeroCrossingHz(up, kFs, 2400, 4800) / groove::test::zeroCrossingHz(ref, kFs, 4800, 9600),
                2.0, 0.01);
    m.setMotion(DrumMachine::motionTarget(key, DrumParamId::Reverse), 1.0f);
    m.setMotion(DrumMachine::motionTarget(key, DrumParamId::Level), 30.0f);  // sanitised to 1
    EXPECT_NE(hit(), up);
    m.clearAllMotion();
    EXPECT_EQ(hit(), ref);

    // A motion step on a key with nothing sounding takes effect exactly at its hit.
    m.setMotion(DrumMachine::motionTarget(key, DrumParamId::Level), 0.4f);
    EXPECT_NEAR(peakAbs(hit()) / peakAbs(ref), 0.5f, 1e-4f);
}

TEST(DrumMachine, MotionCanChokeAndUnchoke) {
    DrumMachine& m = fresh(0);
    m.setMotion(DrumMachine::motionTarget(8, DrumParamId::Choke), 0.0f);
    m.seqNoteOn(note(8), 1.0f);
    render(m, 4800);
    m.seqNoteOn(note(6), 1.0f);
    const Stereo after = render(m, 4800);
    const Stereo closed = renderHit(6, 4800);
    std::vector<float> diff(4800);
    for (std::size_t i = 0; i < diff.size(); ++i) diff[i] = after.l[i] - closed.l[i];
    EXPECT_GT(peakAbs(diff, 1000), 0.05f) << "open hat (motion: no group) keeps ringing";
}

TEST(DrumMachine, AnyBlockSizeGivesTheSameOutput) {
    auto play = [](int block) {
        DrumMachine& m = fresh(0);
        m.setParam(10, DrumParamId::Pitch, 3.5f);
        m.setParam(10, DrumParamId::Tone, 3000.0f);
        m.setParam(10, DrumParamId::Pan, -0.3f);
        for (int k : {0, 6, 10, 15}) m.seqNoteOn(note(k), 0.9f);
        return render(m, 30000, block);
    };
    const Stereo ref = play(96);
    for (int block : {1, 7, 16, 333, 4096}) {
        const Stereo s = play(block);
        for (std::size_t i = 0; i < ref.l.size(); ++i) {
            ASSERT_NEAR(s.l[i], ref.l[i], 1e-6f) << block << " @ " << i;
            ASSERT_NEAR(s.r[i], ref.r[i], 1e-6f) << block << " @ " << i;
        }
    }
}

TEST(DrumMachine, WorksAtOtherSampleRates) {
    for (float rate : {44100.0f, 96000.0f}) {
        DrumMachine m;
        m.prepare(rate);
        m.seqNoteOn(note(12), 1.0f);
        const int frames = static_cast<int>(rate * 0.5f);
        const auto s = render(m, frames).l;
        const auto ref = renderHit(12, 24000).l;
        const double hz = groove::test::zeroCrossingHz(s, rate, static_cast<std::size_t>(rate * 0.1f),
                                                       static_cast<std::size_t>(rate * 0.4f));
        const double hz48 = groove::test::zeroCrossingHz(ref, kFs, 4800, 19200);
        EXPECT_NEAR(hz / hz48, 1.0, 0.01) << rate;
    }
    DrumMachine bad;
    bad.prepare(kNaN);  // falls back to 48 kHz rather than synthesising nonsense
    bad.seqNoteOn(note(0), 1.0f);
    EXPECT_GT(peakAbs(render(bad, 4800).l), 0.1f);
}

// --- real-time safety (F0.5) ---

TEST(DrumMachine, RenderAndSequencerCallsNeverAllocate) {
    DrumMachine& m = fresh(0);
    std::vector<float> l(4096), r(4096);
    groove::test::AllocationScope scope;
    for (int i = 0; i < 4000; ++i) {
        const int key = (i * 7) % DrumMachine::kSoundCount;
        if (i % 3 == 0) m.seqNoteOn(note(key), static_cast<float>(i % 10) / 9.0f);
        if (i % 5 == 0) m.noteOn(note((key + 3) % 16), 0.8f, i + 1);
        if (i % 4 == 0) {
            const auto id = static_cast<DrumParamId>(i % static_cast<int>(kDrumParamCount));
            const ParamInfo& p = drumParamInfo(id);
            m.setParam(key, id, p.min + (p.max - p.min) * static_cast<float>(i % 11) / 10.0f);
        }
        if (i % 6 == 0) {
            const int target = (i * 13) % (DrumMachine::kSoundCount * static_cast<int>(kDrumParamCount));
            m.setMotion(target, (i % 12 == 0) ? kNaN : static_cast<float>(i % 9) / 8.0f);
        }
        if (i % 9 == 0) m.seqNoteOff(note(key));
        if (i % 11 == 0) m.noteOff(note(key));
        if (i % 97 == 0) m.clearAllMotion();
        if (i % 301 == 0) m.seqAllNotesOff();
        if (i % 401 == 0) m.allNotesOff();
        m.setTempo(120.0f);
        const int32_t frames = (i % 13 == 0) ? 4096 : 1 + (i * 31) % 300;
        m.renderStereo(l.data(), r.data(), frames);
        NoteOnRender rec;
        while (m.popNoteOnRender(rec)) {
        }
        (void)m.framesRendered();
    }
    EXPECT_EQ(scope.count(), 0u);
}

TEST(DrumMachine, ControlAndAudioThreadsRunConcurrently) {
    // Run under TSan: the UI thread's notes, params and record reads race the audio
    // thread's render, sequencer notes and motion only through the queues and atomics.
    DrumMachine& m = fresh(0);
    std::atomic<bool> stop{false};
    std::atomic<int> records{0};
    std::thread control([&] {
        int i = 0;
        while (!stop.load()) {
            const int key = i % 16;
            m.noteOn(note(key), 0.7f, i + 1);
            m.setParam(key, static_cast<DrumParamId>(i % static_cast<int>(kDrumParamCount)),
                       static_cast<float>(i % 7) / 6.0f);
            (void)m.param(key, DrumParamId::Level);
            (void)m.framesRendered();
            if (i % 50 == 0) m.allNotesOff();
            NoteOnRender rec;
            while (m.popNoteOnRender(rec)) records.fetch_add(1);
            ++i;
            std::this_thread::sleep_for(std::chrono::microseconds(50));
        }
    });
    std::vector<float> l(192), r(192);
    for (int block = 0; block < 1500; ++block) {
        if (block % 4 == 0) m.seqNoteOn(note(block % 16), 1.0f);
        if (block % 10 == 0) m.setMotion(block % 128, static_cast<float>(block % 5) / 4.0f);
        if (block % 64 == 0) m.clearAllMotion();
        m.renderStereo(l.data(), r.data(), 192);
        for (float v : l) ASSERT_TRUE(std::isfinite(v));
    }
    stop.store(true);
    control.join();
    EXPECT_EQ(m.framesRendered(), 1500 * 192);
    EXPECT_GT(records.load(), 0);
}
