#pragma once

namespace groove {

// Linear attack / release gate envelope with full sustain. Retriggering starts the
// attack from the current level, so legato and fast repeats never click. Real-time safe.
class Envelope {
public:
    void setSampleRate(float sampleRate);
    void setAttackMs(float ms);
    void setReleaseMs(float ms);

    void gateOn() { stage_ = Stage::Attack; }
    void gateOff() {
        if (stage_ != Stage::Idle) stage_ = Stage::Release;
    }

    float process();

    bool isIdle() const { return stage_ == Stage::Idle; }
    float level() const { return level_; }

private:
    enum class Stage { Idle, Attack, Sustain, Release };

    void updateSteps();

    float sampleRate_ = 48000.0f;
    float attackMs_ = 1.0f;
    float releaseMs_ = 80.0f;
    float attackStep_ = 0.0f;
    float releaseStep_ = 0.0f;
    float level_ = 0.0f;
    Stage stage_ = Stage::Idle;
};

}  // namespace groove
