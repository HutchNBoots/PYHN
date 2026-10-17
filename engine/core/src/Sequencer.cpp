#include "groove/Sequencer.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include "groove/DrumMachine.h"
#include "groove/DrumParams.h"
#include "groove/SynthParams.h"

namespace groove {

namespace {

constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();
constexpr int32_t kShuffleDivisor = 3 * Pattern::kShuffleResolution / 2;  // 2/3 of a step at 100%

// ceil(a / b) for b > 0 and any sign of a.
int64_t ceilDiv(int64_t a, int64_t b) {
    const int64_t q = a / b;
    return (a % b > 0) ? q + 1 : q;
}

bool validSlot(int slot) { return slot >= 0 && slot < kSlotCount; }
bool validPattern(int pattern) { return pattern >= 0 && pattern < kPatternCount; }
bool validStep(int step) { return step >= 0 && step < kMaxSteps; }

float finiteOr(float v, float fallback) { return std::isfinite(v) ? v : fallback; }

// round(nudge * units): a nudged hit's offset, a whole number of position units.
int64_t nudgeUnits(float nudge, int64_t units) {
    return static_cast<int64_t>(std::llround(static_cast<double>(nudge) * static_cast<double>(units)));
}

uint32_t seedFor(int slot) { return 0x9E3779B9u ^ (0x85EBCA6Bu * static_cast<uint32_t>(slot + 1)); }

// xorshift32, uniform in [0, 1).
float nextUnit(uint32_t& state) {
    uint32_t x = state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    state = x;
    return static_cast<float>(x >> 8) * (1.0f / 16777216.0f);
}

}  // namespace

struct Sequencer::Command {
    enum class Type : int32_t {
        Play,
        Stop,
        Tempo,
        RecordArmed,
        MotionRecording,
        SelectPattern,
        Settings,
        SetStep,
        MotionValue,
        ClearLane,
        ClearPattern,
        StepLoop,
        RecordMotion,
    };
    Type type = Type::Play;
    int32_t slot = 0;
    int32_t pattern = 0;
    int32_t step = 0;
    int32_t a = 0;
    int32_t b = 0;
    float f0 = 0.0f;
    float f1 = 0.0f;
    float f2 = 0.0f;
    bool flag = false;
    Step hits{};  // SetStep only
};
// The SetStep payload (8 hits, 164 bytes) sets the size: about 200 bytes, so the queue's
// kCommandCapacity = 4096 commands is about 800 KB, allocated once on the control thread.
// That still holds a dense session load (one command per non-empty step) without waiting.
static_assert(sizeof(Step) == 4 + kMaxHitsPerStep * 20, "Step is plain data: hitCount + 8 hits");

struct Sequencer::SlotState {
    struct Off {
        int32_t note;
        uint32_t order;  // when it was scheduled: the oldest goes first when the list is full
        int64_t frame;
    };
    // A hit waiting for its nudged time (or, from the look-ahead, for the next step's early time).
    struct PendingHit {
        int32_t note;
        float velocity;
        int32_t pattern;  // the step it belongs to (live recording takes it over)
        int32_t step;
        int64_t position;  // exact
        int64_t frame;     // frameOf(position), kept in step with tempo changes
        int64_t lengthUnits;
    };
    struct Override {
        int32_t target;
        float value;
    };
    static constexpr int kMaxOverrides = 2 * kMaxMotionLanes;

    int32_t current = 0;
    int32_t queued = -1;

    // Clock: grid position (unshuffled) of the next step, its virtual index in the current
    // pattern, and its exact (shuffled) position and frame.
    int64_t gridPosition = 0;
    int32_t nextVirtual = 0;
    int64_t nextEventPosition = 0;
    int64_t nextEventFrame = 0;

    // The step last triggered.
    int32_t lastStep = -1;
    int32_t lastPattern = 0;
    int64_t lastEventPosition = 0;

    bool loopActive = false;
    int32_t loopStart = 0;
    int32_t loopEnd = 0;
    int32_t loopNext = 0;

    uint32_t rng = 1;

    std::array<Off, kMaxPendingOffs> offs{};
    int32_t offCount = 0;
    uint32_t offOrder = 0;

    std::array<PendingHit, kMaxPendingHits> pending{};
    int32_t pendingCount = 0;

    // The step whose early hits the look-ahead handled, and their notes: that step's own
    // trigger skips them (scheduled, or dropped by chance, once).
    int32_t lookStep = -1;
    int32_t lookPattern = -1;
    int32_t lookCount = 0;
    std::array<int32_t, kMaxHitsPerStep> lookNotes{};

    // Motion overrides currently applied to the instrument by this slot's patterns.
    std::array<Override, kMaxOverrides> overrides{};
    int32_t overrideCount = 0;

    // Targets moved while motion recording: playback leaves them to the live strip.
    std::array<int32_t, kMaxOverrides> touched{};
    int32_t touchedCount = 0;

