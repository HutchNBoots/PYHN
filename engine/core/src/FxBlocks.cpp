// Master FX and mixer channel blocks (docs/feature-spec.md F6.1, F6.2, F6.4).
//
// Real-time rules (CLAUDE.md): only prepare() allocates (the delay lines, sized once for the
// sample rate). Setters and process() run on the audio thread: no allocation, locks, logging
// or I/O, and no libm calls inside the per-sample loops (the setters and the reverb's
// 32-sample control tick use tan/exp/pow/sin/cos). All state advances per sample (or on a
// tick counted in samples), so any split into process() calls gives bit-identical output.
// Feedback paths flush values below 1e-20 to zero, so silence never decays into denormals.

#include "groove/FxBlocks.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

#include "groove/DspMath.h"
#include "groove/Filters.h"
#include "groove/MixParams.h"

namespace groove {

namespace {

constexpr float kDefaultRate = 48000.0f;

float validRate(float fs) { return (fs >= 8000.0f && fs <= 384000.0f) ? fs : kDefaultRate; }  // NaN fails

// Per-sample coefficient of a one-pole smoother with time constant tau.
float smoothingCoefficient(float tauSeconds, float fs) { return 1.0f - std::exp(-1.0f / (tauSeconds * fs)); }

// One step of a one-pole smoother that lands exactly on its target: in float the step d * k
// eventually rounds away (near 1 that happens about 3e-6 short of the target) or heads for
// denormals near 0, so it snaps once the step stops moving it or the gap is negligible.
inline void smooth(float& now, float target, float k) {
    const float d = target - now;
    const float next = now + d * k;
    now = (next == now || std::fabs(d) < 1.0e-7f) ? target : next;
}

// Non-finite or absurd input becomes silence rather than poisoning a feedback loop.
inline float finiteOrZero(float x) { return std::fabs(x) <= 1.0e6f ? x : 0.0f; }
inline float flushTiny(float x) { return std::fabs(x) < 1.0e-20f ? 0.0f : x; }

// TPT one-pole low-pass step with coefficient G = g / (1 + g) (see Filters.h).
// The state is flushed so a decaying loop never leaves denormals in it.
inline float onePoleLowPass(float x, float g, float& s) {
    const float v = (x - s) * g;
    const float y = v + s;
    s = flushTiny(y + v);
    return y;
}

uint32_t nextPowerOfTwo(uint32_t v) {
    uint32_t p = 1;
    while (p < v) p <<= 1;
    return p;
}

float param(MixParamId id, float value) { return sanitize(mixParamInfo(id), value); }

// ---------------------------------------------------------------------------------------------
// StereoDelay constants
// ---------------------------------------------------------------------------------------------

// Delay-time fractions of a whole note, in MixParams.def dly.div order.
constexpr float kDivisionWholeNotes[] = {1.0f / 24.0f, 1.0f / 16.0f, 3.0f / 32.0f, 1.0f / 12.0f,
                                         1.0f / 8.0f,  3.0f / 16.0f, 1.0f / 6.0f,  1.0f / 4.0f,
                                         3.0f / 8.0f,  1.0f / 3.0f,  1.0f / 2.0f};
constexpr int kDivisionCount = static_cast<int>(sizeof(kDivisionWholeNotes) / sizeof(kDivisionWholeNotes[0]));
static_assert(mixParamInfo(MixParamId::DelayDivision).max == static_cast<float>(kDivisionCount - 1),
              "dly.div options and the division table must match");

constexpr float kMinDelaySeconds = 0.01f;
constexpr float kMaxDelaySeconds = 2.0f;
// Time glide: exponential approach (80 ms) with the read-head speed limited to +-0.8 of the
// write speed (a pitch bend between 0.2x and 1.8x while gliding), and the speed itself
// smoothed over 10 ms so the bend eases in and out. Overdamped, so it doesn't overshoot.
constexpr double kGlideSeconds = 0.08;
constexpr double kGlideRateSeconds = 0.01;
constexpr double kMaxGlideRate = 0.8;
constexpr float kDelayParamSeconds = 0.005f;  // feedback and tone smoothing (F0.7)
// Each repeat hands 15% of a side to the other: the image stays put on the first echo and
// widens a little on the repeats. Mono stays mono, and the 2x2 mix has gains 1 and 0.7, so it
// can only lose energy.
constexpr float kCrossFeed = 0.15f;
// The loop's soft saturation: linear for normal levels (-0.1 dB at 0.4), and bounded at
// +-kSaturation whatever the filters, the interpolation or the input do.
constexpr float kSaturation = 1.5f;

// Catmull-Rom (4-point cubic Hermite) interpolation between y0 and y1, t in [0, 1).
inline float hermite(float ym1, float y0, float y1, float y2, float t) {
    const float c1 = 0.5f * (y1 - ym1);
    const float c2 = ym1 - 2.5f * y0 + 2.0f * y1 - 0.5f * y2;
    const float c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1);
    return ((c3 * t + c2) * t + c1) * t + y0;
}

// ---------------------------------------------------------------------------------------------
// Reverb constants
// ---------------------------------------------------------------------------------------------

constexpr int kLines = 8;
// Line lengths at Size 1 and 48 kHz (37..85 ms, spread roughly geometrically). Size scales
// them by 0.25..1; each is then moved to a prime, all distinct, so they are mutually prime.
constexpr float kLineSamples48k[kLines] = {1777.0f, 2053.0f, 2371.0f, 2693.0f, 3049.0f, 3407.0f, 3727.0f, 4079.0f};
constexpr float kMinSizeScale = 0.25f;
// Injection signs (into the lines) and output tap signs: the two tap vectors are rows of a
// Hadamard matrix, orthogonal to each other, so L and R come out decorrelated.
constexpr float kInSign[kLines] = {1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f, -1.0f, -1.0f};
constexpr float kTapL[kLines] = {1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f};
constexpr float kTapR[kLines] = {1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, -1.0f};
// The lines are not modulated: a fractional read (any interpolator short of an all-pass)
// loses highs on every trip round the loop and would shorten the decay. The lengths are
// integers except while Size glides, so the set RT60 holds exactly.

// Input diffusion: four Schroeder all-passes on the mono sum.
constexpr int kDiffusers = 4;
constexpr float kDiffuserSamples48k[kDiffusers] = {131.0f, 191.0f, 263.0f, 347.0f};
constexpr float kDiffusion = 0.6f;
// Damping: a high-shelf per line from this crossover, so the mid band keeps the set decay
// and the top decays faster: at Damping 1 the top's RT60 is 0.2x the set value.
constexpr float kDampingCrossoverHz = 6000.0f;
constexpr float kMaxDampingLoss = 0.8f;
// Control tick: lengths and gains are computed every 32 samples and ramped
// linearly in between.
constexpr int kTick = 32;
constexpr float kSizeGlideSeconds = 0.1f;
constexpr float kMaxSizeRate = 0.1f;  // line-length change per sample while gliding (<10% pitch)
constexpr float kReverbParamSeconds = 0.003f;  // decay and damping smoothing (F0.7)
// About -3 dB of steady-state noise gain at the defaults (longer decays are louder: about
// +5 dB at 20 s and Size 1).
constexpr float kInputGain = 1.0f;
constexpr float kOutputGain = 0.6f;

bool isPrime(int n) {
    if (n < 2) return false;
    if (n % 2 == 0) return n == 2;
    for (int d = 3; d * d <= n; d += 2)
        if (n % d == 0) return false;
    return true;
}

int nextPrimeAtLeast(int n) {
    while (!isPrime(n)) ++n;
    return n;
}

// ---------------------------------------------------------------------------------------------
// ChannelStrip constants
// ---------------------------------------------------------------------------------------------

constexpr float kStripSeconds = 0.002f;  // one-pole: within 0.7% of the target after 10 ms (F0.7)
constexpr float kMuteFadeSeconds = 0.005f;  // F6.4

float channelParam(ChannelParamId id, float value) { return sanitize(channelParamInfo(id), value); }

}  // namespace

