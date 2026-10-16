// The built-in kit's sound models (docs/feature-spec.md F2.1). Every sound is an original
// design made from first principles (swept sines, filtered noise, banks of detuned
// square waves); nothing is sampled or modelled on a commercial drum machine. This code
// runs offline in prepare(), never on the audio thread, so it may allocate and call libm.

#include "groove/DrumKit.h"

#include <algorithm>
#include <cmath>
#include <cstddef>

#include "groove/DspMath.h"

namespace groove {

namespace {

constexpr double kPi = 3.14159265358979323846;

// --- offline building blocks (double precision; speed matters only a little) ---

class Noise {
public:
    explicit Noise(uint32_t seed) : rng_(seed) {}
    double next() { return static_cast<double>(rng_.nextBipolar()); }

private:
    dsp::XorShift32 rng_;
};

// Exponential decay env(t) = exp(-t / tau), by recursion.
class Decay {
public:
    Decay(double tau, double fs) : coef_(std::exp(-1.0 / (tau * fs))) {}
    double next() {
        const double out = value_;
        value_ *= coef_;
        if (value_ < 1e-30) value_ = 0.0;  // stay out of denormals (slow, inaudible)
        return out;
    }

private:
    double value_ = 1.0;
    double coef_;
};

// Sine with a per-sample frequency, so pitch sweeps stay phase-continuous.
class Sine {
public:
    explicit Sine(double phase = 0.0) : phase_(phase) {}
    double next(double hz, double fs) {
        const double s = static_cast<double>(dsp::sinCycle(static_cast<float>(phase_)));
        phase_ += hz / fs;
        if (phase_ >= 1.0) phase_ -= std::floor(phase_);
        return s;
    }

private:
    double phase_;
};

// Fixed-frequency sine by rotating a phasor: four multiplies a sample, no libm. Double
// precision keeps the amplitude drift far below audibility over a few seconds.
class Rotor {
public:
    Rotor() = default;
    Rotor(double hz, double fs, double phase)
        : c_(std::cos(2.0 * kPi * phase)), s_(std::sin(2.0 * kPi * phase)),
          dc_(std::cos(2.0 * kPi * hz / fs)), ds_(std::sin(2.0 * kPi * hz / fs)) {}
    double next() {
        const double out = s_;
        const double c = c_ * dc_ - s_ * ds_;
        s_ = s_ * dc_ + c_ * ds_;
        c_ = c;
        return out;
    }

private:
    double c_ = 1.0, s_ = 0.0, dc_ = 1.0, ds_ = 0.0;
};

// TPT state-variable filter (Q = 0.707 is Butterworth). All three outputs per sample.
class Svf {
public:
    struct Out {
        double lp, bp, hp;
    };
    Svf(double hz, double q, double fs) { set(hz, q, fs); }
    void set(double hz, double q, double fs) {
        hz = std::clamp(hz, 10.0, 0.45 * fs);
        const double g = std::tan(kPi * hz / fs);
        k_ = 1.0 / q;
        a1_ = 1.0 / (1.0 + g * (g + k_));
        a2_ = g * a1_;
        a3_ = g * a2_;
    }
    Out process(double v0) {
        const double v3 = v0 - ic2_;
        const double v1 = a1_ * ic1_ + a2_ * v3;
        const double v2 = ic2_ + a2_ * ic1_ + a3_ * v3;
        ic1_ = 2.0 * v1 - ic1_;
        ic2_ = 2.0 * v2 - ic2_;
        return {v2, v1, v0 - k_ * v1 - v2};
    }
    double lp(double x) { return process(x).lp; }
    double bp(double x) { return process(x).bp; }
    double hp(double x) { return process(x).hp; }

private:
    double k_ = 1.414, a1_ = 0.0, a2_ = 0.0, a3_ = 0.0;
    double ic1_ = 0.0, ic2_ = 0.0;
};

// Six detuned square waves at original, deliberately inharmonic ratios. Summed, they give
// a dense clangorous spectrum that filtered noise alone lacks. The squares are naive (not
// band-limited) on purpose: their aliases are more inharmonic metal, under a high-pass,
// and they cost a few instructions each.
class MetalBank {
public:
    MetalBank(double baseHz, double fs, uint32_t seed) {
        static constexpr double kRatios[6] = {1.0, 1.4471, 1.8213, 2.3389, 2.9127, 3.6614};
        Noise phases(seed);
        for (std::size_t i = 0; i < 6; ++i) {
            phase_[i] = 0.5 + 0.5 * phases.next();
            dt_[i] = std::min(baseHz * kRatios[i] / fs, 0.45);
        }
    }
    double next() {
        // Plain arrays and a manual loop: this runs ~10^6 times per kit render, and Debug
        // builds don't inline std::array::operator[].
        double s = 0.0;
        double* p = phase_;
        const double* d = dt_;
        for (int i = 0; i < 6; ++i) {
            s += p[i] < 0.5 ? 1.0 : -1.0;
            p[i] += d[i];
            if (p[i] >= 1.0) p[i] -= 1.0;
        }
        return s * (1.0 / 6.0);
    }

private:
    double phase_[6] = {};
    double dt_[6] = {};
};

// Smooth saturation normalised so that +-1 maps to +-1 (tanh-like, no libm call).
double softSat(double x, double drive) {
    return static_cast<double>(dsp::softClip(static_cast<float>(drive * x)) / dsp::softClip(static_cast<float>(drive)));
}

// Linear attack ramp of `seconds` (avoids a hard first-sample step).
double attack(std::size_t i, double fs, double seconds) {
    const double t = static_cast<double>(i) / fs;
    return t >= seconds ? 1.0 : t / seconds;
}

using Buffer = std::vector<double>;

Buffer make(double seconds, double fs) { return Buffer(static_cast<std::size_t>(seconds * fs), 0.0); }

// --- the sounds ---

// Deep Kick: long, round sine whose pitch falls fast from a knock to a low hum, with a
// short noise click on top and gentle saturation for weight.
Buffer deepKick(double fs) {
    Buffer b = make(1.0, fs);
    Sine body;
    Decay pitchEnv(0.030, fs), chirpEnv(0.004, fs), amp(0.30, fs), clickEnv(0.0025, fs);
    Noise noise(0x1234567u);
    Svf clickLp(4200.0, 0.7, fs);
    Svf dcBlock(18.0, 0.7, fs);
    for (std::size_t i = 0; i < b.size(); ++i) {
        const double hz = 47.0 + 125.0 * pitchEnv.next() + 280.0 * chirpEnv.next();
        const double a = amp.next() * attack(i, fs, 0.0006);
        double s = softSat(body.next(hz, fs) * a, 1.7);
        s += 0.30 * clickLp.lp(noise.next()) * clickEnv.next();
        b[i] = dcBlock.hp(s);
    }
    return b;
}

// Punch Kick: shorter and harder; the body holds for 35 ms before decaying, a higher
// pitch sweep and more drive give it a tight, forward punch.
Buffer punchKick(double fs) {
    Buffer b = make(0.6, fs);
    Sine body;
    Decay pitchEnv(0.017, fs), chirpEnv(0.0022, fs), clickEnv(0.004, fs);
    const double holdS = 0.035;
    const double tailCoef = std::exp(-1.0 / (0.12 * fs));
    double tail = 1.0;
    Noise noise(0x2468aceu);
    Svf clickBp(3100.0, 1.2, fs);
    Svf dcBlock(22.0, 0.7, fs);
    for (std::size_t i = 0; i < b.size(); ++i) {
        const double t = static_cast<double>(i) / fs;
        const double hz = 56.0 + 190.0 * pitchEnv.next() + 520.0 * chirpEnv.next();
        if (t > holdS) tail *= tailCoef;
        const double a = tail * attack(i, fs, 0.0004);
        double s = softSat(body.next(hz, fs) * a, 3.0);
        s += 0.45 * clickBp.bp(noise.next()) * clickEnv.next();
        b[i] = dcBlock.hp(s);
    }
    return b;
}

// Snares: a pitched shell tone (two modes) plus band-limited noise for the wires, and a
// fast noise snap on the very front.
Buffer snare(double fs, double toneHz, double secondRatio, double toneTau, double wireLowHz, double wireHighHz,
             double wireTau, double wireMix, double seconds, uint32_t seed) {
    Buffer b = make(seconds, fs);
    Sine shell, shell2(0.25);
    Decay drop(0.012, fs), toneAmp(toneTau, fs), tone2Amp(toneTau * 0.7, fs), wireAmp(wireTau, fs), snapAmp(0.003, fs);
    Noise noise(seed);
    Svf wireHp(wireLowHz, 0.7, fs), wireLp(wireHighHz, 0.7, fs), snapHp(3000.0, 0.7, fs);
    for (std::size_t i = 0; i < b.size(); ++i) {
        const double bend = 1.0 + 0.35 * drop.next();
        const double tone = 0.55 * shell.next(toneHz * bend, fs) * toneAmp.next() +
                            0.30 * shell2.next(toneHz * secondRatio * bend, fs) * tone2Amp.next();
        const double n = noise.next();
        const double wires = wireLp.lp(wireHp.hp(n)) * wireAmp.next();
        const double snap = snapHp.hp(n) * snapAmp.next();
        b[i] = softSat((tone + wireMix * wires + 0.35 * snap) * attack(i, fs, 0.0003), 1.4);
    }
    return b;
}

// Clap: four noise bursts a few ms apart (several hands), then a diffuse tail, all
// band-passed around 1.2 kHz with a little air on top.
Buffer clap(double fs) {
    Buffer b = make(0.7, fs);
    static constexpr double kBurstS[4] = {0.0, 0.0082, 0.0151, 0.0236};
    static constexpr double kBurstAmp[4] = {0.9, 1.0, 0.8, 1.0};
    Noise noise(0x51a9u);
    Svf body(1180.0, 1.3, fs), air(2900.0, 1.6, fs), hp(450.0, 0.7, fs);
    const double burstCoef = std::exp(-1.0 / (0.0042 * fs));
    const double tailCoef = std::exp(-1.0 / (0.13 * fs));
    std::array<double, 4> burst{};
    double tail = 0.55;
    for (std::size_t i = 0; i < b.size(); ++i) {
        const double t = static_cast<double>(i) / fs;
        double env = 0.0;
        for (std::size_t k = 0; k < 4; ++k) {
            const double dt = t - kBurstS[k];
            if (dt < 0.0) continue;
            burst[k] = burst[k] == 0.0 ? kBurstAmp[k] : burst[k] * burstCoef;
            env += burst[k] * std::min(1.0, dt / 0.0003);
        }
        if (t >= kBurstS[3]) {
            env += tail;
            tail *= tailCoef;
        }
        const double n = hp.hp(noise.next());
        b[i] = (body.bp(n) + 0.45 * air.bp(n)) * env;
    }
    return b;
}

// Rim: a short woody knock from two inharmonic modes and a bright noise tick.
Buffer rim(double fs) {
    Buffer b = make(0.2, fs);
    Rotor m1(830.0, fs, 0.0), m2(1940.0, fs, 0.1), m3(3370.0, fs, 0.3);
    Decay a1(0.020, fs), a2(0.011, fs), a3(0.006, fs), tick(0.0025, fs);
    Noise noise(0x7777u);
    Svf tickBp(4300.0, 1.5, fs);
    for (std::size_t i = 0; i < b.size(); ++i) {
        const double s = 0.6 * m1.next() * a1.next() + 0.45 * m2.next() * a2.next() +
                         0.25 * m3.next() * a3.next() + 0.6 * tickBp.bp(noise.next()) * tick.next();
        b[i] = softSat(s * attack(i, fs, 0.0002), 2.0);
    }
    return b;
}

// Hats: a metal bank mixed with noise, high-passed (two cascaded Butterworth stages) with a
// gentle presence peak. tauFast/tauSlow give a two-stage decay.
Buffer hat(double fs, double seconds, double bankHz, double metalMix, double hpHz, double peakHz, double tauFast,
           double tauSlow, double slowMix, uint32_t seed) {
    Buffer b = make(seconds, fs);
    MetalBank bank(bankHz, fs, seed);
    Noise noise(seed * 2654435761u + 1u);
    Svf hp1(hpHz, 0.7, fs), hp2(hpHz, 0.7, fs), peak(peakHz, 1.4, fs);
    Decay fast(tauFast, fs), slow(tauSlow, fs);
    for (std::size_t i = 0; i < b.size(); ++i) {
        const double src = metalMix * bank.next() + (1.0 - metalMix) * noise.next();
        const double h = hp2.hp(hp1.hp(src));
        const double env = ((1.0 - slowMix) * fast.next() + slowMix * slow.next()) * attack(i, fs, 0.0002);
        b[i] = (h + 0.6 * peak.bp(h)) * env;
    }
    return b;
}

// Crash: two metal banks ring-modulated into a wash, plus noise, with a bright initial
// splash and a long tail.
Buffer crash(double fs) {
    Buffer b = make(2.4, fs);
    MetalBank a(377.0, fs, 0xc0ffeeu), c(531.0, fs, 0xbeefu);
    Noise noise(0xabcdefu);
    Svf hp1(3200.0, 0.7, fs), hp2(3200.0, 0.7, fs), lp(13500.0, 0.7, fs), body(5200.0, 0.9, fs);
    Decay splash(0.06, fs), tail(0.6, fs);
    for (std::size_t i = 0; i < b.size(); ++i) {
        const double src = 0.65 * a.next() * c.next() + 0.45 * noise.next();
        const double h = lp.lp(hp2.hp(hp1.hp(src)));
        const double env = (0.45 * splash.next() + 0.55 * tail.next()) * attack(i, fs, 0.0015);
        b[i] = (h + 0.5 * body.bp(h)) * env;
    }
    return b;
}

// Ride: a bell of inharmonic sine partials over a quieter metallic sizzle, with a short
// stick transient.
Buffer ride(double fs) {
    Buffer b = make(2.3, fs);
    static constexpr double kHz[5] = {1487.0, 2213.0, 2861.0, 3934.0, 5276.0};
    static constexpr double kAmp[5] = {0.45, 0.35, 0.32, 0.22, 0.15};
    static constexpr double kTau[5] = {1.1, 0.85, 0.7, 0.5, 0.35};
    static constexpr double kPhase[5] = {0.0, 0.13, 0.41, 0.67, 0.29};
    std::array<Rotor, 5> bell;
    for (std::size_t k = 0; k < 5; ++k) bell[k] = Rotor(kHz[k], fs, kPhase[k]);
    std::array<Decay, 5> bellAmp{{{kTau[0], fs}, {kTau[1], fs}, {kTau[2], fs}, {kTau[3], fs}, {kTau[4], fs}}};
    MetalBank bank(611.0, fs, 0x5eedu);
    Noise noise(0xfaceu);
    Svf hp1(6000.0, 0.7, fs), hp2(6000.0, 0.7, fs), tickBp(7000.0, 1.0, fs);
    Decay sizzle(0.9, fs), stick(0.002, fs);
    for (std::size_t i = 0; i < b.size(); ++i) {
        double s = 0.0;
        for (std::size_t k = 0; k < 5; ++k) s += kAmp[k] * bell[k].next() * bellAmp[k].next();
        const double n = noise.next();
        s += 0.55 * hp2.hp(hp1.hp(0.6 * bank.next() + 0.4 * n)) * sizzle.next();
        s += 0.5 * tickBp.bp(n) * stick.next();
        b[i] = s * attack(i, fs, 0.0005);
    }
    return b;
}

// Toms: a sine that bends down to its pitch, a quieter inharmonic second mode, and a
// soft mallet thump of low-passed noise.
Buffer tom(double fs, double hz, double tau, double seconds, uint32_t seed) {
    Buffer b = make(seconds, fs);
    Sine m1, m2(0.2);
    Decay bend(0.05, fs), amp(tau, fs), amp2(tau * 0.4, fs), thump(0.003, fs);
    Noise noise(seed);
    Svf thumpLp(2600.0, 0.7, fs), dcBlock(20.0, 0.7, fs);
    for (std::size_t i = 0; i < b.size(); ++i) {
        const double f = hz * (1.0 + 0.55 * bend.next());
        double s = m1.next(f, fs) * amp.next() + 0.22 * m2.next(f * 1.59, fs) * amp2.next();
        s += 0.3 * thumpLp.lp(noise.next()) * thump.next();
        b[i] = dcBlock.hp(softSat(s * attack(i, fs, 0.0005), 1.5));
    }
    return b;
}

// Block: a hollow wooden tock, two high modes with a tiny downward bend and a click.
Buffer block(double fs) {
    Buffer b = make(0.25, fs);
    Sine m1, m2(0.37);
    Decay bend(0.004, fs), a1(0.032, fs), a2(0.013, fs), click(0.0012, fs);
    Noise noise(0xb10cu);
    Svf clickBp(2400.0, 1.0, fs);
    for (std::size_t i = 0; i < b.size(); ++i) {
        const double k = 1.0 + 0.06 * bend.next();
        const double s = 0.7 * m1.next(1175.0 * k, fs) * a1.next() + 0.35 * m2.next(2930.0 * k, fs) * a2.next() +
                         0.35 * clickBp.bp(noise.next()) * click.next();
        b[i] = s * attack(i, fs, 0.0002);
    }
    return b;
}

// Shaker: band-passed noise with a slow swell (the beads travelling) and a second,
// softer grain as they land.
Buffer shaker(double fs) {
    Buffer b = make(0.3, fs);
    Noise noise(0x5a4eu);
    Svf bp(6300.0, 1.8, fs), hp(3800.0, 0.7, fs);
    for (std::size_t i = 0; i < b.size(); ++i) {
        const double t = static_cast<double>(i) / fs;
        const double rise = std::min(1.0, t / 0.008);
        double env = rise * rise * std::exp(-std::max(0.0, t - 0.008) / 0.045);
        const double t2 = t - 0.046;
        if (t2 > 0.0) env += 0.35 * std::min(1.0, t2 / 0.004) * std::exp(-t2 / 0.03);
        b[i] = bp.bp(hp.hp(noise.next())) * env;
    }
    return b;
}

// Bends the last 25% of the model's render to zero (raised cosine) so every sound has a
// natural-sounding end, trims the inaudible tail (below -70 dB of the peak), ramps the
// last few ms to exactly zero and normalises the peak.
std::vector<float> finish(Buffer in, double fs) {
    const std::size_t windowLen = in.size() / 4;
    const std::size_t windowStart = in.size() - windowLen;
    for (std::size_t i = 0; i < windowLen; ++i) {
        const double w = 0.5 + 0.5 * std::cos(kPi * static_cast<double>(i) / static_cast<double>(windowLen));
        in[windowStart + i] *= w;
    }
    double peak = 0.0;
    for (double v : in) peak = std::max(peak, std::fabs(v));
    if (peak <= 0.0) return std::vector<float>(1, 0.0f);
    const double floor = peak * std::pow(10.0, -70.0 / 20.0);
    std::size_t end = in.size();
    while (end > 1 && std::fabs(in[end - 1]) < floor) --end;
    const auto fadeLen = std::max<std::size_t>(
        1, std::min(static_cast<std::size_t>(0.010 * fs), end / 10));
    const double gain = std::pow(10.0, static_cast<double>(DrumKit::kPeakDb) / 20.0) / peak;
    // Every sound starts from exactly zero (0.25 ms ramp), so reversed playback ends
    // cleanly too.
    const auto rampIn = std::max<std::size_t>(1, static_cast<std::size_t>(0.00025 * fs));
    std::vector<float> out(end);
    for (std::size_t i = 0; i < end; ++i) {
        double g = gain;
        if (i < rampIn) g *= static_cast<double>(i) / static_cast<double>(rampIn);
        const std::size_t fromEnd = end - 1 - i;
        if (fromEnd < fadeLen) g *= static_cast<double>(fromEnd) / static_cast<double>(fadeLen);
        out[i] = static_cast<float>(in[i] * g);
    }
    return out;
}

constexpr const char* kNames[DrumKit::kSoundCount] = {
    "Deep Kick", "Punch Kick", "Crack Snare", "Tight Snare", "Clap",    "Rim",     "Closed Hat", "Tick Hat",
    "Open Hat",  "Crash",      "Ride",        "Low Tom",     "Mid Tom", "High Tom", "Block",      "Shaker",
};

Buffer synthesise(int key, double fs) {
    switch (key) {
        case 0: return deepKick(fs);
        case 1: return punchKick(fs);
        case 2: return snare(fs, 186.0, 1.72, 0.065, 1300.0, 9000.0, 0.15, 0.75, 0.55, 0x5ee1u);
        case 3: return snare(fs, 247.0, 1.61, 0.040, 2400.0, 12000.0, 0.085, 0.95, 0.4, 0x5ee2u);
        case 4: return clap(fs);
        case 5: return rim(fs);
        case 6: return hat(fs, 0.25, 409.0, 0.55, 7200.0, 10200.0, 0.020, 0.06, 0.15, 0x0a1u);
        case 7: return hat(fs, 0.3, 563.0, 0.25, 9300.0, 12800.0, 0.009, 0.045, 0.3, 0x0a2u);
        case 8: return hat(fs, 1.3, 409.0, 0.55, 6600.0, 9800.0, 0.05, 0.36, 0.7, 0x0a3u);
        case 9: return crash(fs);
        case 10: return ride(fs);
        case 11: return tom(fs, 84.0, 0.30, 0.95, 0x701u);
        case 12: return tom(fs, 121.0, 0.24, 0.8, 0x702u);
        case 13: return tom(fs, 169.0, 0.19, 0.65, 0x703u);
        case 14: return block(fs);
        default: return shaker(fs);
    }
}

}  // namespace

const char* DrumKit::name(int key) { return key >= 0 && key < kSoundCount ? kNames[key] : ""; }

void DrumKit::render(float sampleRate) {
    const double fs = static_cast<double>(sampleRate);
    std::array<std::vector<float>, kSoundCount> sounds;
    std::size_t total = kGuard;
    for (int k = 0; k < kSoundCount; ++k) {
        auto& s = sounds[static_cast<std::size_t>(k)];
        s = finish(synthesise(k, fs), fs);
        total += s.size() + kGuard;
    }
    storage_.assign(total, 0.0f);
    std::size_t at = kGuard;
    for (std::size_t k = 0; k < sounds.size(); ++k) {
        offset_[k] = at;
        length_[k] = static_cast<int32_t>(sounds[k].size());
        std::copy(sounds[k].begin(), sounds[k].end(), storage_.begin() + static_cast<std::ptrdiff_t>(at));
        at += sounds[k].size() + kGuard;
    }
    sampleRate_ = sampleRate;
}

}  // namespace groove
