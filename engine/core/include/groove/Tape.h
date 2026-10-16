#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <memory>

#include "groove/SpscQueue.h"

namespace groove {

// Tape readout (NativeEngine.kt TapeState), published by the audio thread through atomics.
struct TapeState {
    static constexpr int kTracks = 4;
    int64_t lengthFrames = 0;  // 0 = empty tape; always lengthBars * barFrames
    int32_t lengthBars = 0;
    int64_t barFrames = 0;  // fixed by the first take; 0 when empty
    int64_t headFrame = 0;
    int32_t recordingTrack = -1;
    int32_t armedTrack = -1;  // armed record or pending commit, waiting for its start
    bool canUndo = false;
    bool canRedo = false;
    std::array<bool, kTracks> hasAudio{};
};

// The 4-track looping tape (docs/feature-spec.md F5, docs/plans/2026-10-16-m3-tape-mixer-fx.md).
//
// Storage: 16-bit stereo at the stream rate, kTracks x (2 minutes + 2 crossfade lengths),
// allocated (zeroed lazily by the OS) in prepare() and kept across prepare() at the same
// rate. int16 full scale is +-kHeadroom (6 dB above 0 dBFS), because the slot mix it records
// is pre-limiter; louder input clips. An undo pool (a ring of int16 frames) takes the rest of
// the 256 MB budget.
//
// Time: the tape has its own frame clock. The head starts at the loop start on Play and
// returns there on Stop. Tape bar lines are loop start + k * barFrames, where barFrames is a
// bar (4 beats) at the tempo of the first take, kept afterwards (a later tempo change doesn't
// stretch the tape). The first take on an empty tape starts on a sequencer bar line instead
// (or at Play) and stops on a whole number of its own bars.
//
// Writes: every record run (old * dub + input) is preceded by a crossfade-length lead-in
// that ramps the new input in, taken from an input history, and followed by a tail that
// ramps it out, so punch-in, punch-out and every loop wrap are click-free in the stored
// audio. The pre- and post-roll areas around the tape hold the lead-in before frame 0 and
// the tail after the end. Playback wraps the loop with an equal-power crossfade over its last
// kLoopFadeSeconds against the audio just before the loop start (forward), or its first
// kLoopFadeSeconds against the audio just after the loop end (reverse), so a loop's
// downbeat keeps its attack.
//
// Speed 0.5x/1x/2x and reverse: the head moves in exact half frames; half-frame positions
// read through a 16-tap windowed-sinc interpolator and 2x reads through a 32-tap low-pass
// at 0.21 of the tape rate (anti-alias for the 2:1 decimation). Play/Stop, speed and loop jumps crossfade the head (kVoiceFadeSeconds).
// Recording runs only at 1x forward: speed, reverse and the loop region are latched while a
// take runs and take effect when it ends.
//
// Undo/redo (the last kMaxUndo actions: a take or commit, or a clear): before a frame is
// first changed, it is copied into the pool (copy-on-write per action), and undo or redo
// swaps the pool copy with the tape, incrementally (kOpFramesPerFrame tape frames per
// rendered frame, so a full 2-minute track takes under 100 ms). When the pool is full the
// oldest actions are dropped. While an undo, redo or clear runs, later commands wait.
//
// Threading:
//  - Control thread (exactly one): the command methods (false if the queue is full, nothing
//    queued; out-of-range tracks/slots are ignored and return true), state(), overview().
//  - Audio thread: beginChunk(), commitPassRequest(), setCommitPasses(), process(). They
//    never allocate, lock, log or do I/O.
//  - prepare() and applyPendingOffline() only while the audio thread can't run.
class Tape {
public:
    static constexpr int kTracks = TapeState::kTracks;
    static constexpr int kSources = 9;  // 0: full mix (with FX), 1..8: slot 0..7 post-fader
    static constexpr int kOverviewBins = 256;
    static constexpr int kMaxUndo = 8;
    static constexpr int kMaxBars = 64;
    static constexpr int kMaxSegments = 8;
    static constexpr int32_t kMaxBlockFrames = 256;
    static constexpr std::size_t kCommandCapacity = 256;
    static constexpr int32_t kOverviewBlock = 256;  // frames per block peak
    static constexpr float kHeadroom = 2.0f;
    static constexpr double kLoopFadeSeconds = 0.010;
    static constexpr double kVoiceFadeSeconds = 0.005;
    static constexpr double kStartFadeSeconds = 0.001;
    // An arm or commit applied this soon after Play starts at the Play frame, from the input
    // history (the UI sends play() and the tape call together).
    static constexpr double kRetroSeconds = 0.050;
    static constexpr int64_t kOpFramesPerFrame = 1250;