// =============================================================================================
// StereoDelay
// =============================================================================================

struct StereoDelay::Impl {
    float fs = kDefaultRate;
    std::vector<float> line[2];
    uint32_t mask = 0;  // 0 until prepared
    uint32_t write = 0;
    double maxDelay = 0.0;

    // Parameters as set.
    float bpm = 120.0f;
    bool sync = mixParamInfo(MixParamId::DelaySync).def >= 0.5f;
    int division = static_cast<int>(mixParamInfo(MixParamId::DelayDivision).def);
    float freeSeconds = mixParamInfo(MixParamId::DelayTime).def;
    float feedback = mixParamInfo(MixParamId::DelayFeedback).def;
    float lowCutHz = mixParamInfo(MixParamId::DelayLowCut).def;
    float highCutHz = mixParamInfo(MixParamId::DelayHighCut).def;

    // Targets and their smoothed values.
    double delayTarget = 0.0, delayNow = 0.0, delayRate = 0.0;
    double glideK = 0.0, glideRateK = 0.0;
    float fbTarget = 0.0f, fbNow = 0.0f;
    float hpGTarget = 0.0f, hpGNow = 0.0f;
    float lpGTarget = 0.0f, lpGNow = 0.0f;
    float paramK = 0.0f;
    float hpState[2] = {0.0f, 0.0f};
    float lpState[2] = {0.0f, 0.0f};

