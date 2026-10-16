// Tests for the master FX and mixer channel blocks (docs/feature-spec.md F6.1, F6.2, F6.4,
// plus F0.5 and F0.7 for them).

#include "groove/FxBlocks.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <limits>
#include <memory>
#include <vector>

#include "AllocationCounter.h"
#include "Analysis.h"
#include "groove/DspMath.h"
#include "groove/MixParams.h"

using namespace groove;
using groove::test::maxStep;
using groove::test::peakAbs;
using groove::test::rms;

namespace {

constexpr float kFs = 48000.0f;
constexpr double kPi = 3.14159265358979323846;
const float kCentre = std::sqrt(0.5f);  // equal-power pan at the centre

#if defined(__SANITIZE_ADDRESS__) || defined(__SANITIZE_THREAD__)
constexpr bool kSanitized = true;
#elif defined(__has_feature)
#if __has_feature(address_sanitizer) || __has_feature(thread_sanitizer)
constexpr bool kSanitized = true;
#else
constexpr bool kSanitized = false;
#endif
#else
constexpr bool kSanitized = false;
#endif

struct Stereo {
    std::vector<float> l;
    std::vector<float> r;
    Stereo() = default;
    explicit Stereo(std::size_t n) : l(n, 0.0f), r(n, 0.0f) {}
    std::size_t size() const { return l.size(); }
};

int frames(double seconds) { return static_cast<int>(std::lround(seconds * kFs)); }

Stereo silence(int n) { return Stereo(static_cast<std::size_t>(n)); }

Stereo impulse(int n, int at = 0, float amp = 1.0f) {
    Stereo s(static_cast<std::size_t>(n));
    s.l[static_cast<std::size_t>(at)] = s.r[static_cast<std::size_t>(at)] = amp;
    return s;
}

Stereo sine(int n, double hz, float amp, int from = 0, int to = -1) {
    Stereo s(static_cast<std::size_t>(n));
    if (to < 0) to = n;
    for (int i = from; i < to; ++i) {
        const float v = amp * static_cast<float>(std::sin(2.0 * kPi * hz * i / kFs));
        s.l[static_cast<std::size_t>(i)] = s.r[static_cast<std::size_t>(i)] = v;
    }
    return s;
}

// A Hann-windowed sine burst starting at `at`.
Stereo burst(int n, double hz, float amp, int length, int at = 0) {
    Stereo s(static_cast<std::size_t>(n));
    for (int i = 0; i < length; ++i) {
        const double w = 0.5 - 0.5 * std::cos(2.0 * kPi * i / (length - 1));
        const float v = amp * static_cast<float>(w * std::sin(2.0 * kPi * hz * i / kFs));
        s.l[static_cast<std::size_t>(at + i)] = s.r[static_cast<std::size_t>(at + i)] = v;
    }
    return s;
}

Stereo noise(int n, float amp, uint32_t seed, bool mono = false) {
    Stereo s(static_cast<std::size_t>(n));
    dsp::XorShift32 a(seed), b(seed * 7919u + 13u);
    for (std::size_t i = 0; i < s.size(); ++i) {
        s.l[i] = amp * a.nextBipolar();
        s.r[i] = mono ? s.l[i] : amp * b.nextBipolar();
    }
    return s;
}

// Renders `in` through an FX block in chunks of `block` frames.
template <typename Fx>
Stereo run(Fx& fx, const Stereo& in, int block = 96) {
    Stereo out(in.size());
    const int n = static_cast<int>(in.size());
    for (int done = 0; done < n; done += block) {
        const int k = std::min(block, n - done);
        fx.process(in.l.data() + done, in.r.data() + done, out.l.data() + done, out.r.data() + done, k);
    }
    return out;
}

void append(Stereo& to, const Stereo& from) {
    to.l.insert(to.l.end(), from.l.begin(), from.l.end());
    to.r.insert(to.r.end(), from.r.begin(), from.r.end());
}

bool allFinite(const std::vector<float>& x) {
    return std::all_of(x.begin(), x.end(), [](float v) { return std::isfinite(v); });
}

// True when no value is subnormal (each is exactly zero or a normal float).
bool noSubnormals(const std::vector<float>& x) {
    return std::all_of(x.begin(), x.end(), [](float v) { return v == 0.0f || std::fpclassify(v) != FP_SUBNORMAL; });
}

std::size_t argmaxAbs(const std::vector<float>& x, std::size_t from, std::size_t to) {
    to = std::min(to, x.size());
    std::size_t best = from;
    for (std::size_t i = from; i < to; ++i)
        if (std::fabs(x[i]) > std::fabs(x[best])) best = i;
    return best;
}

// Bernstein bound: a signal with peak P and no energy above f moves at most 2*pi*f*P/fs per
// sample. A click breaks it by a wide margin.
float slewBound(double hz, float peak) { return static_cast<float>(2.0 * kPi * hz / kFs) * peak; }

// RBJ band-pass (0 dB peak), double precision, for band-limited decay measurements.
std::vector<double> bandPass(const std::vector<float>& x, double hz, double q) {
    const double w0 = 2.0 * kPi * hz / kFs;
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    const double b0 = alpha / a0, b2 = -alpha / a0;
    const double a1 = -2.0 * std::cos(w0) / a0, a2 = (1.0 - alpha) / a0;
    std::vector<double> y(x.size());
    double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    for (std::size_t i = 0; i < x.size(); ++i) {
        const double v = b0 * x[i] + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = x[i];
        y2 = y1;
        y1 = v;
        y[i] = v;
    }
    return y;
}

// RT60 from the Schroeder energy decay curve of both channels in a band, fitted from -5 to
// -35 dB (T30).
double measureRt60(const Stereo& ir, double hz, double q = 1.4) {
    const auto l = bandPass(ir.l, hz, q);
    const auto r = bandPass(ir.r, hz, q);
    const std::size_t n = l.size();
    std::vector<double> edc(n);
    double acc = 0.0;
    for (std::size_t i = n; i-- > 0;) {
        acc += l[i] * l[i] + r[i] * r[i];
        edc[i] = acc;
    }
    const double total = edc[0];
    std::size_t start = 0, end = 0;
    for (std::size_t i = 0; i < n; ++i) {
        const double db = 10.0 * std::log10(edc[i] / total);
        if (start == 0 && db <= -5.0) start = i;
        if (db <= -35.0) {
            end = i;
            break;
        }
    }
    if (start == 0 || end <= start) return -1.0;
    // Least-squares slope of dB against seconds.
    double sx = 0, sy = 0, sxx = 0, sxy = 0;
    const double m = static_cast<double>(end - start + 1);
    for (std::size_t i = start; i <= end; ++i) {
        const double t = static_cast<double>(i) / kFs;
        const double db = 10.0 * std::log10(edc[i] / total);
        sx += t;
        sy += db;
        sxx += t * t;
        sxy += t * db;
    }
    const double slope = (m * sxy - sx * sy) / (m * sxx - sx * sx);
    return -60.0 / slope;
}

double correlation(const std::vector<float>& a, const std::vector<float>& b, std::size_t from, std::size_t to) {
    double ab = 0, aa = 0, bb = 0;
    for (std::size_t i = from; i < to; ++i) {
        ab += static_cast<double>(a[i]) * b[i];
        aa += static_cast<double>(a[i]) * a[i];
        bb += static_cast<double>(b[i]) * b[i];
    }
    return ab / std::sqrt(aa * bb);
}

double dbRatio(double a, double b) { return 20.0 * std::log10(a / b); }

// --- block factories ---

std::unique_ptr<StereoDelay> makeDelay() {
    auto d = std::make_unique<StereoDelay>();
    d->prepare(kFs);
    return d;
}

// Wide-open tone filters so measurements see the bare delay line.
void openTone(StereoDelay& d) { d.setTone(20.0f, 20000.0f); }

std::unique_ptr<Reverb> makeReverb(float size = 0.6f, float decay = 2.5f, float damping = 0.4f) {
    auto r = std::make_unique<Reverb>();
    r->setSize(size);
    r->setDecaySeconds(decay);
    r->setDamping(damping);
    r->prepare(kFs);
    return r;
}

// Delay-time fractions of a whole note, in MixParams.def dly.div order.
constexpr std::array<double, 11> kDivisionWholeNotes = {1.0 / 24, 1.0 / 16, 3.0 / 32, 1.0 / 12, 1.0 / 8, 3.0 / 16,
                                                         1.0 / 6,  1.0 / 4,  3.0 / 8,  1.0 / 3,  1.0 / 2};

// Sample index of the first echo of an impulse at 0 (feedback off, filters open).
double echoPosition(StereoDelay& d, double expectedSeconds) {
    d.setFeedback(0.0f);
    openTone(d);
    d.reset();
    const int n = frames(expectedSeconds) + 2000;
    const Stereo out = run(d, impulse(n));
    return static_cast<double>(argmaxAbs(out.l, 1, out.size()));
}

}  // namespace