    // A live-recorded note written to the upcoming step already sounds from the keys: that
    // step's next trigger skips it (once), so it isn't played twice.
    int32_t suppressStep = -1;
    int32_t suppressPattern = -1;
    int32_t suppressCount = 0;
    std::array<int32_t, kMaxHitsPerStep> suppressNotes{};

    void resetRuntime() {
        gridPosition = 0;
        nextVirtual = 0;
        nextEventPosition = 0;
        nextEventFrame = 0;
        lastStep = -1;
        lastPattern = current;
        lastEventPosition = 0;
        loopNext = loopStart;
        offCount = 0;
        offOrder = 0;
        pendingCount = 0;
        lookStep = -1;
        lookPattern = -1;
        lookCount = 0;
        overrideCount = 0;
        touchedCount = 0;
        suppressStep = -1;
        suppressCount = 0;
    }
};

Sequencer::Sequencer()
    : patterns_(std::make_unique<std::array<std::array<Pattern, kPatternCount>, kSlotCount>>()),
      slots_(std::make_unique<std::array<SlotState, kSlotCount>>()),
      commands_(std::make_unique<SpscQueue<Command, kCommandCapacity>>()) {
    static_assert(sizeof(Command) <= 208, "keep the command queue's memory in check");
    for (int s = 0; s < kSlotCount; ++s) {
        outStep_[static_cast<std::size_t>(s)].store(-1, std::memory_order_relaxed);
        outPattern_[static_cast<std::size_t>(s)].store(0, std::memory_order_relaxed);
        outQueued_[static_cast<std::size_t>(s)].store(-1, std::memory_order_relaxed);
    }
    prepare(48000.0f);
}

Sequencer::~Sequencer() = default;

void Sequencer::attach(int slot, Instrument* instrument) {
    if (validSlot(slot)) instruments_[static_cast<std::size_t>(slot)] = instrument;
}

void Sequencer::prepare(float sampleRate) {
    sampleRate_ = std::max<int32_t>(1, static_cast<int32_t>(std::lround(sampleRate)));
    unitsPerBeat_ = kUnitsPerBeatPerHz * sampleRate_;
    minNoteFrames_ = std::max<int64_t>(1, std::lround(kMinNoteSeconds * static_cast<float>(sampleRate_)));
    playing_ = false;
    anchorFrame_ = 0;
    anchorPosition_ = 0;
    for (SlotState& s : *slots_) {
        if (s.queued >= 0) s.current = s.queued;
        s.queued = -1;
        s.resetRuntime();
    }
    for (Instrument* inst : instruments_) {
        if (inst) inst->setTempo(static_cast<float>(tempoTenths_) * 0.1f);
    }
    publish();
}

// --- control thread ---

bool Sequencer::push(const Command& c) { return commands_->push(c); }

bool Sequencer::play() {
    Command c;
    c.type = Command::Type::Play;
    return push(c);
}

bool Sequencer::stop() {
    Command c;
    c.type = Command::Type::Stop;
    return push(c);
}

int32_t Sequencer::tempoTenths(float bpm) {
    if (std::isnan(bpm)) return kDefaultTempoTenths;
    const double v = std::clamp(static_cast<double>(bpm), 40.0, 240.0);
    return std::clamp(static_cast<int32_t>(std::lround(v * 10.0)), kMinTempoTenths, kMaxTempoTenths);
}

bool Sequencer::setTempo(float bpm) {
    Command c;
    c.type = Command::Type::Tempo;
    c.a = tempoTenths(bpm);
    return push(c);
}

bool Sequencer::setRecordArmed(bool armed) {
    Command c;
    c.type = Command::Type::RecordArmed;
    c.flag = armed;
    return push(c);
}

bool Sequencer::setMotionRecording(bool active) {
    Command c;
    c.type = Command::Type::MotionRecording;
    c.flag = active;
    return push(c);
}

bool Sequencer::selectPattern(int slot, int pattern) {
    if (!validSlot(slot) || !validPattern(pattern)) return true;
    Command c;
    c.type = Command::Type::SelectPattern;
    c.slot = slot;
    c.pattern = pattern;
    return push(c);
}

bool Sequencer::setPatternSettings(int slot, int pattern, int length, int rate, float shuffle, float gate) {
    if (!validSlot(slot) || !validPattern(pattern)) return true;
    Command c;
    c.type = Command::Type::Settings;
    c.slot = slot;
    c.pattern = pattern;
    c.a = std::clamp(length, 1, kMaxSteps);
    c.b = std::clamp(rate, 0, kStepRateCount - 1);
    const float s = std::clamp(finiteOr(shuffle, 0.0f), 0.0f, 1.0f);
    c.step = static_cast<int32_t>(std::lround(s * static_cast<float>(Pattern::kShuffleResolution)));
    c.f0 = std::clamp(finiteOr(gate, Pattern::kDefaultGate), Pattern::kMinGate, 1.0f);
    return push(c);
}

Hit Sequencer::sanitizeHit(const Hit& hit) {
    Hit h;
    h.note = std::clamp(hit.note, 0, 127);
    h.velocity = std::clamp(finiteOr(hit.velocity, Hit::kDefaultVelocity), Hit::kMinVelocity, 1.0f);
    h.length = (std::isnan(hit.length) || hit.length < 0.0f) ? Hit::kPatternGate
                                                              : std::clamp(hit.length, Hit::kMinLength, Hit::kMaxLength);
    h.probability = std::clamp(finiteOr(hit.probability, 1.0f), 0.0f, 1.0f);
    h.nudge = std::clamp(finiteOr(hit.nudge, 0.0f), -Hit::kMaxNudge, Hit::kMaxNudge);
    return h;
}

bool Sequencer::setStep(int slot, int pattern, int step, const Hit* hits, int hitCount) {
    if (!validSlot(slot) || !validPattern(pattern) || !validStep(step)) return true;
    Command c;
    c.type = Command::Type::SetStep;
    c.slot = slot;
    c.pattern = pattern;
    c.step = step;
    c.hits.hitCount = hits ? std::clamp(hitCount, 0, kMaxHitsPerStep) : 0;
    for (int i = 0; i < c.hits.hitCount; ++i) c.hits.hits[static_cast<std::size_t>(i)] = sanitizeHit(hits[i]);
    return push(c);
}

bool Sequencer::setMotionValue(int slot, int pattern, int target, int step, float value) {
    if (!validSlot(slot) || !validPattern(pattern) || !validStep(step) || !validTarget(slot, target)) return true;
    Command c;
    c.type = Command::Type::MotionValue;
    c.slot = slot;
    c.pattern = pattern;
    c.step = step;
    c.a = target;
    c.f0 = sanitizeTarget(slot, target, value);
    return push(c);
}

bool Sequencer::clearMotionLane(int slot, int pattern, int target) {
    if (!validSlot(slot) || !validPattern(pattern) || !validTarget(slot, target)) return true;
    Command c;
    c.type = Command::Type::ClearLane;
    c.slot = slot;
    c.pattern = pattern;
    c.a = target;
    return push(c);
}

bool Sequencer::clearPattern(int slot, int pattern) {
    if (!validSlot(slot) || !validPattern(pattern)) return true;
    Command c;
    c.type = Command::Type::ClearPattern;
    c.slot = slot;
    c.pattern = pattern;
    return push(c);
}

bool Sequencer::setStepLoop(int slot, int start, int end) {
    if (!validSlot(slot)) return true;
    Command c;
    c.type = Command::Type::StepLoop;
    c.slot = slot;
    if (start < 0 || end < 0) {
        c.a = c.b = -1;
    } else {
        if (!validStep(start) || !validStep(end)) return true;
        c.a = std::min(start, end);
        c.b = std::max(start, end);
    }
    return push(c);
}

bool Sequencer::recordMotion(int slot, int target, float value) {
    if (!validSlot(slot) || !validTarget(slot, target) || std::isnan(value)) return true;
    Command c;
    c.type = Command::Type::RecordMotion;
    c.slot = slot;
    c.a = target;
    c.f0 = sanitizeTarget(slot, target, value);
    return push(c);
}

TransportState Sequencer::transport() const {
    TransportState t;
    t.playing = outPlaying_.load(std::memory_order_relaxed);
    t.recordArmed = outArmed_.load(std::memory_order_relaxed);
    t.tempoBpm = static_cast<float>(outTempoTenths_.load(std::memory_order_relaxed)) * 0.1f;
    for (std::size_t s = 0; s < kSlotCount; ++s) {
        t.currentStep[s] = outStep_[s].load(std::memory_order_relaxed);
        t.currentPattern[s] = outPattern_[s].load(std::memory_order_relaxed);
        t.queuedPattern[s] = outQueued_[s].load(std::memory_order_relaxed);
    }
    return t;
}

bool Sequencer::validTarget(int slot, int target) {
    if (!validSlot(slot) || target < 0) return false;
    if (slot == kDrumSlot) return target < DrumMachine::kSoundCount * static_cast<int>(kDrumParamCount);
    return target < static_cast<int>(kParamCount);
}

float Sequencer::sanitizeTarget(int slot, int target, float value) {
    if (std::isnan(value) || !validTarget(slot, target)) return kNaN;
    if (slot == kDrumSlot) {
        const auto id = static_cast<DrumParamId>(target % static_cast<int>(kDrumParamCount));
        return sanitizeDrumParam(id, value);
    }
    return sanitizeParam(static_cast<ParamId>(target), value);
}

// --- audio thread ---

void Sequencer::applyCommands(int64_t frame, int maxCommands) {
    Command c;
    for (int i = 0; i < maxCommands && commands_->pop(c); ++i) apply(c, frame);
    publish();
}

void Sequencer::apply(const Command& c, int64_t frame) {
    switch (c.type) {
        case Command::Type::Play:
            doPlay(frame);
            break;
        case Command::Type::Stop:
            doStop();
            break;
        case Command::Type::Tempo:
            doSetTempo(c.a, frame);
            break;
        case Command::Type::RecordArmed:
            armed_ = c.flag;
            break;
        case Command::Type::MotionRecording:
            motionRecording_ = c.flag;
            if (!c.flag) {
                for (SlotState& s : *slots_) s.touchedCount = 0;
            }
            break;
        case Command::Type::SelectPattern:
            doSelectPattern(c.slot, c.pattern);
            break;
        case Command::Type::Settings: {
            Pattern& p = pat(c.slot, c.pattern);
            p.length = c.a;
            p.rate = static_cast<StepRate>(c.b);
            p.shuffleMilli = c.step;
            p.gate = c.f0;
            patternEdited(c.slot, c.pattern);
            break;
        }
        case Command::Type::SetStep: {
            pat(c.slot, c.pattern).steps[static_cast<std::size_t>(c.step)] = c.hits;
            break;
        }
        case Command::Type::MotionValue: {
            Pattern& p = pat(c.slot, c.pattern);
            MotionLane* lane = std::isnan(c.f0) ? p.lane(c.a) : p.laneOrNew(c.a);
            if (lane) lane->values[static_cast<std::size_t>(c.step)] = c.f0;
            break;
        }
        case Command::Type::ClearLane: {
            if (MotionLane* lane = pat(c.slot, c.pattern).lane(c.a)) lane->clear();
            laneCleared(c.slot, c.pattern, c.a);
            break;
        }
        case Command::Type::ClearPattern:
            pat(c.slot, c.pattern).clear();
            patternEdited(c.slot, c.pattern);
            break;
        case Command::Type::StepLoop: {
            SlotState& s = (*slots_)[static_cast<std::size_t>(c.slot)];
            s.loopActive = c.a >= 0;
            if (s.loopActive) {
                s.loopStart = c.a;
                s.loopEnd = c.b;
                s.loopNext = c.a;
            }
            break;
        }
        case Command::Type::RecordMotion:
            doRecordMotion(c.slot, c.a, c.f0);
            break;
    }
}

void Sequencer::doPlay(int64_t frame) {
    if (playing_) return;
    playing_ = true;
    anchorFrame_ = frame;
    anchorPosition_ = 0;
    playFrame_ = frame;
    ++playCount_;
    for (int slot = 0; slot < kSlotCount; ++slot) {
        SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
        if (s.queued >= 0) s.current = s.queued;
        s.queued = -1;
        s.resetRuntime();
        s.rng = seedFor(slot);  // F3.4: every play repeats exactly
        scheduleNext(slot);
    }
}

void Sequencer::doStop() {
    for (int slot = 0; slot < kSlotCount; ++slot) {
        SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
        if (Instrument* inst = instruments_[static_cast<std::size_t>(slot)]) {
            inst->seqAllNotesOff();
            inst->clearAllMotion();
        }
        if (s.queued >= 0) s.current = s.queued;
        s.queued = -1;
        s.loopActive = false;  // the UI ends its step loops itself too; never leave one behind
        s.resetRuntime();
    }
    playing_ = false;
}

void Sequencer::doSetTempo(int32_t tenths, int64_t frame) {
    if (tenths == tempoTenths_) return;
    if (playing_) {
        // Re-anchor at this frame: exact integers, so the clock stays exact.
        anchorPosition_ = positionAt(frame);
        anchorFrame_ = frame;
    }
    tempoTenths_ = tenths;
    if (playing_) {
        for (SlotState& s : *slots_) {
            s.nextEventFrame = frameOf(s.nextEventPosition);
            for (int32_t i = 0; i < s.pendingCount; ++i) {
                SlotState::PendingHit& h = s.pending[static_cast<std::size_t>(i)];
                h.frame = std::max(frameOf(h.position), frame);
            }
        }
    }
    const float bpm = static_cast<float>(tenths) * 0.1f;
    for (Instrument* inst : instruments_) {
        if (inst) inst->setTempo(bpm);
    }
}

void Sequencer::doSelectPattern(int slot, int pattern) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    if (!playing_) {
        s.current = pattern;
        s.lastPattern = pattern;
        s.queued = -1;
        return;
    }
    // F3.9: takes effect at the slot's next pattern boundary, never mid-pattern.
    s.queued = pattern == s.current ? -1 : pattern;
}