    void updateTime() {
        const float seconds = sync ? kDivisionWholeNotes[division] * 240.0f / bpm : freeSeconds;
        delayTarget = static_cast<double>(std::clamp(seconds, kMinDelaySeconds, kMaxDelaySeconds)) * fs;
    }
    void updateFeedback() { fbTarget = feedback; }
    void updateTone() {
        hpGTarget = onePoleCoefficient(lowCutHz, fs);
        lpGTarget = onePoleCoefficient(highCutHz, fs);
    }
    void snap() {
        delayNow = delayTarget;
        delayRate = 0.0;
        fbNow = fbTarget;
        hpGNow = hpGTarget;
        lpGNow = lpGTarget;
    }
    void clear() {
        for (auto& l : line) std::fill(l.begin(), l.end(), 0.0f);
        hpState[0] = hpState[1] = lpState[0] = lpState[1] = 0.0f;
        write = 0;
    }
};

StereoDelay::StereoDelay() : impl_(new Impl()) {
    impl_->updateTime();
    impl_->updateFeedback();
    impl_->updateTone();
    impl_->snap();
}

StereoDelay::~StereoDelay() { delete impl_; }

void StereoDelay::prepare(float sampleRate) {
    Impl& d = *impl_;
    d.fs = validRate(sampleRate);
    // 2 s plus room for the interpolator's taps and the glide's tiny overshoot.
    const uint32_t size = nextPowerOfTwo(static_cast<uint32_t>(kMaxDelaySeconds * d.fs) + 64u);
    for (auto& l : d.line) l.assign(size, 0.0f);
    d.mask = size - 1u;
    d.maxDelay = static_cast<double>(size - 8u);
    d.glideK = 1.0 / (kGlideSeconds * d.fs);
    d.glideRateK = 1.0 / (kGlideRateSeconds * d.fs);
    d.paramK = smoothingCoefficient(kDelayParamSeconds, d.fs);
    d.updateTime();
    d.updateTone();
    d.updateFeedback();
    reset();
}

void StereoDelay::reset() {
    impl_->clear();
    impl_->snap();
}

void StereoDelay::setTempo(float bpm) {
    if (!(bpm >= 1.0f)) return;  // NaN or nonsense keeps the last tempo
    impl_->bpm = std::min(bpm, 1000.0f);
    impl_->updateTime();
}

void StereoDelay::setTime(bool sync, int division, float timeSeconds) {
    Impl& d = *impl_;
    d.sync = sync;
    d.division = std::clamp(division, 0, kDivisionCount - 1);
    d.freeSeconds = param(MixParamId::DelayTime, timeSeconds);
    d.updateTime();
}

void StereoDelay::setFeedback(float feedback) {
    impl_->feedback = param(MixParamId::DelayFeedback, feedback);
    impl_->updateFeedback();
}

void StereoDelay::setTone(float lowCutHz, float highCutHz) {
    Impl& d = *impl_;
    const float lo = param(MixParamId::DelayLowCut, lowCutHz);
    const float hi = param(MixParamId::DelayHighCut, highCutHz);
    if (lo == d.lowCutHz && hi == d.highCutHz) return;  // the graph re-sends every chunk
    d.lowCutHz = lo;
    d.highCutHz = hi;
    d.updateTone();
}

