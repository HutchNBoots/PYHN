#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>

#include "groove/Instrument.h"
#include "groove/Pattern.h"
#include "groove/SpscQueue.h"

namespace groove {

// Something the audio thread wrote into a pattern while recording (F3.6, F3.7). Same fields
// and rules as RecordedEvent in NativeEngine.kt:
//  - Note: the step turns on, the note is added if not already there (with 4 notes, the
//    last one is replaced) and the step's velocity becomes value.
//  - Motion: lane noteOrTarget's value at the step becomes value (a new lane is taken only
//    when one of the 8 is free; otherwise nothing is written or reported).
struct RecordedEvent {
    enum class Kind : int32_t { Note = 0, Motion = 1 };
    Kind kind = Kind::Note;
    int32_t slot = 0;
    int32_t pattern = 0;
    int32_t step = 0;
    int32_t noteOrTarget = 0;
    float value = 0.0f;
};

struct TransportState {
    bool playing = false;
    bool recordArmed = false;
    float tempoBpm = 120.0f;
    std::array<int32_t, kSlotCount> currentStep{};     // last triggered step, -1 when stopped
    std::array<int32_t, kSlotCount> currentPattern{};  // playing (or selected, when stopped)
    std::array<int32_t, kSlotCount> queuedPattern{};   // starts at the next pattern boundary, or -1
};

// The step sequencer (docs/feature-spec.md F3): one global clock, 8 slots x 16 patterns.
//
// Timing (F3.1, F3.3). The musical position is an exact integer: one beat is
// kUnitsPerBeatPerHz * sampleRate units and one frame advances it by 60 * tempo-in-0.1-BPM
// units, so the tempo grid (40.0..240.0 in 0.1 steps), every step rate (1/8, 1/16, 1/32,
// 1/16T) and every shuffle amount (0.1% resolution) are whole numbers of units. An event at
// position P lands on the first frame whose position is >= P:
//     frame = anchorFrame + ceil((P - anchorPosition) / unitsPerFrame)
// No float is ever accumulated, so there is no drift at all, however long it plays. At a
// constant tempo, step k of a 1/16 pattern started at frame F0 lands exactly on
// F0 + ceil(k * 600 * sampleRate / (4 * tempoTenths)). A tempo change re-anchors at the
// frame it is applied (still exact integers). int64 positions last for more than 100 hours
// of continuous play at 192 kHz.
//
// Each slot steps through its current pattern at that pattern's rate; all slots start
// together on play(). A step's grid time is the previous step's grid time plus one step at
// the previous step's rate; odd-numbered steps (0-based) are delayed by shuffle * 2/3 of a
// step. The shuffle parity, pattern boundary and queued switch follow the "virtual"
// position, which keeps running under a step loop, so clearing the loop returns exactly to
// where the pattern would have been (F3.8).
//
// Threading:
//  - Control thread (exactly one): the command methods (each queued; false if the queue is
//    full and nothing was queued), popRecorded(), transport().
//  - Audio thread: applyCommands(), recordLiveNote(), advance(). They never allocate, lock,
//    log or do I/O. Instruments are called only from these (seqNoteOn/Off, setMotion...).
//  - attach(), prepare() and the test accessors only while the audio thread can't run.
//    prepare() stops the transport but keeps every pattern and queued command.
//
// Every index and value is validated: out-of-range slots, patterns, steps and targets are
// ignored, and values are clamped exactly like the UI's mirror (PatternStore.kt).
class Sequencer {
public:
    static constexpr std::size_t kCommandCapacity = 4096;
    static constexpr std::size_t kRecordedCapacity = 1024;
    static constexpr int64_t kUnitsPerBeatPerHz = 36000;
    static constexpr int32_t kMinTempoTenths = 400;
    static constexpr int32_t kMaxTempoTenths = 2400;
    static constexpr int32_t kDefaultTempoTenths = 1200;
    static constexpr float kMinNoteSeconds = 0.001f;  // shortest gate
    static constexpr int kMaxPendingOffs = 32;        // per slot
    // Commands applied per render chunk, which bounds the audio thread's work when the UI
    // sends a burst (e.g. loading a session); the rest wait, in order, for the next chunk.
    static constexpr int kMaxCommandsPerChunk = 512;

    Sequencer();
    ~Sequencer();
    Sequencer(const Sequencer&) = delete;
    Sequencer& operator=(const Sequencer&) = delete;

    // --- setup (audio thread not running) ---
    void attach(int slot, Instrument* instrument);
    void prepare(float sampleRate);