void Sequencer::doRecordMotion(int slot, int target, float value) {
    if (!playing_ || !motionRecording_) return;
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    if (s.lastStep < 0) return;
    Pattern& p = pat(slot, s.lastPattern);
    if (!p.hasLane(target)) {
        bool freeLane = false;
        for (const MotionLane& l : p.lanes) freeLane = freeLane || !l.used();
        if (!freeLane) return;  // a 9th target is ignored, and not reported
    }
    // Report first: if the UI can't be told, the engine doesn't write either, so the two
    // copies never disagree.
    const RecordedEvent ev{RecordedEvent::Kind::Motion, slot, s.lastPattern, s.lastStep, target, value};
    if (!recorded_.push(ev)) return;
    p.laneOrNew(target)->values[static_cast<std::size_t>(s.lastStep)] = value;

    if (!touched(slot, target) && s.touchedCount < SlotState::kMaxOverrides) {
        s.touched[static_cast<std::size_t>(s.touchedCount++)] = target;
        removeOverride(slot, target);  // the live strip is what you hear while recording
    }
}

void Sequencer::recordLiveNote(int slot, int note, float velocity, int64_t frame) {
    if (!playing_ || !armed_ || !validSlot(slot) || note < 0 || note > 127) return;
    if (slot == kDrumSlot &&
        (note < DrumMachine::kLowestNote || note >= DrumMachine::kLowestNote + DrumMachine::kSoundCount)) {
        return;
    }
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    velocity = std::clamp(finiteOr(velocity, Hit::kDefaultVelocity), Hit::kMinVelocity, 1.0f);

    // F3.6: the nearest step by exact position (ties go to the earlier step).
    const int64_t position = positionAt(frame);
    const bool useLast =
        s.lastStep >= 0 && (position - s.lastEventPosition) <= (s.nextEventPosition - position);
    const int step = useLast ? s.lastStep : nextPlayedStep(slot);
    const int pattern = useLast ? s.lastPattern : nextPattern(slot);

    const RecordedEvent ev{RecordedEvent::Kind::Note, slot, pattern, step, note, velocity};
    if (!recorded_.push(ev)) return;
    Hit hit;
    hit.note = note;
    hit.velocity = velocity;
    pat(slot, pattern).steps[static_cast<std::size_t>(step)].addHit(hit);
    dropPendingHits(slot, pattern, step, note);  // the key plays it now, not the waiting hit

    if (!useLast) {
        if (s.suppressStep != step || s.suppressPattern != pattern) {
            s.suppressStep = step;
            s.suppressPattern = pattern;
            s.suppressCount = 0;
        }
        if (s.suppressCount < kMaxHitsPerStep) s.suppressNotes[static_cast<std::size_t>(s.suppressCount++)] = note;
    }
}