// ============================================================================================
// StereoDelay (F6.1)
// ============================================================================================

TEST(StereoDelay, SyncedTimeMatchesTheDivisionAtSeveralTempos) {
    auto d = makeDelay();
    double worst = 0.0;
    for (float bpm : {60.0f, 90.0f, 120.0f, 140.0f, 174.0f}) {
        for (int div = 0; div <= 10; ++div) {
            d->setTempo(bpm);
            d->setTime(true, div, 0.5f);
            const double expected = std::clamp(kDivisionWholeNotes[static_cast<std::size_t>(div)] * 240.0 / bpm, 0.01, 2.0);
            const double pos = echoPosition(*d, expected);
            const double err = std::fabs(pos - expected * kFs);
            worst = std::max(worst, err);
            EXPECT_LE(err, 1.0) << "bpm " << bpm << " div " << div;
        }
    }
    std::printf("synced delay: worst echo position error %.2f samples\n", worst);
}

TEST(StereoDelay, SyncedTimeLongerThanTwoSecondsIsClamped) {
    auto d = makeDelay();
    d->setTempo(40.0f);  // 1/2 at 40 bpm would be 3 s
    d->setTime(true, 10, 0.5f);
    EXPECT_NEAR(echoPosition(*d, 2.0), 2.0 * kFs, 1.0);
}

TEST(StereoDelay, FreeTimeIsAccurateAndIgnoresTempo) {
    auto d = makeDelay();
    d->setTempo(97.0f);
    for (float t : {0.01f, 0.0375f, 0.1f, 0.2345f, 0.375f, 1.0f, 1.777f, 2.0f}) {
        d->setTime(false, 3, t);
        EXPECT_NEAR(echoPosition(*d, t), t * kFs, 1.0) << t;
    }
    // Out-of-range values clamp to 10 ms .. 2 s.
    d->setTime(false, 3, 0.0001f);
    EXPECT_NEAR(echoPosition(*d, 0.01), 0.01 * kFs, 1.0);
    d->setTime(false, 3, 50.0f);
    EXPECT_NEAR(echoPosition(*d, 2.0), 2.0 * kFs, 1.0);
}

TEST(StereoDelay, OutputIsFullyWet) {
    auto d = makeDelay();
    d->setTime(false, 0, 0.1f);
    const Stereo out = run(*d, impulse(frames(0.05)));
    EXPECT_EQ(peakAbs(out.l), 0.0f);  // nothing before the first echo: no dry signal
    EXPECT_EQ(peakAbs(out.r), 0.0f);
}

TEST(StereoDelay, EachRepeatIsScaledByTheFeedback) {
    for (float fb : {0.3f, 0.6f, 0.95f}) {
        auto d = makeDelay();
        openTone(*d);
        d->setTime(false, 0, 0.1f);
        d->setFeedback(fb);
        d->reset();
        const int period = frames(0.1);
        const Stereo out = run(*d, burst(period * 7, 1000.0, 0.25f, frames(0.02)));
        double prev = 0.0;
        for (int k = 1; k <= 6; ++k) {
            const double level = rms(out.l, static_cast<std::size_t>(k * period), static_cast<std::size_t>((k + 1) * period));
            if (k > 1) EXPECT_NEAR(dbRatio(level, prev), dbRatio(fb, 1.0), 0.2) << "fb " << fb << " repeat " << k;
            prev = level;
        }
    }
}

// F6.1: feedback never runs away, even with the tone filters wide open or set to extremes, at
// the shortest time (the most trips round the loop), for 60 s of full-scale noise.
TEST(StereoDelay, FullScaleNoiseAtMaxFeedbackStaysBoundedFor60Seconds) {
    struct Case {
        float time, lowCut, highCut;
    };
    for (const Case c : {Case{0.01f, 20.0f, 20000.0f}, Case{0.375f, 120.0f, 6000.0f}, Case{0.0123f, 2000.0f, 1000.0f}}) {
        auto d = makeDelay();
        d->setTime(false, 0, c.time);
        d->setFeedback(0.95f);
        d->setTone(c.lowCut, c.highCut);
        d->reset();
        const Stereo out = run(*d, noise(frames(60.0), 1.0f, 99u), 192);
        ASSERT_TRUE(allFinite(out.l) && allFinite(out.r));
        const float peak = std::max(peakAbs(out.l), peakAbs(out.r));
        const double early = rms(out.l, static_cast<std::size_t>(frames(10)), static_cast<std::size_t>(frames(20)));
        const double late = rms(out.l, static_cast<std::size_t>(frames(50)), static_cast<std::size_t>(frames(60)));
        std::printf("delay %.4f s, cuts %.0f/%.0f Hz: peak %.2f, rms 10-20 s %.3f, 50-60 s %.3f\n", c.time, c.lowCut,
                    c.highCut, peak, early, late);
        EXPECT_LT(peak, 8.0f);
        EXPECT_LT(late, early * 1.05 + 1e-6);  // not growing
    }
}

