#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>

namespace groove {

// Sequencer data model (docs/plans/2026-10-16-m2-sequencer-drums.md, feature spec F3).
// The UI keeps a mirror of the same data (app/.../ui/model/PatternStore.kt) and applies
// the same defaults and clamping rules, so both copies always agree.
//
// Plain fixed-size data: no pointers, no allocation, safe to copy on the audio thread.

inline constexpr int kSlotCount = 8;  // slot 0 = drums, 1..7 = synths
inline constexpr int kDrumSlot = 0;
inline constexpr int kPatternCount = 16;
inline constexpr int kMaxSteps = 64;
inline constexpr int kMaxNotesPerStep = 4;
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

struct Step {
    static constexpr float kDefaultVelocity = 0.8f;
    static constexpr float kPatternGate = -1.0f;  // use the pattern's gate
    static constexpr float kMinGate = 0.05f;

    bool on = false;
    int32_t noteCount = 0;
    std::array<int32_t, kMaxNotesPerStep> notes{};
    float velocity = kDefaultVelocity;  // 0..1
    float gate = kPatternGate;          // 0.05..1 of a step, or negative for the pattern's gate
    float probability = 1.0f;           // 0..1

    bool hasNote(int note) const {
        for (int32_t i = 0; i < noteCount; ++i) {
            if (notes[static_cast<std::size_t>(i)] == note) return true;
        }
        return false;
    }

    // Live-record rule (RecordedEvent in NativeEngine.kt): add the note if it isn't there;
    // when the step already holds 4 notes, the last one is replaced.
    void addNote(int note) {
        if (hasNote(note)) return;
        if (noteCount < kMaxNotesPerStep) {
            notes[static_cast<std::size_t>(noteCount++)] = note;
        } else {
            notes[kMaxNotesPerStep - 1] = note;
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
    static constexpr int32_t kShuffleResolution = 1000;  // shuffle is stored in 0.1% steps

    int32_t length = kDefaultLength;  // 1..64
    StepRate rate = StepRate::Sixteenth;
    int32_t shuffleMilli = 0;  // 0..1000 (0..100%)
    float gate = kDefaultGate;  // 0.05..1 of a step
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