void Sequencer::patternEdited(int slot, int pattern) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    if (!playing_ || pattern != s.current) return;
    const Pattern& p = pat(slot, pattern);
    if (s.nextVirtual >= p.length) s.nextVirtual = 0;  // shortened: the next step is the boundary
    for (int i = s.overrideCount - 1; i >= 0; --i) {
        const int target = s.overrides[static_cast<std::size_t>(i)].target;
        if (!p.hasLane(target)) removeOverride(slot, target);
    }
    scheduleNext(slot);  // the shuffle may have changed
}

void Sequencer::laneCleared(int slot, int pattern, int target) {
    const SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    if (playing_ && pattern == s.current) removeOverride(slot, target);
}

int32_t Sequencer::advance(int64_t frame, int32_t maxFrames) {
    maxFrames = std::max<int32_t>(1, maxFrames);
    if (!playing_) return maxFrames;
    int64_t next = frame + maxFrames;
    for (int slot = 0; slot < kSlotCount; ++slot) {
        fireDue(slot, frame);
        const SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
        next = std::min(next, s.nextEventFrame);
        for (int32_t i = 0; i < s.offCount; ++i) next = std::min(next, s.offs[static_cast<std::size_t>(i)].frame);
        for (int32_t i = 0; i < s.pendingCount; ++i) next = std::min(next, s.pending[static_cast<std::size_t>(i)].frame);
    }
    publish();
    return static_cast<int32_t>(std::max<int64_t>(1, next - frame));
}

