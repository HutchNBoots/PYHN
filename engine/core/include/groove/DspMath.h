#pragma once

#include <cstdint>

// Small real-time-safe maths helpers shared by the DSP blocks. Header-only so they inline
// into the per-sample loops.
namespace groove::dsp {

inline constexpr float kPi = 3.14159265358979323846f;
inline constexpr float kTwoPi = 6.28318530717958647692f;

// sin(2*pi*t) for any phase t in [0, 1). Odd Taylor series to degree 11 after folding
// into [-1/4, 1/4] of a cycle: max error about 1e-7, with no libm call.
inline float sinCycle(float t) {
    // y in [-1, 1) such that sin(2*pi*t) = sin(pi*y).
    float y = 2.0f * t;
    if (y >= 1.0f) y -= 2.0f;
    // Fold to [-0.5, 0.5]: sin(pi*(1-y)) = sin(pi*y).
    if (y > 0.5f) y = 1.0f - y;
    if (y < -0.5f) y = -1.0f - y;
    const float x = kPi * y;
    const float x2 = x * x;
    return x * (1.0f +
                x2 * (-1.0f / 6.0f +
                      x2 * (1.0f / 120.0f +
                            x2 * (-1.0f / 5040.0f + x2 * (1.0f / 362880.0f + x2 * (-1.0f / 39916800.0f))))));
}

// Smooth saturator close to tanh (Pade 3/2 approximant), exactly +-1 with zero slope
// beyond |x| = 3, so it is continuous and bounded.
inline float softClip(float x) {
    if (x <= -3.0f) return -1.0f;
    if (x >= 3.0f) return 1.0f;
    const float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

// Band-limited step and ramp corrections from a quintic B-spline kernel (6 samples wide),
// a higher-order PolyBLEP / PolyBLAMP. x is the distance of the sample from the
// discontinuity in samples (positive after it). Zero outside (-3, 3).
//   step of height h:          out = naive + h * blepResidual(x)
//   slope change of d/sample:  out = naive + d * blampResidual(x)
// The kernel's spectrum is sinc^6, so partials above Nyquist are strongly attenuated
// before they fold back (F1.4); the price is a gentle roll-off of the top octave
// (about -4 dB at 10 kHz at 48 kHz), which the synth's filter mostly hides anyway.
// Polynomials derived exactly (rational arithmetic) by integrating the kernel.
inline constexpr float kBlepReach = 3.0f;  // samples on each side of an edge

inline float blepResidual(float x) {
    if (x <= -3.0f || x >= 3.0f) return 0.0f;
    const float sign = x < 0.0f ? -1.0f : 1.0f;  // odd function
    const float a = x < 0.0f ? -x : x;
    float r;
    if (a < 1.0f) {
        const float a2 = a * a;
        r = -0.5f + a * (11.0f / 20.0f + a2 * (-1.0f / 6.0f + a2 * (1.0f / 20.0f - a * (1.0f / 72.0f))));
    } else if (a < 2.0f) {
        r = -23.0f / 48.0f +
            a * (17.0f / 40.0f +
                 a * (5.0f / 16.0f + a * (-7.0f / 12.0f + a * (5.0f / 16.0f + a * (-3.0f / 40.0f + a * (1.0f / 144.0f))))));
    } else {
        const float b = 3.0f - a;
        const float b2 = b * b;
        r = -(b2 * b2 * b2) * (1.0f / 720.0f);
    }
    return sign * r;
}

inline float blampResidual(float x) {
    if (x <= -3.0f || x >= 3.0f) return 0.0f;
    const float a = x < 0.0f ? -x : x;  // even function
    if (a < 1.0f) {
        const float a2 = a * a;
        return 239.0f / 840.0f - a * 0.5f +
               a2 * (11.0f / 40.0f + a2 * (-1.0f / 24.0f + a2 * (1.0f / 120.0f - a * (1.0f / 504.0f))));
    }
    if (a < 2.0f) {
        return 473.0f / 1680.0f +
               a * (-23.0f / 48.0f +
                    a * (17.0f / 80.0f +
                         a * (5.0f / 48.0f +
                              a * (-7.0f / 48.0f + a * (1.0f / 16.0f + a * (-1.0f / 80.0f + a * (1.0f / 1008.0f)))))));
    }
    const float b = 3.0f - a;
    const float b2 = b * b;
    return b2 * b2 * b2 * b * (1.0f / 5040.0f);
}

// Wrapped phase distance from edge to t, in [-0.5, 0.5).
inline float phaseDistance(float t, float edge) {
    float d = t - edge;
    if (d < -0.5f) d += 1.0f;
    if (d >= 0.5f) d -= 1.0f;
    return d;
}

// Sum of a residual for one periodic edge, seen from phase t with increment dt (in cycles
// per sample). At very high pitches the kernel can reach the edge on both sides.
template <float (*Residual)(float)>
inline float edgeCorrection(float t, float edge, float dt) {
    const float d = phaseDistance(t, edge);
    const float reach = kBlepReach * dt;
    float r = 0.0f;
    if (d < reach && d > -reach) r += Residual(d / dt);
    if (reach > 0.5f) {
        const float other = d >= 0.0f ? d - 1.0f : d + 1.0f;
        if (other < reach && other > -reach) r += Residual(other / dt);
    }
    return r;
}

// xorshift32: tiny, seedable, deterministic white-noise source. Never returns state 0.
class XorShift32 {
public:
    explicit XorShift32(uint32_t seed = 0x9E3779B9u) { reseed(seed); }
    void reseed(uint32_t seed) { state_ = seed != 0 ? seed : 0x9E3779B9u; }
    uint32_t nextU32() {
        uint32_t x = state_;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        state_ = x;
        return x;
    }
    // Uniform in [-1, 1].
    float nextBipolar() {
        return static_cast<float>(static_cast<int32_t>(nextU32())) * (1.0f / 2147483648.0f);
    }

private:
    uint32_t state_ = 1;
};

}  // namespace groove::dsp