TEST(StereoDelay, ToneFiltersAttenuateInsideTheFeedbackPath) {
    const int period = frames(0.1);
    auto echoLevels = [&](float lowCut, float highCut, double hz) {
        auto d = makeDelay();
        d->setTime(false, 0, 0.1f);
        d->setFeedback(0.9f);
        d->setTone(lowCut, highCut);
        d->reset();
        const Stereo out = run(*d, burst(period * 4, hz, 0.25f, frames(0.04)));
        const double in = 0.25 * std::sqrt(3.0 / 16.0);  // rms of a Hann-windowed sine burst over its length
        const double e1 = rms(out.l, static_cast<std::size_t>(period), static_cast<std::size_t>(period + frames(0.04)));
        const double e2 = rms(out.l, static_cast<std::size_t>(2 * period), static_cast<std::size_t>(2 * period + frames(0.04)));
        return std::array<double, 2>{dbRatio(e1, in), dbRatio(e2, e1)};
    };
    // Low cut at 1 kHz: 100 Hz loses about 20 dB on the first echo and again on each repeat.
    const auto lo = echoLevels(1000.0f, 20000.0f, 100.0);
    EXPECT_LT(lo[0], -14.0);
    EXPECT_LT(lo[1], dbRatio(0.9, 1.0) - 14.0);
    // High cut at 1 kHz: 8 kHz loses about 18 dB per trip.
    const auto hi = echoLevels(20.0f, 1000.0f, 8000.0);
    EXPECT_LT(hi[0], -12.0);
    EXPECT_LT(hi[1], dbRatio(0.9, 1.0) - 12.0);
    // In the pass band (defaults 120 Hz / 6 kHz) 800 Hz is barely touched.
    const auto mid = echoLevels(120.0f, 6000.0f, 800.0);
    EXPECT_NEAR(mid[0], 0.0, 1.0);
    EXPECT_NEAR(mid[1], dbRatio(0.9, 1.0), 1.0);
    std::printf("tone: 100 Hz via 1k low cut %.1f dB, 8 kHz via 1k high cut %.1f dB, 800 Hz %.2f dB\n", lo[0], hi[0],
                mid[0]);
}

TEST(StereoDelay, MonoInputStaysMono) {
    auto d = makeDelay();
    d->setFeedback(0.8f);
    const Stereo out = run(*d, noise(frames(2.0), 0.5f, 3u, true));
    EXPECT_EQ(out.l, out.r);
}

TEST(StereoDelay, StereoRepeatsCrossFeedALittle) {
    auto d = makeDelay();
    openTone(*d);
    d->setTime(false, 0, 0.1f);
    d->setFeedback(0.8f);
    d->reset();
    Stereo in = burst(frames(0.5), 1000.0, 0.25f, frames(0.02));
    std::fill(in.r.begin(), in.r.end(), 0.0f);
    const Stereo out = run(*d, in);
    const auto first = [&](const std::vector<float>& x) {
        return rms(x, static_cast<std::size_t>(frames(0.1)), static_cast<std::size_t>(frames(0.2)));
    };
    const auto second = [&](const std::vector<float>& x) {
        return rms(x, static_cast<std::size_t>(frames(0.2)), static_cast<std::size_t>(frames(0.3)));
    };
    EXPECT_EQ(first(out.r), 0.0);       // the first echo keeps its side
    EXPECT_GT(second(out.r), 0.0);      // later repeats spread a little
    EXPECT_LT(second(out.r), 0.5 * second(out.l));
}

// Time changes glide: the read head moves smoothly, so a running sine never steps.
TEST(StereoDelay, TimeChangesGlideWithoutClicks) {
    auto d = makeDelay();
    openTone(*d);
    d->setFeedback(0.5f);
    d->setTime(false, 0, 0.25f);
    d->reset();
    const double hz = 220.0;
    const Stereo in = sine(frames(8.0), hz, 0.5f);
    Stereo out;
    const int seg = frames(1.0);
    std::vector<float> steady;
    for (int s = 0; s < 8; ++s) {
        if (s == 2) d->setTime(false, 0, 0.6f);
        if (s == 4) d->setTime(false, 0, 0.05f);
        if (s == 5) d->setTime(true, 4, 0.05f);  // synced 1/8 at the default tempo
        if (s == 6) d->setTempo(132.0f);
        Stereo part;
        part.l.assign(in.l.begin() + s * seg, in.l.begin() + (s + 1) * seg);
        part.r = part.l;
        append(out, run(*d, part));
    }
    const float baseline = maxStep(out.l, static_cast<std::size_t>(frames(1.2)), static_cast<std::size_t>(frames(2.0)));
    const float during = maxStep(out.l, static_cast<std::size_t>(frames(2.0)), out.size());
    const float peak = peakAbs(out.l, static_cast<std::size_t>(frames(1.0)));
    std::printf("delay glide: steady max step %.4f, during changes %.4f (peak %.2f)\n", baseline, during, peak);
    ASSERT_TRUE(allFinite(out.l));
    // The glide shifts pitch up to 1.8x; a click would be far larger than that slew.
    EXPECT_LT(during, slewBound(hz * 2.0, peak));
    EXPECT_LT(during, 3.0f * baseline);
}

TEST(StereoDelay, TheGlideLandsOnTheNewTime) {
    auto d = makeDelay();
    d->setFeedback(0.0f);
    openTone(*d);
    d->setTime(false, 0, 0.3f);
    d->reset();
    run(*d, sine(frames(0.5), 300.0, 0.5f));
    d->setTime(false, 0, 1.1f);
    run(*d, silence(frames(4.0)));  // glide and let the line empty
    const Stereo out = run(*d, impulse(frames(1.5)));
    EXPECT_NEAR(static_cast<double>(argmaxAbs(out.l, 1, out.size())), 1.1 * kFs, 1.0);
}

TEST(StereoDelay, FeedbackAndToneChangesDoNotClick) {
    auto d = makeDelay();
    d->setTime(false, 0, 0.2f);
    d->setFeedback(0.0f);
    d->reset();
    const double hz = 500.0;
    const Stereo in = sine(frames(4.0), hz, 0.5f);
    Stereo out;
    const int seg = frames(0.25);
    for (int s = 0; s < 16; ++s) {
        d->setFeedback((s % 2) ? 0.95f : 0.0f);
        d->setTone((s % 3) ? 2000.0f : 20.0f, (s % 4) ? 1000.0f : 20000.0f);
        Stereo part;
        part.l.assign(in.l.begin() + s * seg, in.l.begin() + (s + 1) * seg);
        part.r = part.l;
        append(out, run(*d, part));
    }
    ASSERT_TRUE(allFinite(out.l));
    const float peak = peakAbs(out.l);
    const float step = maxStep(out.l, static_cast<std::size_t>(frames(0.21)));
    std::printf("delay fb/tone jumps: max step %.4f, peak %.3f, bound %.4f\n", step, peak, slewBound(hz, peak));
    EXPECT_LT(step, 1.5f * slewBound(hz, peak));
}

TEST(StereoDelay, AnyBlockSizeGivesIdenticalOutput) {
    const Stereo in = noise(frames(1.0), 0.7f, 21u);
    auto render = [&](int block) {
        auto d = makeDelay();
        d->setTime(false, 0, 0.07f);
        d->setFeedback(0.7f);
        Stereo out = run(*d, in, block);
        // Changes mid-render (all of them glide or smooth) at the same sample for every size.
        d->setTime(true, 2, 0.1f);
        d->setTempo(150.0f);
        d->setFeedback(0.9f);
        d->setTone(400.0f, 3000.0f);
        append(out, run(*d, in, block));
        return out;
    };
    const Stereo ref = render(96);
    for (int block : {1, 7, 64, 480, 4096}) {
        const Stereo out = render(block);
        EXPECT_EQ(out.l, ref.l) << block;
        EXPECT_EQ(out.r, ref.r) << block;
    }
}