void Sequencer::fireDue(int slot, int64_t frame) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    Instrument* inst = instruments_[static_cast<std::size_t>(slot)];
    for (int32_t i = 0; i < s.offCount;) {
        const SlotState::Off off = s.offs[static_cast<std::size_t>(i)];
        if (off.frame <= frame) {
            if (inst) inst->seqNoteOff(off.note);
            s.offs[static_cast<std::size_t>(i)] = s.offs[static_cast<std::size_t>(--s.offCount)];
        } else {
            ++i;
        }
    }
    // Waiting hits now due, earliest first (normally none or one).
    for (;;) {
        int32_t best = -1;
        for (int32_t i = 0; i < s.pendingCount; ++i) {
            const SlotState::PendingHit& h = s.pending[static_cast<std::size_t>(i)];
            if (h.frame <= frame && (best < 0 || h.position < s.pending[static_cast<std::size_t>(best)].position)) best = i;
        }
        if (best < 0) break;
        const SlotState::PendingHit h = s.pending[static_cast<std::size_t>(best)];
        s.pending[static_cast<std::size_t>(best)] = s.pending[static_cast<std::size_t>(--s.pendingCount)];
        sound(slot, h.note, h.velocity, h.position, h.lengthUnits, frame);
    }
    // Normally at most one step is due; a few more only after an edit made one overdue.
    for (int guard = 0; guard < 4 && s.nextEventFrame <= frame; ++guard) fireStep(slot, frame);
}