void StereoDelay::process(const float* inL, const float* inR, float* outL, float* outR, int32_t numFrames) {
    Impl& d = *impl_;
    if (d.mask == 0) {
        for (int32_t i = 0; i < numFrames; ++i) outL[i] = outR[i] = 0.0f;
        return;
    }
    float* const bufL = d.line[0].data();
    float* const bufR = d.line[1].data();
    const uint32_t mask = d.mask;
    uint32_t w = d.write;
    double delayNow = d.delayNow, delayRate = d.delayRate;
    const double delayTarget = d.delayTarget;
    float fb = d.fbNow, hpG = d.hpGNow, lpG = d.lpGNow;
    float hpL = d.hpState[0], hpR = d.hpState[1], lpL = d.lpState[0], lpR = d.lpState[1];
    const float k = d.paramK;
    const float invSat = 1.0f / kSaturation;

    for (int32_t i = 0; i < numFrames; ++i) {
        const float xl = finiteOrZero(inL[i]);
        const float xr = finiteOrZero(inR[i]);

        if (delayRate != 0.0 || delayNow != delayTarget) {
            const double desired = std::clamp((delayTarget - delayNow) * d.glideK, -kMaxGlideRate, kMaxGlideRate);
            delayRate += (desired - delayRate) * d.glideRateK;
            delayNow = std::clamp(delayNow + delayRate, 2.0, d.maxDelay);
            if (std::fabs(delayTarget - delayNow) < 1.0e-3 && std::fabs(delayRate) < 1.0e-5) {
                delayNow = delayTarget;
                delayRate = 0.0;
            }
        }
        smooth(fb, d.fbTarget, k);
        smooth(hpG, d.hpGTarget, k);
        smooth(lpG, d.lpGTarget, k);

        // Read delayNow samples behind the write head (which holds the oldest sample).
        const int32_t whole = static_cast<int32_t>(delayNow);
        const float frac = static_cast<float>(delayNow - static_cast<double>(whole));
        const uint32_t i0 = (w - static_cast<uint32_t>(whole)) & mask;
        const uint32_t im1 = (i0 + 1u) & mask;
        const uint32_t i1 = (i0 - 1u) & mask;
        const uint32_t i2 = (i0 - 2u) & mask;
        const float yl = hermite(bufL[im1], bufL[i0], bufL[i1], bufL[i2], frac);
        const float yr = hermite(bufR[im1], bufR[i0], bufR[i1], bufR[i2], frac);

        // Tone: low cut (one-pole high-pass), then high cut (one-pole low-pass).
        const float hl = yl - onePoleLowPass(yl, hpG, hpL);
        const float hr = yr - onePoleLowPass(yr, hpG, hpR);
        const float tl = onePoleLowPass(hl, lpG, lpL);
        const float tr = onePoleLowPass(hr, lpG, lpR);
        outL[i] = tl;
        outR[i] = tr;

        const float fl = (1.0f - kCrossFeed) * tl + kCrossFeed * tr;
        const float fr = (1.0f - kCrossFeed) * tr + kCrossFeed * tl;
        bufL[w] = flushTiny(xl + fb * kSaturation * dsp::softClip(fl * invSat));
        bufR[w] = flushTiny(xr + fb * kSaturation * dsp::softClip(fr * invSat));
        w = (w + 1u) & mask;
    }

    d.write = w;
    d.delayNow = delayNow;
    d.delayRate = delayRate;
    d.fbNow = fb;
    d.hpGNow = hpG;
    d.lpGNow = lpG;
    d.hpState[0] = hpL;
    d.hpState[1] = hpR;
    d.lpState[0] = lpL;
    d.lpState[1] = lpR;
}

// =============================================================================================
// Reverb: 8-line feedback delay network
//
//   in L+R -> 4 series all-passes -> injected into 8 delay lines (signs kInSign)
//   each line: read (interpolated only while Size glides) -> high-shelf damping with RT60 gains
//   -> outputs tapped (kTapL / kTapR) -> 8x8 Hadamard (orthogonal, lossless) -> written back
//
// Per-line gains: g = 10^(-3 L / (fs T60)) for a line of L samples, so every path through the
// network loses 60 dB in T60 seconds in the mid band. The shelf's top gain uses T60 * hf,
// hf = 1 - 0.8 * Damping. Both gains are below 1 and the matrix is orthogonal, so the network
// is stable for every setting, during changes too.
// =============================================================================================

