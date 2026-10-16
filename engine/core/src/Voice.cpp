#include "groove/Voice.h"

#include <algorithm>
#include <cmath>

namespace groove {

namespace {

constexpr float kLfoPitchSemis = 12.0f;     // depth^2 * 1 octave: fine vibrato at low depth
constexpr float kLfoCutoffOctaves = 3.0f;   // per unit of depth (dest cutoff) or flt.lfoAmt
constexpr float kLfoPulseWidthSwing = 0.45f;
constexpr float kMaxCutoffOctaves = 15.0f;  // exp2 input clamp (32 kHz; the filter clamps to 0.45 fs)
constexpr float kMinCutoffOctaves = 3.0f;   // 8 Hz
constexpr float kOscMixGain = 0.5f;         // headroom into the ladder's saturator

float semisToHz(float semis) { return 440.0f * std::exp2((semis - 69.0f) * (1.0f / 12.0f)); }

}  // namespace

void Voice::prepare(float sampleRate, uint32_t seed) {
    sampleRate_ = sampleRate;
    controlRate_ = sampleRate / static_cast<float>(kSubBlock);
    fadeStep_ = 1.0f / std::max(1.0f, kStealFadeSeconds * sampleRate);
    osc_ = Oscillator{};
    osc_.setSampleRate(sampleRate);
    hpf_ = OnePoleHighPass{};
    ladder_ = LadderFilter{};
    ladder_.setSampleRate(sampleRate);
    lfo_ = Lfo{};
    lfo_.setUpdateRate(controlRate_);
    lfo_.seed(seed * 2654435761u + 1u);
    noise_.reseed(seed);
    reset();
}

void Voice::reset() {
    idle_ = true;
    gate_ = false;
    sounded_ = false;
    fading_ = false;
    fadeGain_ = 1.0f;
    hasPending_ = false;
    amp_.reset();
    filterEnv_.reset();
}

void Voice::start(int note, float velocity, const VoiceControl& c) {
    idle_ = false;
    gate_ = true;
    sounded_ = false;
    fading_ = false;
    fadeGain_ = 1.0f;
    hasPending_ = false;
    note_ = note;
    velocity_ = velocity;
    pitch_ = targetPitch_ = static_cast<float>(note);
    glideStep_ = 0.0f;
    // The voice is silent here, so resetting the signal path cannot click.
    osc_.resetPhase(startPhase_);
    hpf_.reset();
    ladder_.reset();
    amp_.reset();
    filterEnv_.reset();
    amp_.gateOn();
    filterEnv_.gateOn();
    lfo_.reset();
    lfoFade_ = c.lfoFadeStep >= 1.0f ? 1.0f : 0.0f;
    snapRamps_ = true;
}

void Voice::trigger(int note, float velocity, bool retrigger, const VoiceControl& c) {
    if (fading_) {
        steal(note, velocity);
        return;
    }
    if (idle_ || !sounded_) {
        start(note, velocity, c);
        return;
    }
    note_ = note;
    targetPitch_ = static_cast<float>(note);
    if (c.glideSeconds > 0.0f) {
        glideStep_ = std::fabs(targetPitch_ - pitch_) / std::max(1.0f, c.glideSeconds * controlRate_);
    } else {
        pitch_ = targetPitch_;
        glideStep_ = 0.0f;
    }
    gate_ = true;
    if (retrigger) {
        // Envelopes restart from their current level: no jump (F1.6).
        velocity_ = velocity;
        amp_.gateOn();
        filterEnv_.gateOn();
        if (c.lfoRetrigger) lfo_.reset();
        lfoFade_ = c.lfoFadeStep >= 1.0f ? 1.0f : 0.0f;
    }
}

void Voice::release() {
    if (idle_) return;
    gate_ = false;
    amp_.gateOff();
    filterEnv_.gateOff();
}

void Voice::steal(int note, float velocity) {
    if (!fading_) fadeGain_ = 1.0f;
    fading_ = true;
    hasPending_ = true;
    pendingNote_ = note;
    pendingVelocity_ = velocity;
}

void Voice::fadeOut() {
    if (idle_) return;
    if (!sounded_) {
        reset();
        return;
    }
    if (!fading_) fadeGain_ = 1.0f;
    fading_ = true;
    hasPending_ = false;
}

void Voice::controlUpdate(const VoiceControl& c, int n) {
    // Glide: constant time, linear in pitch.
    if (pitch_ != targetPitch_) {
        if (glideStep_ <= 0.0f || std::fabs(targetPitch_ - pitch_) <= glideStep_) {
            pitch_ = targetPitch_;
        } else {
            pitch_ += pitch_ < targetPitch_ ? glideStep_ : -glideStep_;
        }
    }

    lfo_.setShape(c.lfoShape);
    lfo_.setFrequency(c.lfoHz);
    float lfo = lfo_.process();
    if (!c.lfoRetrigger) lfo = c.freeLfo;
    lfoFade_ = std::min(1.0f, lfoFade_ + c.lfoFadeStep);
    lfo *= lfoFade_;

    float pitchMod = 0.0f, cutoffMod = 0.0f, pwMod = 0.0f, tremolo = 1.0f;
    const float depth = c.lfoDepth;
    switch (c.lfoDest) {
        case LfoDest::Pitch:
            pitchMod = lfo * depth * depth * kLfoPitchSemis;
            break;
        case LfoDest::Cutoff:
            cutoffMod = lfo * depth * kLfoCutoffOctaves;
            break;
        case LfoDest::PulseWidth:
            pwMod = lfo * depth * kLfoPulseWidthSwing;
            break;
        case LfoDest::Amp:
            tremolo = 1.0f - depth * 0.5f * (1.0f - lfo);
            break;
    }

    const float played = pitch_ + c.pitchOffsetSemis + detuneSemis_;
    osc_.setFrequency(semisToHz(played + pitchMod));
    osc_.setPulseWidth(c.pulseWidth + pwMod);

    const float fenv = filterEnv_.process(c.filterShape);
    float octaves = c.cutoffOctaves + c.envOctaves * fenv + c.keyTrack * (played - 60.0f) * (1.0f / 12.0f) +
                    c.velocityOctaves * (velocity_ - 1.0f) + cutoffMod + c.lfoCutoffOctaves * lfo;
    octaves = std::clamp(octaves, kMinCutoffOctaves, kMaxCutoffOctaves);
    ladder_.configure(onePoleCoefficient(std::exp2(octaves), sampleRate_), c.resonance, c.drive, snapRamps_ ? 0 : n);
    hpf_.setCoefficient(c.hpfCoef);

    weightsTarget_ = c.waveWeights;
    subTarget_ = c.subLevel;
    noiseTarget_ = c.noiseLevel;
    gainTarget_ = kOutputGain * (0.6f + 0.4f * velocity_) * tremolo;
    if (snapRamps_) {
        weights_ = weightsTarget_;
        sub_ = subTarget_;
        noiseLevel_ = noiseTarget_;
        gain_ = gainTarget_;
        snapRamps_ = false;
    }
}

void Voice::render(float* out, int n, int subBlockLeft, bool newSubBlock, const VoiceControl& c) {
    if (fading_ && fadeGain_ <= 0.0f && hasPending_) start(pendingNote_, pendingVelocity_, c);

    if (newSubBlock || snapRamps_ || rampLeft_ <= 0) {
        const int len = std::max(subBlockLeft, n);
        controlUpdate(c, len);
        const float inv = 1.0f / static_cast<float>(len);
        for (std::size_t k = 0; k < dWeights_.size(); ++k) dWeights_[k] = (weightsTarget_[k] - weights_[k]) * inv;
        dSub_ = (subTarget_ - sub_) * inv;
        dNoise_ = (noiseTarget_ - noiseLevel_) * inv;
        dGain_ = (gainTarget_ - gain_) * inv;
        rampLeft_ = len;
    }

    Oscillator::Weights w = weights_;
    const Oscillator::Weights dw = dWeights_;
    float sub = sub_;
    const float dSub = dSub_;
    float noise = noiseLevel_;
    const float dNoise = dNoise_;
    float gain = gain_;
    const float dGain = dGain_;
    const bool useNoise = noise > 0.0f || noiseTarget_ > 0.0f;
    const bool useSub = sub > 0.0f || subTarget_ > 0.0f;

    for (int i = 0; i < n; ++i) {
        for (std::size_t k = 0; k < w.size(); ++k) w[k] += dw[k];
        gain += dGain;
        float s = osc_.process(w);
        if (useSub) {
            sub += dSub;
            s += osc_.sub() * sub;
        }
        if (useNoise) {
            noise += dNoise;
            s += noise_.nextBipolar() * noise;
        }
        s = ladder_.process(hpf_.process(s * kOscMixGain));
        float a = amp_.process(c.ampShape) * gain;
        if (fading_) {
            fadeGain_ = std::max(0.0f, fadeGain_ - fadeStep_);
            a *= fadeGain_;
        }
        out[i] = s * a;
    }

    rampLeft_ -= n;
    if (rampLeft_ <= 0) {
        // End of the sub-block: land exactly on the targets (no rounding drift).
        weights_ = weightsTarget_;
        sub_ = subTarget_;
        noiseLevel_ = noiseTarget_;
        gain_ = gainTarget_;
    } else {
        weights_ = w;
        sub_ = useSub ? sub : sub_ + dSub * static_cast<float>(n);
        noiseLevel_ = useNoise ? noise : noiseLevel_ + dNoise * static_cast<float>(n);
        gain_ = gain;
    }
    sounded_ = true;

    if (fading_) {
        if (fadeGain_ <= 0.0f && !hasPending_) reset();
    } else if (amp_.isIdle()) {
        reset();
    }
}

}  // namespace groove