TEST(StereoDelay, ProcessAndSettersNeverAllocate) {
    auto d = makeDelay();
    std::vector<float> inL(4096), inR(4096), outL(4096), outR(4096);
    dsp::XorShift32 rng(5);
    for (std::size_t i = 0; i < inL.size(); ++i) {
        inL[i] = rng.nextBipolar();
        inR[i] = rng.nextBipolar();
    }
    groove::test::AllocationScope scope;
    for (int i = 0; i < 3000; ++i) {
        d->setTempo(60.0f + static_cast<float>(i % 150));
        d->setTime(i % 2 == 0, i % 11, 0.01f + 0.001f * static_cast<float>(i % 1990));
        d->setFeedback(static_cast<float>(i % 20) / 19.0f);
        d->setTone(20.0f + static_cast<float>(i % 50) * 40.0f, 1000.0f + static_cast<float>(i % 30) * 600.0f);
        if (i % 500 == 0) d->reset();
        const int32_t n = (i % 13 == 0) ? 4096 : 1 + (i * 31) % 300;
        d->process(inL.data(), inR.data(), outL.data(), outR.data(), n);
    }
    EXPECT_EQ(scope.count(), 0u);
}

TEST(StereoDelay, NonFiniteInputAndParamsDoNotPoisonTheLoop) {
    auto d = makeDelay();
    d->setFeedback(0.95f);
    d->setTime(false, 0, 0.01f);
    Stereo in = noise(frames(1.0), 0.5f, 8u);
    in.l[100] = std::numeric_limits<float>::quiet_NaN();
    in.r[200] = std::numeric_limits<float>::infinity();
    in.l[300] = -std::numeric_limits<float>::infinity();
    d->setTempo(std::nanf(""));
    d->setFeedback(std::nanf(""));
    d->setTone(std::nanf(""), std::nanf(""));
    d->setTime(false, 0, std::nanf(""));
    const Stereo out = run(*d, in);
    EXPECT_TRUE(allFinite(out.l) && allFinite(out.r));
    EXPECT_GT(rms(out.l, out.size() / 2), 0.01);
}

// Silence after a loud loop decays to exact zeros: no denormals, so no slowdown.
TEST(StereoDelay, LongRendersStayFiniteAndDecayToExactZero) {
    auto d = makeDelay();
    d->setTime(false, 0, 0.01f);
    d->setFeedback(0.95f);
    d->setTone(20.0f, 20000.0f);
    Stereo out = run(*d, noise(frames(5.0), 1.0f, 4u), 128);
    append(out, run(*d, silence(frames(25.0)), 128));
    ASSERT_TRUE(allFinite(out.l) && allFinite(out.r));
    EXPECT_TRUE(noSubnormals(out.l) && noSubnormals(out.r));
    EXPECT_EQ(peakAbs(out.l, out.size() - static_cast<std::size_t>(frames(1.0))), 0.0f);
    EXPECT_EQ(peakAbs(out.r, out.size() - static_cast<std::size_t>(frames(1.0))), 0.0f);
}

TEST(StereoDelay, UnpreparedRendersSilence) {
    StereoDelay d;
    std::vector<float> in(64, 0.5f), l(64, 1.0f), r(64, 1.0f);
    d.process(in.data(), in.data(), l.data(), r.data(), 64);
    EXPECT_EQ(peakAbs(l), 0.0f);
    EXPECT_EQ(peakAbs(r), 0.0f);
}

TEST(StereoDelay, ProcessesInPlace) {
    auto a = makeDelay();
    auto b = makeDelay();
    const Stereo in = noise(frames(0.5), 0.5f, 77u);
    const Stereo ref = run(*a, in);
    Stereo buf = in;
    b->process(buf.l.data(), buf.r.data(), buf.l.data(), buf.r.data(), static_cast<int32_t>(buf.size()));
    EXPECT_EQ(buf.l, ref.l);
    EXPECT_EQ(buf.r, ref.r);
}

// ============================================================================================
// Reverb (F6.2)
// ============================================================================================

// Impulse response long enough for a T30 fit with margin.
static Stereo reverbIr(Reverb& rv, double rt60) { return run(rv, impulse(frames(1.3 * rt60 + 0.5), 0, 0.5f), 256); }

TEST(Reverb, Rt60MatchesTheDecaySettingAtMidFrequencies) {
    struct Case {
        float size, decay, damping;
    };
    const Case cases[] = {{0.6f, 0.2f, 0.4f},  {0.6f, 0.5f, 0.4f}, {0.6f, 1.0f, 0.4f}, {0.6f, 2.5f, 0.4f},
                          {0.6f, 5.0f, 0.4f},  {0.6f, 10.0f, 0.4f}, {0.0f, 0.3f, 0.4f}, {0.0f, 5.0f, 0.4f},
                          {1.0f, 0.5f, 0.4f},  {1.0f, 5.0f, 0.4f},  {0.3f, 2.5f, 0.0f}, {0.6f, 2.5f, 1.0f},
                          {0.25f, 0.2f, 1.0f}, {1.0f, 2.0f, 1.0f}};
    double worst = 0.0;
    for (const Case c : cases) {
        auto rv = makeReverb(c.size, c.decay, c.damping);
        for (double hz : {500.0, 1000.0}) {
            const double rt = measureRt60(reverbIr(*rv, c.decay), hz);
            const double err = rt / c.decay - 1.0;
            worst = std::max(worst, std::fabs(err));
            std::printf("reverb size %.2f decay %5.2f s damp %.1f: RT60 @%4.0f Hz %6.3f s (%+.1f%%)\n", c.size, c.decay,
                        c.damping, hz, rt, 100.0 * err);
            EXPECT_NEAR(rt, c.decay, 0.2 * c.decay) << "size " << c.size << " damping " << c.damping << " at " << hz;
            rv->reset();
        }
    }
    std::printf("reverb RT60: worst error %.1f%%\n", 100.0 * worst);
}

TEST(Reverb, Rt60IsRightAtTwentySeconds) {
    auto rv = makeReverb(0.8f, 20.0f, 0.4f);
    const double rt = measureRt60(reverbIr(*rv, 20.0), 1000.0);
    std::printf("reverb 20 s: RT60 @1 kHz %.2f s\n", rt);
    EXPECT_NEAR(rt, 20.0, 4.0);
}

TEST(Reverb, DampingShortensTheHighFrequencyDecay) {
    for (float damping : {0.0f, 0.5f, 1.0f}) {
        auto rv = makeReverb(0.6f, 3.0f, damping);
        const Stereo ir = reverbIr(*rv, 3.0);
        const double mid = measureRt60(ir, 1000.0);
        const double high = measureRt60(ir, 10000.0, 2.0);
        std::printf("reverb damping %.1f: RT60 1 kHz %.2f s, 10 kHz %.2f s\n", damping, mid, high);
        if (damping == 0.0f) EXPECT_GT(high, 0.75 * mid);
        if (damping == 0.5f) EXPECT_LT(high, 0.8 * mid);
        if (damping == 1.0f) EXPECT_LT(high, 0.4 * mid);
    }
}