    // --- control thread: commands, applied at the start of the next render chunk ---
    bool play();
    bool stop();  // rewinds every slot; sounding sequencer notes are released
    bool setTempo(float bpm);  // clamped to 40..240 and rounded to 0.1 BPM; NaN ignored
    bool setRecordArmed(bool armed);
    bool setMotionRecording(bool active);
    bool selectPattern(int slot, int pattern);
    bool setPatternSettings(int slot, int pattern, int length, int rate, float shuffle, float gate);
    bool setStep(int slot, int pattern, int step, bool on, const int* notes, int noteCount, float velocity,
                 float gate, float probability);
    bool setMotionValue(int slot, int pattern, int target, int step, float value);
    bool clearMotionLane(int slot, int pattern, int target);
    bool clearPattern(int slot, int pattern);
    // start..end inclusive; start < 0 or end < 0 clears the loop.
    bool setStepLoop(int slot, int start, int end);
    // A parameter moved while motion recording (F3.7): written at the slot's current step
    // when recording is active and the transport is playing, ignored otherwise.
    bool recordMotion(int slot, int target, float value);

    bool popRecorded(RecordedEvent& out) { return recorded_.pop(out); }
    TransportState transport() const;  // lock-free, any thread

    // Motion targets: synth slots use ParamId, the drum slot key * kDrumParamCount + id.
    static bool validTarget(int slot, int target);
    // Clamps value to the target parameter's range (NaN stays NaN: "no value").
    static float sanitizeTarget(int slot, int target, float value);
    static int32_t tempoTenths(float bpm);  // 40..240 BPM in 0.1 steps

    // --- audio thread ---
    // Applies queued commands (up to maxCommands, in order) at stream frame `frame`. Call at
    // the start of each render chunk, before advance().
    void applyCommands(int64_t frame, int maxCommands = kMaxCommandsPerChunk);
    // A note-on from the keys that the instruments apply at `frame` (F3.6). While armed and
    // playing it is written to the nearest step of the slot's current pattern.
    void recordLiveNote(int slot, int note, float velocity, int64_t frame);
    // Fires every event due at `frame` (event frame <= frame) to the instruments, then
    // returns how many frames (1..maxFrames) the caller can render before the next event.
    int32_t advance(int64_t frame, int32_t maxFrames);

    // --- tests only (audio thread not running) ---
    const Pattern& pattern(int slot, int pattern) const;
    int64_t unitsPerBeat() const { return unitsPerBeat_; }

private:
    struct Command;
    struct SlotState;

    bool push(const Command& c);
    void apply(const Command& c, int64_t frame);
    void doPlay(int64_t frame);
    void doStop();
    void doSetTempo(int32_t tenths, int64_t frame);
    void doSelectPattern(int slot, int pattern);
    void doRecordMotion(int slot, int target, float value);
    void patternEdited(int slot, int pattern);
    void laneCleared(int slot, int pattern, int target);

    void fireDue(int slot, int64_t frame);
    void fireStep(int slot, int64_t frame);
    void switchPattern(int slot, int pattern);
    void applyMotion(int slot, const Pattern& p, int step);
    void setOverride(int slot, int target, float value);
    void removeOverride(int slot, int target);
    bool touched(int slot, int target) const;
    void scheduleOff(int slot, int note, int64_t frame);
    bool cancelOff(int slot, int note);
    void scheduleNext(int slot);
    int nextPlayedStep(int slot) const;
    int nextPattern(int slot) const;
    void publish();

    Pattern& pat(int slot, int pattern);
    const Pattern& pat(int slot, int pattern) const;
    int64_t stepUnits(StepRate rate) const { return unitsPerBeat_ / stepsPerBeat(rate); }
    int64_t shuffleUnits(const Pattern& p) const;
    int64_t frameOf(int64_t position) const;
    int64_t positionAt(int64_t frame) const;
    int64_t unitsPerFrame() const { return 60 * static_cast<int64_t>(tempoTenths_); }

    std::unique_ptr<std::array<std::array<Pattern, kPatternCount>, kSlotCount>> patterns_;
    std::unique_ptr<std::array<SlotState, kSlotCount>> slots_;
    std::array<Instrument*, kSlotCount> instruments_{};

    std::unique_ptr<SpscQueue<Command, kCommandCapacity>> commands_;
    SpscQueue<RecordedEvent, kRecordedCapacity> recorded_;

    // --- audio-thread state ---
    int32_t sampleRate_ = 48000;
    int64_t unitsPerBeat_ = kUnitsPerBeatPerHz * 48000;
    int32_t tempoTenths_ = kDefaultTempoTenths;
    int64_t minNoteFrames_ = 48;
    bool playing_ = false;
    bool armed_ = false;
    bool motionRecording_ = false;
    int64_t anchorFrame_ = 0;
    int64_t anchorPosition_ = 0;

    // --- transport readout (written by the audio thread, read anywhere) ---
    std::atomic<bool> outPlaying_{false};
    std::atomic<bool> outArmed_{false};
    std::atomic<int32_t> outTempoTenths_{kDefaultTempoTenths};
    std::array<std::atomic<int32_t>, kSlotCount> outStep_{};
    std::array<std::atomic<int32_t>, kSlotCount> outPattern_{};
    std::array<std::atomic<int32_t>, kSlotCount> outQueued_{};
};

}  // namespace groove