struct Reverb::Impl {
    float fs = kDefaultRate;
    std::vector<float> lines;      // kLines x lineSize
    std::vector<float> diffusers;  // kDiffusers x diffuserSize
    uint32_t lineSize = 0, lineMask = 0;  // 0 until prepared
    uint32_t diffuserSize = 0, diffuserMask = 0;
    uint32_t write = 0, diffuserWrite = 0;
    uint32_t diffuserDelay[kDiffusers] = {};

    float size = mixParamInfo(MixParamId::ReverbSize).def;
    float decay = mixParamInfo(MixParamId::ReverbDecay).def;
    float damping = mixParamInfo(MixParamId::ReverbDamping).def;

    // Size: integer prime targets, glided base lengths.
    float lengthTarget[kLines] = {};
    float baseLength[kLines] = {};
    // Decay and damping, smoothed per tick: log(T60) and the shelf's top ratio.
    float logDecayTarget = 0.0f, logDecayNow = 0.0f;
    float hfTarget = 1.0f, hfNow = 1.0f;

    // Per-tick linear ramps: value = start + step * j for j = 0..kTick-1.
    int tickPos = 0;
    float lenStart[kLines] = {}, lenStep[kLines] = {};
    float loStart[kLines] = {}, loStep[kLines] = {};  // mid-band gain
    float hiStart[kLines] = {}, hiStep[kLines] = {};  // top gain
    float dampState[kLines] = {};
    float dampG = 0.0f;

    float sizeK = 0.0f, paramK = 0.0f, maxLenStepPerTick = 0.0f;
    bool idle = true;  // every ramp sits at its final value: ticks have nothing to compute

    void updateSize() {
        const float scale = (kMinSizeScale + (1.0f - kMinSizeScale) * size) * fs / 48000.0f;
        int previous = 0;
        for (int i = 0; i < kLines; ++i) {
            int n = nextPrimeAtLeast(std::max(static_cast<int>(std::lround(kLineSamples48k[i] * scale)), previous + 1));
            lengthTarget[i] = static_cast<float>(n);
            previous = n;
        }
    }
    void updateDecay() { logDecayTarget = std::log(decay); }
    void updateDamping() { hfTarget = 1.0f - kMaxDampingLoss * damping; }

    // Mid-band and top gains for a line of `length` samples.
    void gainsFor(float length, float& lo, float& hi) const {
        const float t60 = std::exp(logDecayNow);
        const float perSample = -6.907755f / (fs * t60);  // ln(10^-3)
        lo = std::exp(perSample * length);
        hi = std::exp(perSample * length / hfNow);
    }

    // Computes the values the ramps reach at the end of the coming tick.
    void tick() {
        smooth(logDecayNow, logDecayTarget, paramK);
        smooth(hfNow, hfTarget, paramK);
        bool still = logDecayNow == logDecayTarget && hfNow == hfTarget;
        for (int i = 0; i < kLines; ++i) {
            const float diff = lengthTarget[i] - baseLength[i];
            if (std::fabs(diff) < 1.0e-3f) {
                baseLength[i] = lengthTarget[i];
            } else {
                baseLength[i] += std::clamp(diff * sizeK, -maxLenStepPerTick, maxLenStepPerTick);
                still = false;
            }
        }
        if (still && idle) {
            for (int i = 0; i < kLines; ++i) lenStep[i] = loStep[i] = hiStep[i] = 0.0f;
            return;
        }
        idle = still;  // this tick's ramps end on the final values
        for (int i = 0; i < kLines; ++i) {
            const float lenEnd = baseLength[i];
            float lo = 0.0f, hi = 0.0f;
            gainsFor(baseLength[i], lo, hi);
            const float inv = 1.0f / static_cast<float>(kTick);
            lenStep[i] = (lenEnd - lenStart[i]) * inv;
            loStep[i] = (lo - loStart[i]) * inv;
            hiStep[i] = (hi - hiStart[i]) * inv;
        }
    }
    // Moves the ramp starts to the values reached at the end of a tick.
    void endTick() {
        for (int i = 0; i < kLines; ++i) {
            lenStart[i] += lenStep[i] * static_cast<float>(kTick);
            loStart[i] += loStep[i] * static_cast<float>(kTick);
            hiStart[i] += hiStep[i] * static_cast<float>(kTick);
        }
    }