TEST(Reverb, OutputIsFullyWetAndStartsAfterTheShortestLine) {
    auto rv = makeReverb(0.6f, 2.5f, 0.4f);
    const Stereo out = run(*rv, impulse(frames(0.5)));
    EXPECT_EQ(out.l[0], 0.0f);
    EXPECT_EQ(peakAbs(out.l, 0, static_cast<std::size_t>(frames(0.004))), 0.0f);  // no dry path
    EXPECT_GT(peakAbs(out.l), 0.0f);
}

// The energy envelope falls in a straight line (in dB) with no bumps or flutter.
TEST(Reverb, AnImpulseDecaysSmoothly) {
    for (float size : {0.0f, 0.6f, 1.0f}) {
        auto rv = makeReverb(size, 2.5f, 0.4f);
        const Stereo ir = reverbIr(*rv, 2.5);
        const std::size_t win = static_cast<std::size_t>(frames(0.05));
        std::vector<double> t, db;
        for (std::size_t s = static_cast<std::size_t>(frames(0.15)); s + win < static_cast<std::size_t>(frames(2.5)); s += win) {
            const double e = std::sqrt(0.5 * (rms(ir.l, s, s + win) * rms(ir.l, s, s + win) + rms(ir.r, s, s + win) * rms(ir.r, s, s + win)));
            t.push_back(static_cast<double>(s) / kFs);
            db.push_back(20.0 * std::log10(e));
        }
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        const double m = static_cast<double>(t.size());
        for (std::size_t i = 0; i < t.size(); ++i) {
            sx += t[i];
            sy += db[i];
            sxx += t[i] * t[i];
            sxy += t[i] * db[i];
        }
        const double slope = (m * sxy - sx * sy) / (m * sxx - sx * sx);
        const double icpt = (sy - slope * sx) / m;
        double worst = 0.0;
        for (std::size_t i = 0; i < t.size(); ++i) worst = std::max(worst, std::fabs(db[i] - (icpt + slope * t[i])));
        std::printf("reverb size %.1f: 50 ms envelope max deviation from a straight decay %.2f dB (slope %.1f dB/s)\n",
                    size, worst, slope);
        EXPECT_LT(worst, 3.0) << size;
        EXPECT_LT(slope, 0.0);
    }
}

TEST(Reverb, OfflineRendersRepeatExactly) {
    const Stereo in = noise(frames(0.3), 0.8f, 11u);
    Stereo longIn = in;
    append(longIn, silence(frames(2.0)));
    auto a = makeReverb(0.7f, 4.0f, 0.3f);
    auto b = makeReverb(0.7f, 4.0f, 0.3f);
    const Stereo ra = run(*a, longIn, 96);
    const Stereo rb = run(*b, longIn, 96);
    EXPECT_EQ(ra.l, rb.l);
    EXPECT_EQ(ra.r, rb.r);
    a->reset();
    const Stereo again = run(*a, longIn, 96);
    EXPECT_EQ(again.l, ra.l);
    EXPECT_EQ(again.r, ra.r);
}

TEST(Reverb, StereoOutputIsDecorrelated) {
    auto rv = makeReverb(0.6f, 2.5f, 0.4f);
    Stereo in = noise(frames(0.5), 0.8f, 2u, true);  // mono input
    append(in, silence(frames(2.0)));
    const Stereo out = run(*rv, in);
    const double rho = correlation(out.l, out.r, static_cast<std::size_t>(frames(0.1)), out.size());
    const double balance = dbRatio(rms(out.l), rms(out.r));
    std::printf("reverb L/R correlation for mono input %.3f, balance %.2f dB\n", rho, balance);
    EXPECT_LT(std::fabs(rho), 0.25);
    EXPECT_NEAR(balance, 0.0, 1.5);
}

TEST(Reverb, WetLevelIsSensible) {
    auto rv = makeReverb(0.6f, 2.5f, 0.4f);
    const Stereo in = noise(frames(6.0), 0.5f, 6u);
    const Stereo out = run(*rv, in);
    const double gain = dbRatio(rms(out.l, static_cast<std::size_t>(frames(4.0))), rms(in.l));
    std::printf("reverb steady-state noise gain at the defaults %.1f dB\n", gain);
    EXPECT_NEAR(gain, 0.0, 6.0);
}

TEST(Reverb, StableAtTwentySecondsForAMinuteOfFullScaleNoise) {
    for (float damping : {0.0f, 1.0f}) {
        auto rv = makeReverb(1.0f, 20.0f, damping);
        const Stereo out = run(*rv, noise(frames(60.0), 1.0f, 31u), 192);
        ASSERT_TRUE(allFinite(out.l) && allFinite(out.r));
        const double early = rms(out.l, static_cast<std::size_t>(frames(30)), static_cast<std::size_t>(frames(40)));
        const double late = rms(out.l, static_cast<std::size_t>(frames(50)), static_cast<std::size_t>(frames(60)));
        const float peak = std::max(peakAbs(out.l), peakAbs(out.r));
        std::printf("reverb 20 s damp %.0f, 60 s full-scale noise: peak %.2f, rms 30-40 s %.3f, 50-60 s %.3f\n", damping,
                    peak, early, late);
        EXPECT_LT(late, early * 1.1);
        EXPECT_LT(peak, 30.0f);
    }
}

// Parameter jumps while a tone rings through: the output slews like a sine, never steps.
TEST(Reverb, ParameterChangesDoNotClickOrBlowUp) {
    auto rv = makeReverb(0.6f, 2.5f, 0.4f);
    const double hz = 400.0;
    const int seg = frames(0.25);
    Stereo out;
    for (int s = 0; s < 24; ++s) {
        if (s >= 4) {
            rv->setSize((s % 3 == 0) ? 0.0f : (s % 3 == 1 ? 1.0f : 0.5f));
            rv->setDecaySeconds((s % 2) ? 20.0f : 0.2f);
            rv->setDamping((s % 4 < 2) ? 0.0f : 1.0f);
        }
        Stereo part = sine(seg, hz, 0.3f);
        // Keep the phase continuous across segments.
        for (int i = 0; i < seg; ++i)
            part.l[static_cast<std::size_t>(i)] = part.r[static_cast<std::size_t>(i)] =
                0.3f * static_cast<float>(std::sin(2.0 * kPi * hz * (s * seg + i) / kFs));
        append(out, run(*rv, part));
    }
    ASSERT_TRUE(allFinite(out.l) && allFinite(out.r));
    float worstRatio = 0.0f;
    const std::size_t win = static_cast<std::size_t>(frames(0.05));
    for (std::size_t s = static_cast<std::size_t>(frames(0.5)); s + win <= out.size(); s += win) {
        for (const auto* x : {&out.l, &out.r}) {
            const float p = peakAbs(*x, s - 1, s + win);
            if (p < 1e-4f) continue;
            worstRatio = std::max(worstRatio, maxStep(*x, s, s + win) / slewBound(hz, p));
        }
    }
    std::printf("reverb parameter jumps: worst step / sine slew bound %.3f, peak %.2f\n", worstRatio,
                std::max(peakAbs(out.l), peakAbs(out.r)));
    EXPECT_LT(worstRatio, 1.5f);
    EXPECT_LT(std::max(peakAbs(out.l), peakAbs(out.r)), 20.0f);
}