void Sequencer::fireStep(int slot, int64_t frame) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    if (s.nextVirtual == 0 && s.queued >= 0) {
        switchPattern(slot, s.queued);
        s.queued = -1;
    }
    const Pattern& p = pat(slot, s.current);
    if (s.nextVirtual >= p.length) s.nextVirtual = 0;
    const int step = nextPlayedStep(slot);
    const int64_t eventPosition = s.nextEventPosition;
    const int64_t units = stepUnits(p.rate);

    applyMotion(slot, p, step);

    const Step& st = p.steps[static_cast<std::size_t>(step)];
    const bool suppress = s.suppressStep == step && s.suppressPattern == s.current;
    const bool looked = s.lookStep == step && s.lookPattern == s.current;
    for (int32_t i = 0; i < st.hitCount; ++i) {
        const Hit& hit = st.hits[static_cast<std::size_t>(i)];
        bool skip = false;
        if (looked) {
            for (int32_t k = 0; k < s.lookCount; ++k) skip = skip || s.lookNotes[static_cast<std::size_t>(k)] == hit.note;
        }
        if (suppress) {
            for (int32_t k = 0; k < s.suppressCount; ++k) skip = skip || s.suppressNotes[static_cast<std::size_t>(k)] == hit.note;
        }
        if (skip || !roll(slot, hit)) continue;
        // An early hit the look-ahead didn't schedule sounds on its step.
        const int64_t position = eventPosition + (hit.nudge > 0.0f ? nudgeUnits(hit.nudge, units) : 0);
        queueHit(slot, hit, p, s.current, step, position, frame, eventPosition);
    }
    s.lookStep = -1;
    s.lookCount = 0;
    s.suppressStep = -1;
    s.suppressCount = 0;

    s.lastStep = step;
    s.lastPattern = s.current;
    s.lastEventPosition = eventPosition;

    s.gridPosition += units;
    if (++s.nextVirtual >= p.length) s.nextVirtual = 0;
    if (s.loopActive) {
        const int end = std::min(s.loopEnd, p.length - 1);
        const int start = std::min(s.loopStart, end);
        s.loopNext = step + 1 > end || step < start ? start : step + 1;
    }
    scheduleNext(slot);
    lookAhead(slot, frame, eventPosition);
}

// Schedules the early hits of the step that will really play next (loop, length wrap and a
// switch due at the boundary are already in nextPlayedStep / nextPattern).
void Sequencer::lookAhead(int slot, int64_t frame, int64_t stepPosition) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    const int pattern = nextPattern(slot);
    const int step = nextPlayedStep(slot);
    const Pattern& p = pat(slot, pattern);
    const int64_t units = stepUnits(p.rate);
    const Step& st = p.steps[static_cast<std::size_t>(step)];
    s.lookStep = step;
    s.lookPattern = pattern;
    s.lookCount = 0;
    for (int32_t i = 0; i < st.hitCount; ++i) {
        const Hit& hit = st.hits[static_cast<std::size_t>(i)];
        if (hit.nudge >= 0.0f) continue;
        s.lookNotes[static_cast<std::size_t>(s.lookCount++)] = hit.note;  // at most kMaxHitsPerStep
        if (!roll(slot, hit)) continue;
        queueHit(slot, hit, p, pattern, step, s.nextEventPosition + nudgeUnits(hit.nudge, units), frame, stepPosition);
    }
}

bool Sequencer::roll(int slot, const Hit& hit) {
    if (hit.probability >= 1.0f) return true;
    return nextUnit((*slots_)[static_cast<std::size_t>(slot)].rng) < hit.probability;  // F3.4
}

// A hit that passed its chance roll, at its exact position: sounds now if that is due (or
// already past: an early hit can't sound before the step that scheduled it, at
// stepPosition), else waits.
void Sequencer::queueHit(int slot, const Hit& hit, const Pattern& p, int pattern, int step, int64_t position,
                         int64_t frame, int64_t stepPosition) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    const float length = hit.length < 0.0f ? p.gate : hit.length;
    const auto lengthUnits =
        static_cast<int64_t>(std::llround(static_cast<double>(length) * static_cast<double>(stepUnits(p.rate))));
    const int64_t due = frameOf(position);
    if (due <= frame) {
        sound(slot, hit.note, hit.velocity, std::max(position, stepPosition), lengthUnits, frame);
        return;
    }
    if (s.pendingCount >= kMaxPendingHits) {
        // Never expected (see kMaxPendingHits): the earliest waiting hit sounds now, early.
        int32_t first = 0;
        for (int32_t i = 1; i < s.pendingCount; ++i) {
            if (s.pending[static_cast<std::size_t>(i)].position < s.pending[static_cast<std::size_t>(first)].position) first = i;
        }
        const SlotState::PendingHit h = s.pending[static_cast<std::size_t>(first)];
        s.pending[static_cast<std::size_t>(first)] = s.pending[static_cast<std::size_t>(--s.pendingCount)];
        sound(slot, h.note, h.velocity, h.position, h.lengthUnits, frame);
    }
    s.pending[static_cast<std::size_t>(s.pendingCount++)] = {hit.note, hit.velocity, pattern, step, position, due, lengthUnits};
}