    // Lands every smoothed value on its target (fresh start).
    void snap() {
        tickPos = 0;
        idle = true;
        logDecayNow = logDecayTarget;
        hfNow = hfTarget;
        for (int i = 0; i < kLines; ++i) {
            baseLength[i] = lengthTarget[i];
            lenStart[i] = baseLength[i];
            gainsFor(baseLength[i], loStart[i], hiStart[i]);
            lenStep[i] = loStep[i] = hiStep[i] = 0.0f;
        }
    }
    void clear() {
        std::fill(lines.begin(), lines.end(), 0.0f);
        std::fill(diffusers.begin(), diffusers.end(), 0.0f);
        for (float& s : dampState) s = 0.0f;
        write = diffuserWrite = 0;
    }
};

Reverb::Reverb() : impl_(new Impl()) {
    impl_->updateSize();
    impl_->updateDecay();
    impl_->updateDamping();
    impl_->snap();
}

Reverb::~Reverb() { delete impl_; }

void Reverb::prepare(float sampleRate) {
    Impl& r = *impl_;
    r.fs = validRate(sampleRate);
    const float ratio = r.fs / 48000.0f;
    const float longest = kLineSamples48k[kLines - 1] * ratio + 64.0f;  // room for the primes and the taps
    r.lineSize = nextPowerOfTwo(static_cast<uint32_t>(longest) + 4u);
    r.lineMask = r.lineSize - 1u;
    r.lines.assign(static_cast<std::size_t>(r.lineSize) * kLines, 0.0f);
    uint32_t longestDiffuser = 0;
    for (int i = 0; i < kDiffusers; ++i) {
        r.diffuserDelay[i] = static_cast<uint32_t>(std::lround(kDiffuserSamples48k[i] * ratio));
        longestDiffuser = std::max(longestDiffuser, r.diffuserDelay[i]);
    }
    r.diffuserSize = nextPowerOfTwo(longestDiffuser + 1u);
    r.diffuserMask = r.diffuserSize - 1u;
    r.diffusers.assign(static_cast<std::size_t>(r.diffuserSize) * kDiffusers, 0.0f);
    r.dampG = onePoleCoefficient(kDampingCrossoverHz, r.fs);
    r.sizeK = 1.0f - std::exp(-static_cast<float>(kTick) / (kSizeGlideSeconds * r.fs));
    r.paramK = 1.0f - std::exp(-static_cast<float>(kTick) / (kReverbParamSeconds * r.fs));
    r.maxLenStepPerTick = kMaxSizeRate * static_cast<float>(kTick);
    r.updateSize();
    r.updateDecay();
    r.updateDamping();
    reset();
}

void Reverb::reset() {
    impl_->clear();
    impl_->snap();
}

void Reverb::setSize(float size) {
    const float s = param(MixParamId::ReverbSize, size);
    if (s == impl_->size) return;  // the graph re-sends every chunk; primes cost a little
    impl_->size = s;
    impl_->updateSize();
}

void Reverb::setDecaySeconds(float seconds) {
    impl_->decay = param(MixParamId::ReverbDecay, seconds);
    impl_->updateDecay();
}

void Reverb::setDamping(float damping) {
    impl_->damping = param(MixParamId::ReverbDamping, damping);
    impl_->updateDamping();
}