TEST(Reverb, AnyBlockSizeGivesIdenticalOutput) {
    const Stereo in = noise(frames(0.6), 0.7f, 41u);
    auto render = [&](int block) {
        auto rv = makeReverb(0.4f, 3.0f, 0.5f);
        Stereo out = run(*rv, in, block);
        rv->setSize(0.9f);
        rv->setDecaySeconds(0.7f);
        rv->setDamping(0.1f);
        append(out, run(*rv, in, block));
        return out;
    };
    const Stereo ref = render(96);
    for (int block : {1, 5, 32, 333, 4096}) {
        const Stereo out = render(block);
        EXPECT_EQ(out.l, ref.l) << block;
        EXPECT_EQ(out.r, ref.r) << block;
    }
}

TEST(Reverb, ProcessAndSettersNeverAllocate) {
    auto rv = makeReverb();
    std::vector<float> inL(4096), inR(4096), outL(4096), outR(4096);
    dsp::XorShift32 rng(9);
    for (std::size_t i = 0; i < inL.size(); ++i) {
        inL[i] = rng.nextBipolar();
        inR[i] = rng.nextBipolar();
    }
    groove::test::AllocationScope scope;
    for (int i = 0; i < 3000; ++i) {
        rv->setSize(static_cast<float>(i % 17) / 16.0f);
        rv->setDecaySeconds(0.2f + static_cast<float>(i % 40) * 0.5f);
        rv->setDamping(static_cast<float>(i % 9) / 8.0f);
        if (i % 700 == 0) rv->reset();
        const int32_t n = (i % 13 == 0) ? 4096 : 1 + (i * 31) % 300;
        rv->process(inL.data(), inR.data(), outL.data(), outR.data(), n);
    }
    EXPECT_EQ(scope.count(), 0u);
}

TEST(Reverb, LongRendersStayFiniteAndDecayToExactZero) {
    auto rv = makeReverb(0.6f, 0.2f, 0.4f);
    Stereo in = noise(frames(10.0), 1.0f, 12u);
    in.l[1000] = std::numeric_limits<float>::quiet_NaN();
    in.r[2000] = std::numeric_limits<float>::infinity();
    rv->setDecaySeconds(std::nanf(""));
    rv->setSize(std::nanf(""));
    rv->setDamping(std::nanf(""));
    rv->setDecaySeconds(0.2f);
    Stereo out = run(*rv, in, 128);
    append(out, run(*rv, silence(frames(10.0)), 128));
    ASSERT_TRUE(allFinite(out.l) && allFinite(out.r));
    EXPECT_GT(rms(out.l, static_cast<std::size_t>(frames(5)), static_cast<std::size_t>(frames(10))), 0.01);
    EXPECT_TRUE(noSubnormals(out.l) && noSubnormals(out.r));
    EXPECT_EQ(peakAbs(out.l, out.size() - static_cast<std::size_t>(frames(2.0))), 0.0f);
    EXPECT_EQ(peakAbs(out.r, out.size() - static_cast<std::size_t>(frames(2.0))), 0.0f);
}

TEST(Reverb, UnpreparedRendersSilenceAndInPlaceWorks) {
    Reverb unprepared;
    std::vector<float> in(64, 0.5f), l(64, 1.0f), r(64, 1.0f);
    unprepared.process(in.data(), in.data(), l.data(), r.data(), 64);
    EXPECT_EQ(peakAbs(l), 0.0f);
    EXPECT_EQ(peakAbs(r), 0.0f);

    auto a = makeReverb();
    auto b = makeReverb();
    const Stereo src = noise(frames(0.5), 0.5f, 78u);
    const Stereo ref = run(*a, src);
    Stereo buf = src;
    b->process(buf.l.data(), buf.r.data(), buf.l.data(), buf.r.data(), static_cast<int32_t>(buf.size()));
    EXPECT_EQ(buf.l, ref.l);
    EXPECT_EQ(buf.r, ref.r);
}

// ============================================================================================
// ChannelStrip (F6.4)
// ============================================================================================

namespace {

struct StripOut {
    Stereo mix, delay, reverb;
};

StripOut runStrip(ChannelStrip& s, const Stereo& in, int block = 96, float busFill = 0.0f) {
    StripOut o;
    o.mix = Stereo(in.size());
    o.delay = Stereo(in.size());
    o.reverb = Stereo(in.size());
    for (Stereo* b : {&o.mix, &o.delay, &o.reverb}) {
        std::fill(b->l.begin(), b->l.end(), busFill);
        std::fill(b->r.begin(), b->r.end(), busFill);
    }
    const int n = static_cast<int>(in.size());
    for (int done = 0; done < n; done += block) {
        const int k = std::min(block, n - done);
        s.process(in.l.data() + done, in.r.data() + done, k, o.mix.l.data() + done, o.mix.r.data() + done,
                  o.delay.l.data() + done, o.delay.r.data() + done, o.reverb.l.data() + done, o.reverb.r.data() + done);
    }
    return o;
}

Stereo dc(int n, float v = 1.0f) {
    Stereo s(static_cast<std::size_t>(n));
    std::fill(s.l.begin(), s.l.end(), v);
    std::fill(s.r.begin(), s.r.end(), v);
    return s;
}

std::unique_ptr<ChannelStrip> makeStrip() {
    auto s = std::make_unique<ChannelStrip>();
    s->prepare(kFs);
    return s;
}

// The strip's settled gains, read from the last sample of a DC render after 50 ms.
StripOut settled(ChannelStrip& s) { return runStrip(s, dc(frames(0.05))); }

}  // namespace

TEST(ChannelStrip, DefaultsAreUnityVolumeCentrePanNoSends) {
    auto s = makeStrip();
    const StripOut o = settled(*s);
    EXPECT_NEAR(o.mix.l.back(), kCentre, 1e-6);
    EXPECT_NEAR(o.mix.r.back(), kCentre, 1e-6);
    EXPECT_EQ(peakAbs(o.delay.l), 0.0f);
    EXPECT_EQ(peakAbs(o.reverb.r), 0.0f);
    // A fresh strip starts at its settings: no fade-in on the first block.
    EXPECT_NEAR(o.mix.l.front(), kCentre, 1e-6);
}

TEST(ChannelStrip, VolumeFollowsTheDecibelLawAndMinusSixtyIsSilence) {
    auto s = makeStrip();
    for (float db : {6.0f, 3.0f, 0.0f, -6.0f, -20.0f, -40.0f, -59.9f, -60.0f, -80.0f}) {
        s->setVolumeDb(db);
        const StripOut o = settled(*s);
        const float expected = db <= -60.0f ? 0.0f : std::pow(10.0f, db / 20.0f) * kCentre;
        EXPECT_NEAR(o.mix.l.back(), expected, 1e-5f * std::max(expected, 1e-3f) + 1e-9f) << db;
        EXPECT_NEAR(o.mix.r.back(), expected, 1e-5f * std::max(expected, 1e-3f) + 1e-9f) << db;
        if (db <= -60.0f) EXPECT_EQ(o.mix.l.back(), 0.0f);
    }
}