    struct Limits {
        double maxSeconds = 120.0;          // per track
        int64_t budgetBytes = 256000000;    // tape + undo pool
    };

    // Per-chunk parameters (from MixParams).
    struct Params {
        int32_t loopStartBars = 0;
        int32_t loopLengthBars = 0;  // 0 = whole tape
        int32_t speed = 1;           // 0: 0.5x, 1: 1x, 2: 2x
        bool reverse = false;
        float dub = 1.0f;
    };

    // Per-chunk transport link (from the sequencer, before it advances).
    struct Transport {
        bool playing = false;
        uint32_t playCount = 0;
        int64_t playFrame = 0;
        int64_t nextBarFrame = -1;  // first sequencer bar line >= chunk start (playing)
        int64_t barFrames = 96000;  // a bar at the current tempo, rounded
    };

    // A commit's timing, from the sequencer: `next` starts at the next pattern boundary,
    // `fromPlay` is the pass that started at Play (valid only during the first pass).
    struct CommitPass {
        bool valid = false;
        int64_t startFrame = 0;
        int64_t frames = 0;  // exact pass length (slot), or 0 with bars set (all slots)
        int32_t bars = 0;
    };

    Tape();
    explicit Tape(Limits limits);
    ~Tape();
    Tape(const Tape&) = delete;
    Tape& operator=(const Tape&) = delete;

    void prepare(float sampleRate);

    // --- control thread ---
    bool armRecord(int track);
    bool stopRecord();
    bool commit(int track, int sourceSlot);  // sourceSlot -1 = all slots (full mix)
    bool clearTrack(int track);
    bool undo();
    bool redo();
    TapeState state() const;
    // kOverviewBins peaks (0..255, 255 = 0 dBFS or louder) across the tape length.
    void overview(int track, uint8_t* out) const;

    // --- audio thread, once per render chunk, in this order ---
    void beginChunk(int64_t chunkStart, const Transport& transport, const Params& params);
    // True when a commit waits for its start: slot is its source (-1 = all slots).
    bool commitPassRequest(int& slot) const;
    void setCommitPasses(const CommitPass& next, const CommitPass& fromPlay);
    // sources[k] = {left, right} of each source for numFrames (<= kMaxBlockFrames; more are
    // processed in pieces). Writes each track's playback (written, not added) to outL/outR.
    void process(const float* const* sourcesL, const float* const* sourcesR, int32_t numFrames,
                 float* const* outL, float* const* outR);
    // Source the tape is or will be recording (0 = mix, 1 + slot).
    int inputSource() const;

    // --- only while the audio thread can't run (no stream) ---
    // Applies every queued command and completes any running undo/redo/clear and tail.
    void applyPendingOffline();