void Reverb::process(const float* inL, const float* inR, float* outL, float* outR, int32_t numFrames) {
    Impl& r = *impl_;
    if (r.lineMask == 0) {
        for (int32_t i = 0; i < numFrames; ++i) outL[i] = outR[i] = 0.0f;
        return;
    }
    float* const lines = r.lines.data();
    float* const diff = r.diffusers.data();
    const uint32_t lineMask = r.lineMask, lineSize = r.lineSize;
    const uint32_t dMask = r.diffuserMask, dSize = r.diffuserSize;
    const float dampG = r.dampG;
    const float tapScale = kOutputGain / std::sqrt(static_cast<float>(kLines));
    const float hadamardScale = 1.0f / std::sqrt(static_cast<float>(kLines));
    uint32_t w = r.write, dw = r.diffuserWrite;

    for (int32_t n = 0; n < numFrames; ++n) {
        if (r.tickPos == 0) r.tick();
        const float j = static_cast<float>(r.tickPos);

        // Input diffusion on the mono sum.
        float x = kInputGain * 0.5f * (finiteOrZero(inL[n]) + finiteOrZero(inR[n]));
        for (int a = 0; a < kDiffusers; ++a) {
            float* buf = diff + static_cast<std::size_t>(a) * dSize;
            const float delayed = buf[(dw - r.diffuserDelay[a]) & dMask];
            const float v = flushTiny(x + kDiffusion * delayed);
            buf[dw] = v;
            x = delayed - kDiffusion * v;
        }

        // Read and damp every line.
        float y[kLines];
        for (int i = 0; i < kLines; ++i) {
            const float* buf = lines + static_cast<std::size_t>(i) * lineSize;
            const float len = r.lenStart[i] + r.lenStep[i] * j;
            const int32_t whole = static_cast<int32_t>(len);
            const float frac = len - static_cast<float>(whole);
            const uint32_t ia = (w - static_cast<uint32_t>(whole)) & lineMask;
            const float a = buf[ia];
            const float b = buf[(ia - 1u) & lineMask];
            const float s = a + frac * (b - a);
            const float lo = r.loStart[i] + r.loStep[i] * j;
            const float hi = r.hiStart[i] + r.hiStep[i] * j;
            // High shelf: hi above the crossover, lo below.
            y[i] = hi * s + (lo - hi) * onePoleLowPass(s, dampG, r.dampState[i]);
        }

        float l = 0.0f, rr = 0.0f;
        for (int i = 0; i < kLines; ++i) {
            l += kTapL[i] * y[i];
            rr += kTapR[i] * y[i];
        }
        outL[n] = l * tapScale;
        outR[n] = rr * tapScale;

        // Fast Walsh-Hadamard transform (orthonormal after scaling).
        for (int h = 1; h < kLines; h <<= 1) {
            for (int i = 0; i < kLines; i += h << 1) {
                for (int k = i; k < i + h; ++k) {
                    const float u = y[k], v = y[k + h];
                    y[k] = u + v;
                    y[k + h] = u - v;
                }
            }
        }
        for (int i = 0; i < kLines; ++i) {
            float* buf = lines + static_cast<std::size_t>(i) * lineSize;
            buf[w] = flushTiny(y[i] * hadamardScale + kInSign[i] * x);
        }

        w = (w + 1u) & lineMask;
        dw = (dw + 1u) & dMask;
        if (++r.tickPos == kTick) {
            r.tickPos = 0;
            r.endTick();
        }
    }
    r.write = w;
    r.diffuserWrite = dw;
}

// =============================================================================================
// ChannelStrip
// =============================================================================================

struct ChannelStrip::Impl {
    float k = 0.0f;         // smoother coefficient
    float muteStep = 0.0f;  // per-sample step of the 5 ms mute ramp

    float volTarget = 1.0f, vol = 1.0f;
    float panLTarget = 0.0f, panL = 0.0f, panRTarget = 0.0f, panR = 0.0f;
    float dlyTarget = 0.0f, dly = 0.0f, revTarget = 0.0f, rev = 0.0f;
    float open = 1.0f, openTarget = 1.0f;  // the mute ramp: 1 = playing, 0 = muted

    bool settled() const {
        return vol == volTarget && panL == panLTarget && panR == panRTarget && dly == dlyTarget && rev == revTarget &&
               open == openTarget;
    }
};

ChannelStrip::ChannelStrip() : impl_(new Impl()) {
    setVolumeDb(channelParamInfo(ChannelParamId::Volume).def);
    setPan(channelParamInfo(ChannelParamId::Pan).def);
    setMute(channelParamInfo(ChannelParamId::Mute).def >= 0.5f);
    setSends(channelParamInfo(ChannelParamId::DelaySend).def, channelParamInfo(ChannelParamId::ReverbSend).def);
    prepare(kDefaultRate);
}