TEST(ChannelStrip, PanIsEqualPowerWithMinusThreeDecibelsAtTheCentre) {
    auto s = makeStrip();
    for (float pan : {-1.0f, -0.75f, -0.5f, -0.2f, 0.0f, 0.3f, 0.5f, 1.0f}) {
        s->setPan(pan);
        const StripOut o = settled(*s);
        const double l = o.mix.l.back(), r = o.mix.r.back();
        EXPECT_NEAR(l * l + r * r, 1.0, 1e-5) << pan;
        const double theta = (pan + 1.0) * kPi / 4.0;
        EXPECT_NEAR(l, std::cos(theta), 1e-5) << pan;
        EXPECT_NEAR(r, std::sin(theta), 1e-5) << pan;
    }
    s->setPan(0.0f);
    const StripOut c = settled(*s);
    EXPECT_NEAR(dbRatio(c.mix.l.back(), 1.0), -3.0103, 0.001);
    s->setPan(-1.0f);
    const StripOut hardLeft = settled(*s);
    EXPECT_NEAR(hardLeft.mix.r.back(), 0.0f, 1e-6f);
}

TEST(ChannelStrip, MuteFadesOverFiveMillisecondsWithoutAClick) {
    auto s = makeStrip();
    s->setPan(-1.0f);
    settled(*s);
    s->setMute(true);
    const StripOut down = runStrip(*s, dc(frames(0.02)), 64);
    const int fade = frames(0.005);  // 240 samples
    // Falls monotonically, reaches exact silence at 5 ms, and not much sooner.
    for (int i = 1; i < frames(0.02); ++i) EXPECT_LE(down.mix.l[static_cast<std::size_t>(i)], down.mix.l[static_cast<std::size_t>(i - 1)]);
    EXPECT_GT(down.mix.l[static_cast<std::size_t>(fade - 25)], 0.0f);
    EXPECT_EQ(down.mix.l[static_cast<std::size_t>(fade)], 0.0f);
    EXPECT_EQ(peakAbs(down.mix.l, static_cast<std::size_t>(fade)), 0.0f);
    // An S-shaped 5 ms fade: the steepest step is 1.5x a straight ramp's.
    EXPECT_LT(maxStep(down.mix.l), 1.6f / static_cast<float>(fade));
    EXPECT_GT(maxStep(down.mix.l), 1.0f / static_cast<float>(fade));

    s->setMute(false);
    const StripOut up = runStrip(*s, dc(frames(0.02)), 64);
    EXPECT_LT(up.mix.l[static_cast<std::size_t>(fade - 25)], 1.0f);
    EXPECT_NEAR(up.mix.l[static_cast<std::size_t>(fade)], 1.0f, 1e-6f);
    EXPECT_LT(maxStep(up.mix.l), 1.6f / static_cast<float>(fade));

    // On a loud low tone the mute never steps faster than the tone itself plus the fade.
    const Stereo tone = sine(frames(0.2), 100.0, 1.0f);
    std::vector<float> out;
    for (int k = 0; k < 4; ++k) {
        s->setMute(k % 2 == 0);
        Stereo part;
        part.l.assign(tone.l.begin() + k * frames(0.05), tone.l.begin() + (k + 1) * frames(0.05));
        part.r = part.l;
        const StripOut o = runStrip(*s, part);
        out.insert(out.end(), o.mix.l.begin(), o.mix.l.end());
    }
    EXPECT_LT(maxStep(out), slewBound(100.0, 1.0f) + 1.6f / static_cast<float>(fade));
}

TEST(ChannelStrip, SendsArePostFaderPostPanAndPostMute) {
    auto s = makeStrip();
    s->setSends(0.5f, 0.25f);
    s->setVolumeDb(-6.0f);
    s->setPan(0.4f);
    StripOut o = settled(*s);
    EXPECT_NEAR(o.delay.l.back(), 0.5f * o.mix.l.back(), 1e-6f);
    EXPECT_NEAR(o.delay.r.back(), 0.5f * o.mix.r.back(), 1e-6f);
    EXPECT_NEAR(o.reverb.l.back(), 0.25f * o.mix.l.back(), 1e-6f);
    EXPECT_NEAR(o.reverb.r.back(), 0.25f * o.mix.r.back(), 1e-6f);

    s->setVolumeDb(-60.0f);
    o = settled(*s);
    EXPECT_EQ(o.delay.l.back(), 0.0f);
    EXPECT_EQ(o.reverb.r.back(), 0.0f);

    s->setVolumeDb(0.0f);
    s->setMute(true);
    o = settled(*s);
    EXPECT_EQ(o.delay.l.back(), 0.0f);
    EXPECT_EQ(o.reverb.l.back(), 0.0f);
    EXPECT_EQ(o.mix.l.back(), 0.0f);
}

TEST(ChannelStrip, AddsIntoTheBusesAndAcceptsNullSends) {
    auto s = makeStrip();
    s->setSends(1.0f, 1.0f);
    settled(*s);
    const StripOut o = runStrip(*s, dc(256, 0.5f), 96, 0.25f);
    EXPECT_NEAR(o.mix.l.back(), 0.25f + 0.5f * kCentre, 1e-6f);
    EXPECT_NEAR(o.delay.r.back(), 0.25f + 0.5f * kCentre, 1e-6f);
    EXPECT_NEAR(o.reverb.l.back(), 0.25f + 0.5f * kCentre, 1e-6f);

    std::vector<float> in(128, 1.0f), ml(128, 0.0f), mr(128, 0.0f), rl(128, 0.0f), rr(128, 0.0f);
    s->process(in.data(), in.data(), 128, ml.data(), mr.data(), nullptr, nullptr, nullptr, nullptr);
    s->process(in.data(), in.data(), 128, ml.data(), mr.data(), nullptr, nullptr, rl.data(), rr.data());
    EXPECT_NEAR(ml.back(), 2.0f * kCentre, 1e-6f);
    EXPECT_NEAR(rl.back(), kCentre, 1e-6f);
}

// F0.7: every change settles within 10 ms and moves in small steps (no zipper).
TEST(ChannelStrip, EveryChangeIsSmoothedWithinTenMilliseconds) {
    struct Change {
        const char* name;
        void (*apply)(ChannelStrip&);
        int bus;  // 0 mix, 1 delay, 2 reverb
        bool right;
    };
    const Change changes[] = {
        {"volume", [](ChannelStrip& s) { s.setVolumeDb(-60.0f); }, 0, false},
        {"volume up", [](ChannelStrip& s) { s.setVolumeDb(6.0f); }, 0, false},
        {"pan", [](ChannelStrip& s) { s.setPan(1.0f); }, 0, true},
        {"delay send", [](ChannelStrip& s) { s.setSends(1.0f, 0.0f); }, 1, false},
        {"reverb send", [](ChannelStrip& s) { s.setSends(0.0f, 1.0f); }, 2, true},
    };
    for (const Change& c : changes) {
        auto s = makeStrip();
        const StripOut before = settled(*s);
        c.apply(*s);
        const StripOut o = runStrip(*s, dc(frames(0.03)));
        const auto pick = [&](const StripOut& x) -> const std::vector<float>& {
            const Stereo& b = c.bus == 0 ? x.mix : (c.bus == 1 ? x.delay : x.reverb);
            return c.right ? b.r : b.l;
        };
        const std::vector<float>& y = pick(o);
        const float start = pick(before).back();
        const float end = y.back();
        const float span = std::fabs(end - start);
        ASSERT_GT(span, 0.1f) << c.name;
        const float at10ms = y[static_cast<std::size_t>(frames(0.01))];
        EXPECT_LT(std::fabs(at10ms - end), 0.01f * span) << c.name;
        EXPECT_GT(std::fabs(y[static_cast<std::size_t>(frames(0.001))] - end), 0.3f * span) << c.name << " too fast";
        EXPECT_LT(std::max(std::fabs(y[0] - start), maxStep(y)), 0.02f * span) << c.name;
    }
}

