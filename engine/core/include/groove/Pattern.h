#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>

namespace groove {

// Sequencer data model (docs/plans/2026-10-16-m2-sequencer-drums.md, feature spec F3; per-hit
// steps from docs/plans/2026-10-16-build-20-sequencer-lanes.md).
// The UI keeps a mirror of the same data (app/.../ui/model/PatternStore.kt) and applies
// the same defaults and clamping rules, so both copies always agree.
//
// Plain fixed-size data: no pointers, no allocation, safe to copy on the audio thread.

inline constexpr int kSlotCount = 8;  // slot 0 = drums, 1..7 = synths
inline constexpr int kDrumSlot = 0;
inline constexpr int kPatternCount = 16;
inline constexpr int kMaxSteps = 64;
inline constexpr int kMaxHitsPerStep = 8;  // NativeEngine.MAX_HITS_PER_STEP
inline constexpr int kMaxMotionLanes = 8;

// Same order as NativeEngine.STEP_RATES.
enum class StepRate : int32_t { Eighth = 0, Sixteenth = 1, ThirtySecond = 2, SixteenthTriplet = 3 };
inline constexpr int kStepRateCount = 4;

constexpr int stepsPerBeat(StepRate rate) {
    switch (rate) {
        case StepRate::Eighth:
            return 2;
        case StepRate::Sixteenth:
            return 4;
        case StepRate::ThirtySecond:
            return 8;
        case StepRate::SixteenthTriplet:
            return 6;
    }
    return 4;
}

// One sound on a step (docs/plans/2026-10-16-build-20-sequencer-lanes.md, "Data model").
// Sequencer::setStep clamps every field to these ranges, exactly like the UI's mirror.
struct Hit {
    static constexpr float kDefaultVelocity = 0.8f;
    static constexpr float kMinVelocity = 0.01f;
    static constexpr float kPatternGate = -1.0f;    // length: use the pattern's gate
    static constexpr float kMinLength = 1.0f / 16.0f;  // steps
    static constexpr float kMaxLength = 16.0f;         // steps
    static constexpr float kMaxNudge = 0.5f;           // of a step, either way

    int32_t note = 60;                  // MIDI 0..127
    float velocity = kDefaultVelocity;  // 0.01..1
    float length = kPatternGate;        // kPatternGate, or kMinLength..kMaxLength steps
    float probability = 1.0f;           // 0..1, rolled per hit (F3.4)
    float nudge = 0.0f;                 // -0.5..0.5 of a step: early (negative) or late
};

// A step is on when it has at least one hit. On the drum slot each hit is one kit sound
// (lanes); on a synth slot the hits are the step's chord.
struct Step {
    int32_t hitCount = 0;  // 0..kMaxHitsPerStep
    std::array<Hit, kMaxHitsPerStep> hits{};

    bool on() const { return hitCount > 0; }

    bool hasNote(int note) const {
        for (int32_t i = 0; i < hitCount; ++i) {
            if (hits[static_cast<std::size_t>(i)].note == note) return true;
        }
        return false;
    }

    // Live-record rule (RecordedEvent in NativeEngine.kt): add the hit unless its note is
    // already there; when the step already holds kMaxHitsPerStep hits, the last is replaced.
    void addHit(const Hit& hit) {
        if (hasNote(hit.note)) return;
        if (hitCount < kMaxHitsPerStep) {
            hits[static_cast<std::size_t>(hitCount++)] = hit;
        } else {
            hits[kMaxHitsPerStep - 1] = hit;
        }
    }
};

struct MotionLane {
    static constexpr int32_t kUnused = -1;
    int32_t target = kUnused;  // synth: ParamId; drums: key * kDrumParamCount + DrumParamId
    std::array<float, kMaxSteps> values;  // real units; NaN = no value at that step

    MotionLane() { clear(); }
    void clear() {
        target = kUnused;
        values.fill(std::numeric_limits<float>::quiet_NaN());
    }
    bool used() const { return target != kUnused; }
};

struct Pattern {
    static constexpr int32_t kDefaultLength = 16;
    static constexpr float kDefaultGate = 0.5f;
    static constexpr float kMinGate = 0.05f;
    static constexpr int32_t kShuffleResolution = 1000;  // shuffle is stored in 0.1% steps

    int32_t length = kDefaultLength;  // 1..64
    StepRate rate = StepRate::Sixteenth;
    int32_t shuffleMilli = 0;  // 0..1000 (0..100%)
    float gate = kDefaultGate;  // 0.05..1 of a step: the length of hits with kPatternGate
    std::array<Step, kMaxSteps> steps{};
    std::array<MotionLane, kMaxMotionLanes> lanes{};

    // Every step off, motion cleared, settings back to the defaults.
    void clear() {
        length = kDefaultLength;
        rate = StepRate::Sixteenth;
        shuffleMilli = 0;
        gate = kDefaultGate;
        steps.fill(Step{});
        for (MotionLane& l : lanes) l.clear();
    }

    MotionLane* lane(int target) {
        for (MotionLane& l : lanes) {
            if (l.target == target) return &l;
        }
        return nullptr;
    }
    const MotionLane* lane(int target) const {
        for (const MotionLane& l : lanes) {
            if (l.target == target) return &l;
        }
        return nullptr;
    }
    // The lane for target, taking a free one if needed; nullptr when all 8 are in use.
    MotionLane* laneOrNew(int target) {
        if (MotionLane* l = lane(target)) return l;
        for (MotionLane& l : lanes) {
            if (!l.used()) {
                l.target = target;
                return &l;
            }
        }
        return nullptr;
    }
    bool hasLane(int target) const { return lane(target) != nullptr; }
};

}  // namespace groove