ChannelStrip::~ChannelStrip() { delete impl_; }

void ChannelStrip::prepare(float sampleRate) {
    Impl& s = *impl_;
    const float fs = validRate(sampleRate);
    s.k = smoothingCoefficient(kStripSeconds, fs);
    s.muteStep = 1.0f / (kMuteFadeSeconds * fs);
    // Start at the settings: no fade-in.
    s.vol = s.volTarget;
    s.panL = s.panLTarget;
    s.panR = s.panRTarget;
    s.dly = s.dlyTarget;
    s.rev = s.revTarget;
    s.open = s.openTarget;
}

void ChannelStrip::setVolumeDb(float db) { impl_->volTarget = dbToGain(channelParam(ChannelParamId::Volume, db)); }

void ChannelStrip::setPan(float pan) {
    const float p = channelParam(ChannelParamId::Pan, pan);
    const float theta = (p + 1.0f) * (dsp::kPi / 4.0f);
    // Exact zeros at the extremes (cos(pi/2) in float is -4e-8, not 0).
    impl_->panLTarget = p >= 1.0f ? 0.0f : std::cos(theta);
    impl_->panRTarget = p <= -1.0f ? 0.0f : std::sin(theta);
}

void ChannelStrip::setMute(bool muted) { impl_->openTarget = muted ? 0.0f : 1.0f; }

void ChannelStrip::setSends(float delaySend, float reverbSend) {
    impl_->dlyTarget = channelParam(ChannelParamId::DelaySend, delaySend);
    impl_->revTarget = channelParam(ChannelParamId::ReverbSend, reverbSend);
}

void ChannelStrip::process(const float* inL, const float* inR, int32_t numFrames, float* mixL, float* mixR,
                           float* delayL, float* delayR, float* reverbL, float* reverbR) {
    Impl& s = *impl_;
    const bool sendDelay = delayL != nullptr && delayR != nullptr;
    const bool sendReverb = reverbL != nullptr && reverbR != nullptr;

    if (s.settled()) {
        // Constant gains: the same arithmetic as the smoothing loop below, so output doesn't
        // depend on where a block boundary falls.
        const float m = s.open * s.open * (3.0f - 2.0f * s.open);
        const float gl = s.vol * m * s.panL;
        const float gr = s.vol * m * s.panR;
        if (gl == 0.0f && gr == 0.0f) return;  // adds nothing
        for (int32_t i = 0; i < numFrames; ++i) {
            const float l = inL[i] * gl;
            const float r = inR[i] * gr;
            mixL[i] += l;
            mixR[i] += r;
            if (sendDelay) {
                delayL[i] += l * s.dly;
                delayR[i] += r * s.dly;
            }
            if (sendReverb) {
                reverbL[i] += l * s.rev;
                reverbR[i] += r * s.rev;
            }
        }
        return;
    }

    const float k = s.k;
    for (int32_t i = 0; i < numFrames; ++i) {
        smooth(s.vol, s.volTarget, k);
        smooth(s.panL, s.panLTarget, k);
        smooth(s.panR, s.panRTarget, k);
        smooth(s.dly, s.dlyTarget, k);
        smooth(s.rev, s.revTarget, k);
        if (s.open < s.openTarget) {
            s.open = std::min(s.open + s.muteStep, s.openTarget);
        } else if (s.open > s.openTarget) {
            s.open = std::max(s.open - s.muteStep, s.openTarget);
        }
        // Smoothstep turns the 5 ms linear ramp into an S-curve with no corners.
        const float m = s.open * s.open * (3.0f - 2.0f * s.open);
        const float l = inL[i] * (s.vol * m * s.panL);
        const float r = inR[i] * (s.vol * m * s.panR);
        mixL[i] += l;
        mixR[i] += r;
        if (sendDelay) {
            delayL[i] += l * s.dly;
            delayR[i] += r * s.dly;
        }
        if (sendReverb) {
            reverbL[i] += l * s.rev;
            reverbR[i] += r * s.rev;
        }
    }
}

}  // namespace groove