TEST(ChannelStrip, RepeatedSettersWithTheSameValueChangeNothing) {
    auto a = makeStrip();
    auto b = makeStrip();
    for (auto* s : {a.get(), b.get()}) {
        s->setVolumeDb(-3.0f);
        s->setPan(0.2f);
        s->setSends(0.3f, 0.6f);
    }
    const Stereo in = noise(frames(0.2), 0.5f, 5u);
    const StripOut ra = runStrip(*a, in, 96);
    StripOut rb;
    rb.mix = Stereo(in.size());
    rb.delay = Stereo(in.size());
    rb.reverb = Stereo(in.size());
    for (int done = 0; done < static_cast<int>(in.size()); done += 96) {
        const int k = std::min(96, static_cast<int>(in.size()) - done);
        b->setVolumeDb(-3.0f);
        b->setPan(0.2f);
        b->setSends(0.3f, 0.6f);
        b->setMute(false);
        b->process(in.l.data() + done, in.r.data() + done, k, rb.mix.l.data() + done, rb.mix.r.data() + done,
                   rb.delay.l.data() + done, rb.delay.r.data() + done, rb.reverb.l.data() + done, rb.reverb.r.data() + done);
    }
    EXPECT_EQ(ra.mix.l, rb.mix.l);
    EXPECT_EQ(ra.reverb.r, rb.reverb.r);
}

TEST(ChannelStrip, AnyBlockSizeGivesIdenticalOutput) {
    const Stereo in = noise(frames(0.1), 0.7f, 51u);
    auto render = [&](int block) {
        auto s = makeStrip();
        s->setSends(0.2f, 0.4f);
        StripOut a = runStrip(*s, in, block);
        s->setVolumeDb(-12.0f);
        s->setPan(-0.6f);
        s->setSends(0.9f, 0.0f);
        s->setMute(true);
        StripOut b = runStrip(*s, in, block);
        s->setMute(false);
        StripOut c = runStrip(*s, in, block);
        append(a.mix, b.mix);
        append(a.mix, c.mix);
        append(a.delay, b.delay);
        append(a.delay, c.delay);
        append(a.reverb, b.reverb);
        append(a.reverb, c.reverb);
        return a;
    };
    const StripOut ref = render(96);
    for (int block : {1, 3, 64, 1000, 4800}) {
        const StripOut o = render(block);
        EXPECT_EQ(o.mix.l, ref.mix.l) << block;
        EXPECT_EQ(o.mix.r, ref.mix.r) << block;
        EXPECT_EQ(o.delay.l, ref.delay.l) << block;
        EXPECT_EQ(o.reverb.r, ref.reverb.r) << block;
    }
}

TEST(ChannelStrip, ProcessAndSettersNeverAllocate) {
    auto s = makeStrip();
    std::vector<float> in(4096, 0.5f), ml(4096), mr(4096), dl(4096), dr(4096), rl(4096), rr(4096);
    groove::test::AllocationScope scope;
    for (int i = 0; i < 5000; ++i) {
        s->setVolumeDb(-70.0f + static_cast<float>(i % 77));
        s->setPan(static_cast<float>(i % 21) / 10.0f - 1.0f);
        s->setMute(i % 7 == 0);
        s->setSends(static_cast<float>(i % 5) / 4.0f, static_cast<float>(i % 3) / 2.0f);
        const int32_t n = (i % 13 == 0) ? 4096 : 1 + (i * 31) % 300;
        if (i % 4 == 0) {
            s->process(in.data(), in.data(), n, ml.data(), mr.data(), nullptr, nullptr, nullptr, nullptr);
        } else {
            s->process(in.data(), in.data(), n, ml.data(), mr.data(), dl.data(), dr.data(), rl.data(), rr.data());
        }
    }
    EXPECT_EQ(scope.count(), 0u);
}

TEST(ChannelStrip, NonFiniteParamsAreIgnored) {
    auto s = makeStrip();
    s->setVolumeDb(std::nanf(""));
    s->setPan(std::nanf(""));
    s->setSends(std::nanf(""), std::nanf(""));
    const StripOut o = settled(*s);
    EXPECT_TRUE(allFinite(o.mix.l) && allFinite(o.delay.l) && allFinite(o.reverb.r));
    EXPECT_NEAR(o.mix.l.back(), kCentre, 1e-5f);  // NaN maps to the parameter's default
}

// ============================================================================================
// Cost: delay + reverb + 12 strips, the M3 mixer's FX load.
// ============================================================================================

TEST(FxBlocks, CostOfTheFullMixerAndFx) {
    constexpr int kBlock = 96;
    constexpr double kSeconds = 4.0;
    auto delay = makeDelay();
    delay->setFeedback(0.6f);
    auto reverb = makeReverb(0.8f, 6.0f, 0.4f);
    std::vector<std::unique_ptr<ChannelStrip>> strips;
    for (int c = 0; c < kMixerChannelCount; ++c) {
        strips.push_back(makeStrip());
        strips.back()->setSends(0.3f, 0.5f);
    }
    const Stereo src = noise(kBlock, 0.3f, 1u);
    Stereo mix(kBlock), dly(kBlock), rev(kBlock), dOut(kBlock), rOut(kBlock);
    const int blocks = static_cast<int>(kSeconds * kFs / kBlock);
    const auto start = std::chrono::steady_clock::now();
    for (int b = 0; b < blocks; ++b) {
        for (Stereo* s : {&mix, &dly, &rev}) {
            std::fill(s->l.begin(), s->l.end(), 0.0f);
            std::fill(s->r.begin(), s->r.end(), 0.0f);
        }
        for (int c = 0; c < kMixerChannelCount; ++c) {
            strips[static_cast<std::size_t>(c)]->setVolumeDb(-3.0f);  // per-chunk setter traffic, as the graph does
            const bool tape = c >= kFirstTapeChannel;
            strips[static_cast<std::size_t>(c)]->process(src.l.data(), src.r.data(), kBlock, mix.l.data(), mix.r.data(),
                                                         tape ? nullptr : dly.l.data(), tape ? nullptr : dly.r.data(),
                                                         tape ? nullptr : rev.l.data(), tape ? nullptr : rev.r.data());
        }
        delay->process(dly.l.data(), dly.r.data(), dOut.l.data(), dOut.r.data(), kBlock);
        reverb->process(rev.l.data(), rev.r.data(), rOut.l.data(), rOut.r.data(), kBlock);
    }
    const double cpu = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    const double factor = kSeconds / cpu;
    std::printf("FX + 12 strips: %.0fx realtime in this build%s\n", factor, kSanitized ? " (sanitized)" : "");
    EXPECT_TRUE(allFinite(rOut.l) && allFinite(dOut.l));
    if (!kSanitized) EXPECT_GT(factor, 5.0);
}