// Note-on now (frame), as of exact position; its off follows lengthUnits later at the
// current tempo, at least kMinNoteSeconds after now. A note still held is released first.
void Sequencer::sound(int slot, int note, float velocity, int64_t position, int64_t lengthUnits, int64_t frame) {
    Instrument* inst = instruments_[static_cast<std::size_t>(slot)];
    if (cancelOff(slot, note) && inst) inst->seqNoteOff(note);
    if (inst) inst->seqNoteOn(note, velocity);
    scheduleOff(slot, note, std::max(frameOf(position + lengthUnits), frame + minNoteFrames_));
}

void Sequencer::dropPendingHits(int slot, int pattern, int step, int note) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    for (int32_t i = 0; i < s.pendingCount;) {
        const SlotState::PendingHit& h = s.pending[static_cast<std::size_t>(i)];
        if (h.pattern == pattern && h.step == step && h.note == note) {
            s.pending[static_cast<std::size_t>(i)] = s.pending[static_cast<std::size_t>(--s.pendingCount)];
        } else {
            ++i;
        }
    }
}

void Sequencer::scheduleNext(int slot) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    const Pattern& p = pat(slot, s.current);
    // F3.3: odd steps (0-based) are late by shuffle * 2/3 of a step. Step 0 of the next
    // pattern is even, so a queued switch doesn't change this.
    const int64_t offset = (s.nextVirtual % 2 == 1) ? shuffleUnits(p) : 0;
    s.nextEventPosition = s.gridPosition + offset;
    s.nextEventFrame = frameOf(s.nextEventPosition);
}

int Sequencer::nextPattern(int slot) const {
    const SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    return (s.nextVirtual == 0 && s.queued >= 0) ? s.queued : s.current;
}

int Sequencer::nextPlayedStep(int slot) const {
    const SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    if (s.nextVirtual == 0 && s.queued >= 0) return 0;  // the switch clears the loop
    const int length = pat(slot, nextPattern(slot)).length;
    if (!s.loopActive) return std::min(s.nextVirtual, length - 1);
    const int end = std::min(s.loopEnd, length - 1);
    const int start = std::min(s.loopStart, end);
    return std::clamp(s.loopNext, start, end);
}

void Sequencer::switchPattern(int slot, int pattern) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    s.current = pattern;
    s.loopActive = false;  // a step loop belongs to the pattern it was set on
    // A pattern with no lane for a target clears that override.
    const Pattern& p = pat(slot, pattern);
    for (int i = s.overrideCount - 1; i >= 0; --i) {
        const int target = s.overrides[static_cast<std::size_t>(i)].target;
        if (!p.hasLane(target)) removeOverride(slot, target);
    }
}

void Sequencer::applyMotion(int slot, const Pattern& p, int step) {
    for (const MotionLane& lane : p.lanes) {
        if (!lane.used()) continue;
        const float v = lane.values[static_cast<std::size_t>(step)];
        if (std::isnan(v) || touched(slot, lane.target)) continue;
        setOverride(slot, lane.target, v);
    }
}

void Sequencer::setOverride(int slot, int target, float value) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    Instrument* inst = instruments_[static_cast<std::size_t>(slot)];
    for (int32_t i = 0; i < s.overrideCount; ++i) {
        SlotState::Override& o = s.overrides[static_cast<std::size_t>(i)];
        if (o.target != target) continue;
        if (o.value == value) return;
        o.value = value;
        if (inst) inst->setMotion(target, value);
        return;
    }
    if (s.overrideCount >= SlotState::kMaxOverrides) return;  // can't happen: at most 8 lanes
    s.overrides[static_cast<std::size_t>(s.overrideCount++)] = {target, value};
    if (inst) inst->setMotion(target, value);
}

void Sequencer::removeOverride(int slot, int target) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    for (int32_t i = 0; i < s.overrideCount; ++i) {
        if (s.overrides[static_cast<std::size_t>(i)].target != target) continue;
        s.overrides[static_cast<std::size_t>(i)] = s.overrides[static_cast<std::size_t>(--s.overrideCount)];
        if (Instrument* inst = instruments_[static_cast<std::size_t>(slot)]) inst->setMotion(target, kNaN);
        return;
    }
}

bool Sequencer::touched(int slot, int target) const {
    const SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    for (int32_t i = 0; i < s.touchedCount; ++i) {
        if (s.touched[static_cast<std::size_t>(i)] == target) return true;
    }
    return false;
}