    // --- tests only (audio thread not running) ---
    float sample(int track, int64_t frame, int channel) const;  // float value of a tape frame
    int64_t maxFrames() const { return maxFrames_; }
    int64_t poolCapacityFrames() const { return poolFrames_; }
    int64_t poolUsedFrames() const { return poolUsed_; }
    int undoCount() const { return undoCount_; }
    int redoCount() const { return entryCount_ - undoCount_; }
    int64_t crossfadeFrames() const { return xfade_; }
    int64_t trackBytes() const { return trackFrames_ * 4; }
    bool busy() const { return op_.type != OpType::None || tailCount_ > 0; }
    int64_t loopStart() const { return loopS_; }
    int64_t loopEnd() const { return loopE_; }

private:
    struct FreeDeleter {
        void operator()(int16_t* p) const { std::free(p); }
    };
    struct Command {
        enum class Type : int32_t { None, Arm, Stop, Commit, Clear, Undo, Redo };
        Type type = Type::None;
        int32_t track = 0;
        int32_t slot = -1;
    };
    struct Segment {
        int64_t start = 0;  // tape frame
        int64_t frames = 0;
    };
    struct Entry {
        int32_t track = 0;
        int64_t otherLength = 0;
        int64_t otherBar = 0;
        bool otherHasAudio = false;
        std::array<Segment, kMaxSegments> segs{};
        int32_t segCount = 0;
        int64_t poolStart = 0;  // ring index of its first frame
        int64_t poolCount = 0;  // frames
        bool overflow = false;
    };
    enum class OpType { None, Clear, Undo, Redo };
    struct Op {
        OpType type = OpType::None;
        int32_t entry = 0;  // ring slot
        int32_t seg = 0;
        int64_t done = 0;   // frames done in seg
        int64_t poolDone = 0;
    };
    enum class TakeState { None, Armed, CommitPending, Recording };
    struct Tail {
        int32_t track = 0;
        int64_t pos = 0;  // tape frame of the tail's start
        int64_t done = 0;
    };
    struct Voice {
        bool active = false;
        int64_t head2 = 0;  // half frames
        int32_t step2 = 2;  // +-1, +-2, +-4
        float gain = 1.0f;
        float gainStep = 0.0f;
    };

    bool push(const Command& c) { return commands_.push(c); }
    bool tryExecute(const Command& c, bool offline);
    void startOp(OpType type, int track);
    void runOp(int64_t budget);
    void finishOp();
    void endTake();
    void startTake(int32_t pos, int64_t retroFrames);
    void cancelPending() { take_ = TakeState::None; }
    void finalizeEntryIfDone();

    void processPiece(int32_t n);
    int64_t eventDistance(int32_t pos, int32_t n) const;
    bool handleEvents(int32_t pos);
    void renderPlayback(int32_t pos, int32_t run);
    void readFrame(const int16_t* base, int64_t head2, int32_t absStep2, float& l, float& r) const;
    void writeRange(int track, int64_t start, int64_t frames, const float* inL, const float* inR, float w0,
                    float dw, int32_t dubOffset);
    void writeLeadIn(int track, int64_t before, int32_t pos);
    void historyAt(int32_t pos, int64_t back, int64_t count, float* l, float* r) const;
    void runTails(int32_t pos, int32_t run);
    void startTail(int track, int64_t pos);

    // undo pool
    bool saveRange(int64_t start, int64_t end);
    bool reserve(int64_t frames);
    int64_t poolFree() const { return poolFrames_ - poolUsed_; }
    Entry& entryAt(int i) { return entries_[static_cast<std::size_t>((entryStart_ + i) % kMaxUndo)]; }
    Entry* openEntry();
    void dropOldest();
    void clearHistory();
    void newEntry(int track);
    void copyToPool(int track, int64_t start, int64_t frames, int64_t ring);
    void swapWithPool(int track, int64_t start, int64_t frames, int64_t ring);

    // loop and voices
    void computeLoop();
    int32_t effectiveStep2() const;
    void applyStep(int32_t step2);
    void jumpTo(int64_t head2, double fadeSeconds);
    bool onBarLine(int64_t head) const;
    int64_t framesToBarLine(int64_t head) const;
    bool headRunning() const;

    // overview
    void markDirty(int track, int64_t start, int64_t end);
    void flushOverview();
    void rescanBlocks(int track, int64_t start, int64_t end);
    void recomputeBins(int track, int64_t start, int64_t end);
    void recomputeAllBins();
    int64_t overviewLength() const;
    void publish();

    int16_t* trackBase(int track) const { return storage_.get() + static_cast<std::size_t>(track) * static_cast<std::size_t>(trackFrames_) * 2; }
    int16_t* at(int track, int64_t frame) const { return trackBase(track) + (offset_ + frame) * 2; }

