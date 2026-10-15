#pragma once

#include "groove/DspMath.h"

namespace groove {

// Topology-preserving-transform (zero-delay-feedback) filters. Coefficients are set per
// control sub-block (setCutoff calls tan()); process() is per sample, branch-light and
// free of libm calls. Real-time safe: no allocation.

// TPT one-pole coefficient G = g / (1 + g), g = tan(pi * fc / fs), with fc clamped to
// [1 Hz, 0.45 fs].
float onePoleCoefficient(float cutoffHz, float sampleRate);

// One-pole (6 dB/oct) high-pass.
class OnePoleHighPass {
public:
    void setCoefficient(float g) { g_ = g; }
    void setCutoff(float hz, float sampleRate) { g_ = onePoleCoefficient(hz, sampleRate); }
    void reset() { s_ = 0.0f; }

    float process(float x) {
        const float v = (x - s_) * g_;
        const float lp = v + s_;
        s_ = lp + v;
        return x - lp;
    }

private:
    float g_ = 0.0f;
    float s_ = 0.0f;
};

// 4-pole (24 dB/oct) resonant low-pass ladder, TPT form with a soft-clipping feedback
// junction (the "drive" stage).
//  - The linear part is solved without delay in the feedback path, so cutoff and
//    resonance are exact: at full resonance it self-oscillates at the cutoff (F1.5).
//  - The saturator limits the loop, so it can never blow up at any setting.
//  - Passband loss from resonance is partly made up at the input.
class LadderFilter {
public:
    // Resonance 1.0 maps to a loop gain a little above the self-oscillation point (4).
    static constexpr float kMaxFeedback = 4.3f;

    void setSampleRate(float sampleRate) { sampleRate_ = sampleRate; }
    void setCutoff(float hz) { setCoefficient(onePoleCoefficient(hz, sampleRate_)); }
    // G of each one-pole stage, from onePoleCoefficient().
    void setCoefficient(float g);
    // 0..1
    void setResonance(float resonance);
    // Linear gain into the saturator (>= 1); the output is compensated by 1/sqrt(gain).
    void setDrive(float gain);
    // All three at once (one coefficient update), as the voice does per sub-block.
    // The input and output gains (drive and resonance make-up) ramp linearly over the
    // next rampSamples calls to process(), so moving Drive never steps the level.
    void configure(float g, float resonance, float driveGain, int rampSamples = 0);
    void reset();

    float process(float x) {
        if (rampLeft_ > 0) {
            --rampLeft_;
            inputGain_ = rampLeft_ > 0 ? inputGain_ + inputStep_ : inputTarget_;
            outputGain_ = rampLeft_ > 0 ? outputGain_ + outputStep_ : outputTarget_;
        }
        const float g = g_;
        const float sigma = s_[0] * g3_ + s_[1] * g2_ + s_[2] * g + s_[3];
        // Unit-step TPT one-pole: y = G * x + (1 - G) * s. With all four in series,
        // y4 = G^4 * u + (1 - G) * sigma; solve u = in - k * y4 for u, then saturate.
        const float uLinear = (inputGain_ * x - k_ * oneMinusG_ * sigma) * feedbackNorm_;
        const float u = dsp::softClip(uLinear);
        float in = u;
        for (float& s : s_) {
            const float v = (in - s) * g;
            const float y = v + s;
            s = y + v;
            in = y;
        }
        return in * outputGain_;
    }

private:
    void update(int rampSamples);

    float sampleRate_ = 48000.0f;
    float g_ = 0.5f;
    float g2_ = 0.25f;
    float g3_ = 0.125f;
    float oneMinusG_ = 0.5f;
    float k_ = 0.0f;
    float drive_ = 1.0f;
    float inputGain_ = 1.0f;
    float outputGain_ = 1.0f;
    float feedbackNorm_ = 1.0f;
    float inputTarget_ = 1.0f;
    float outputTarget_ = 1.0f;
    float inputStep_ = 0.0f;
    float outputStep_ = 0.0f;
    int rampLeft_ = 0;
    float s_[4] = {0.0f, 0.0f, 0.0f, 0.0f};
};

}  // namespace groove