void Sequencer::scheduleOff(int slot, int note, int64_t frame) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    if (s.offCount >= kMaxPendingOffs) {
        // Can't happen while each note has at most one off (sound() releases a held note
        // first); kept so the list can never overflow: the oldest note ends now.
        int32_t oldest = 0;
        for (int32_t i = 1; i < s.offCount; ++i) {
            const uint32_t age = s.offOrder - s.offs[static_cast<std::size_t>(i)].order;
            if (age > s.offOrder - s.offs[static_cast<std::size_t>(oldest)].order) oldest = i;
        }
        if (Instrument* inst = instruments_[static_cast<std::size_t>(slot)]) inst->seqNoteOff(s.offs[static_cast<std::size_t>(oldest)].note);
        s.offs[static_cast<std::size_t>(oldest)] = s.offs[static_cast<std::size_t>(--s.offCount)];
    }
    s.offs[static_cast<std::size_t>(s.offCount++)] = {note, s.offOrder++, frame};
}

bool Sequencer::cancelOff(int slot, int note) {
    SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    for (int32_t i = 0; i < s.offCount; ++i) {
        if (s.offs[static_cast<std::size_t>(i)].note != note) continue;
        s.offs[static_cast<std::size_t>(i)] = s.offs[static_cast<std::size_t>(--s.offCount)];
        return true;
    }
    return false;
}

void Sequencer::publish() {
    outPlaying_.store(playing_, std::memory_order_relaxed);
    outArmed_.store(armed_, std::memory_order_relaxed);
    outTempoTenths_.store(tempoTenths_, std::memory_order_relaxed);
    for (std::size_t i = 0; i < kSlotCount; ++i) {
        const SlotState& s = (*slots_)[i];
        const bool started = playing_ && s.lastStep >= 0;
        outStep_[i].store(started ? s.lastStep : -1, std::memory_order_relaxed);
        outPattern_[i].store(started ? s.lastPattern : s.current, std::memory_order_relaxed);
        outQueued_[i].store(s.queued, std::memory_order_relaxed);
    }
}

// --- tape link ---

int64_t Sequencer::roundedBarFrames() const {
    // 4 beats of 60 / bpm seconds = 2400 * sampleRate / tempoTenths frames.
    const int64_t num = 2400LL * sampleRate_;
    return (2 * num + tempoTenths_) / (2 * static_cast<int64_t>(tempoTenths_));
}

int64_t Sequencer::nextBarFrame(int64_t frame) const {
    // The first bar whose position is > the position of frame - 1: its frame is >= frame.
    const int64_t before = positionAt(frame - 1);
    const int64_t bar = barUnits();
    int64_t k = before / bar;
    if (before % bar != 0 && before < 0) --k;  // floor
    return frameOf((k + 1) * bar);
}

Sequencer::PatternPass Sequencer::nextPatternPass(int slot) const {
    PatternPass pass;
    if (!validSlot(slot)) return pass;
    const SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    const Pattern& cur = pat(slot, s.current);
    const int remaining = s.nextVirtual == 0 ? 0 : std::max(0, cur.length - s.nextVirtual);
    pass.startPosition = s.gridPosition + static_cast<int64_t>(remaining) * stepUnits(cur.rate);
    const Pattern& next = pat(slot, s.queued >= 0 ? s.queued : s.current);
    pass.units = static_cast<int64_t>(next.length) * stepUnits(next.rate);
    pass.startFrame = frameOf(pass.startPosition);
    return pass;
}

Sequencer::PatternPass Sequencer::currentPatternPass(int slot) const {
    PatternPass pass;
    if (!validSlot(slot)) return pass;
    const SlotState& s = (*slots_)[static_cast<std::size_t>(slot)];
    const Pattern& cur = pat(slot, s.current);
    pass.startPosition = s.gridPosition - static_cast<int64_t>(s.nextVirtual) * stepUnits(cur.rate);
    pass.units = static_cast<int64_t>(cur.length) * stepUnits(cur.rate);
    pass.startFrame = frameOf(pass.startPosition);
    return pass;
}

Pattern& Sequencer::pat(int slot, int pattern) {
    return (*patterns_)[static_cast<std::size_t>(slot)][static_cast<std::size_t>(pattern)];
}

const Pattern& Sequencer::pat(int slot, int pattern) const {
    return (*patterns_)[static_cast<std::size_t>(slot)][static_cast<std::size_t>(pattern)];
}

const Pattern& Sequencer::pattern(int slot, int pattern) const {
    return pat(std::clamp(slot, 0, kSlotCount - 1), std::clamp(pattern, 0, kPatternCount - 1));
}

int64_t Sequencer::shuffleUnits(const Pattern& p) const {
    // stepUnits is a multiple of kShuffleDivisor for every rate, so this is exact.
    return stepUnits(p.rate) / kShuffleDivisor * p.shuffleMilli;
}

int64_t Sequencer::frameOf(int64_t position) const {
    return anchorFrame_ + ceilDiv(position - anchorPosition_, unitsPerFrame());
}

int64_t Sequencer::positionAt(int64_t frame) const {
    return anchorPosition_ + (frame - anchorFrame_) * unitsPerFrame();
}

}  // namespace groove
