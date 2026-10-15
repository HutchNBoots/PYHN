#pragma once

#include <cstdint>

namespace groove {

enum class Waveform : int32_t { Sine = 0, Saw = 1, Square = 2 };

// Single band-limited oscillator (PolyBLEP for saw and square). Real-time safe.
class Oscillator {
public:
    void setSampleRate(float sampleRate);
    void setFrequency(float hz);
    void setWaveform(Waveform waveform) { waveform_ = waveform; }
    void resetPhase() { phase_ = 0.0f; }

    // Returns the next sample in roughly [-1, 1].
    float process();

private:
    float sampleRate_ = 48000.0f;
    float frequency_ = 440.0f;
    float increment_ = 440.0f / 48000.0f;
    float phase_ = 0.0f;  // [0, 1)
    Waveform waveform_ = Waveform::Saw;
};

}  // namespace groove