    Limits limits_;
    SpscQueue<Command, kCommandCapacity> commands_;

    // --- storage (prepare) ---
    int32_t sampleRate_ = 0;
    int64_t maxFrames_ = 0;
    int64_t xfade_ = 480;
    int64_t voiceFade_ = 240;
    int64_t startFade_ = 48;
    int64_t retroFrames_ = 2400;
    int64_t guard_ = 0;
    int64_t offset_ = 0;       // storage frame of tape frame 0
    int64_t trackFrames_ = 0;  // storage frames per track
    std::unique_ptr<int16_t, FreeDeleter> storage_;
    std::unique_ptr<int16_t[]> pool_;
    int64_t poolFrames_ = 0;
    std::unique_ptr<uint16_t[]> blockPeak_;  // kTracks x blocks
    int64_t blocks_ = 0;
    std::unique_ptr<float[]> history_;  // kSources x 2 x historySize_
    int64_t historySize_ = 0;           // power of two
    int64_t historyEnd_ = 0;            // frames written so far (ring position)
    std::unique_ptr<float[]> scratch_;  // lead-in input, 2 x xfade_
    std::array<float, 16> interpHalf_{};  // half-frame interpolator (16 taps)
    std::array<float, 32 * 2> lowpass_{};  // 2x anti-alias low-pass, phase 0 then phase 0.5

    // --- audio-thread state ---
    int64_t frame_ = 0;  // stream frame of the next piece
    Transport transport_;
    Params params_;
    Params latched_;
    bool wasPlaying_ = false;
    uint32_t lastPlayCount_ = 0;
    int64_t playFrame_ = 0;
    int64_t length_ = 0;
    int64_t bar_ = 0;
    std::array<bool, kTracks> hasAudio_{};
    int64_t loopS_ = 0, loopE_ = 0;
    Voice main_, old_;
    float dub_ = 1.0f;
    std::array<float, kMaxBlockFrames> dubRamp_{};
    const float* const* srcL_ = nullptr;
    const float* const* srcR_ = nullptr;
    float* const* outL_ = nullptr;
    float* const* outR_ = nullptr;
    int32_t pieceOffset_ = 0;
    int32_t pieceFrames_ = 0;

    TakeState take_ = TakeState::None;
    int32_t takeTrack_ = 0;
    int32_t takeSource_ = 0;
    int32_t takeSourceForTails_ = 0;
    bool retroOk_ = false;
    bool takeCommit_ = false;
    bool takeFirst_ = false;
    bool takeEnded_ = true;  // the take's entry waits only for its tails
    bool stopPending_ = false;
    int64_t written_ = 0;
    int64_t commitFrames_ = 0;
    int64_t firstLimit_ = 0;
    CommitPass nextPass_, playPass_;
    Command deferred_;

    std::array<Tail, 2> tails_{};
    int32_t tailCount_ = 0;

    std::array<Entry, kMaxUndo> entries_{};
    int32_t entryStart_ = 0;
    int32_t entryCount_ = 0;
    int32_t undoCount_ = 0;
    bool entryOpen_ = false;
    int64_t poolTail_ = 0;
    int64_t poolUsed_ = 0;
    Op op_;

    struct Dirty {
        int64_t start = 0, end = 0;
    };
    std::array<std::array<Dirty, 4>, kTracks> dirty_{};
    std::array<int32_t, kTracks> dirtyCount_{};
    int64_t binLength_ = 0;  // length the published bins are scaled to

    // --- readout ---
    std::atomic<int64_t> outLength_{0};
    std::atomic<int32_t> outLengthBars_{0};
    std::atomic<int64_t> outBar_{0};
    std::atomic<int64_t> outHead_{0};
    std::atomic<int32_t> outRecording_{-1};
    std::atomic<int32_t> outArmed_{-1};
    std::atomic<bool> outCanUndo_{false};
    std::atomic<bool> outCanRedo_{false};
    std::array<std::atomic<bool>, kTracks> outHasAudio_{};
    std::array<std::array<std::atomic<uint8_t>, kOverviewBins>, kTracks> bins_{};
};

}  // namespace groove
