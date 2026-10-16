#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <vector>

#include "groove/SpscByteRing.h"
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
    bool loading = false;  // a saved tape is loading (F7.6)
};

// --- M4 persistence, load and export links (docs/plans/2026-10-16-m4-keep-it.md) ---

// One record in the tape's persistence ring, written by the audio thread and read by the
// writer thread (TapeStore). A Data record is followed by `frames` interleaved int16 stereo
// frames, a copy of the tape storage at the time it was written.
struct TapePersistRecord {
    enum Type : uint32_t { Data = 1, Reset = 2, Meta = 3 };
    uint32_t type = 0;
    int32_t track = 0;
    int64_t frame = 0;       // Data: first tape frame
    int64_t frames = 0;      // Data: frame count
    int64_t preroll = 0;     // storage layout: file frame 0 holds tape frame -preroll
    int64_t fileFrames = 0;  // frames per track file
    int64_t length = 0;      // Meta: tape length (a first take in progress counts its bars so far)
    int64_t bar = 0;         // Meta
    uint64_t sequence = 0;   // Meta: the change counter this state includes
    uint32_t hasAudioMask = 0;
    uint32_t sampleRate = 0;
};

// Shared between the audio thread (producer) and the writer thread (consumer). Allocated by
// the control thread before it is attached and kept alive until the tape is destroyed.
struct TapePersistPort {
    explicit TapePersistPort(std::size_t ringBytes) : ring(ringBytes) {}
    SpscByteRing ring;
    // Writer -> audio: the files lost track of the tape (an I/O error): send everything again.
    std::atomic<bool> resyncRequested{false};
};

// A saved tape read by the loader thread, swapped in by the audio thread. Read-only once offered.
struct TapeImage {
    int64_t lengthFrames = 0;
    int64_t barFrames = 0;
    uint32_t hasAudioMask = 0;
    int64_t lo = 0, hi = 0;  // tape frames covered by data (the rest of the storage is zero)
    std::array<std::vector<int16_t>, TapeState::kTracks> data;  // (hi - lo) * 2 per track with audio
};

// An export snapshot (F7.2): the control thread allocates the buffers and fills the request;
// the audio thread copies the tape into it incrementally (copy-on-write while it runs, so the
// copy is the tape exactly as it was when the copy started) and fills in the layout.
struct TapeSnapshot {
    static constexpr int64_t kBlockFrames = 4096;
    // --- request (control thread, before Tape::requestSnapshot) ---
    int16_t* data = nullptr;
    int64_t capacityFrames = 0;  // frames over all tracks
    uint8_t* copied = nullptr;   // one zeroed byte per block
    int64_t blockCapacity = 0;
    bool wholeTape = false;
    // --- result (audio thread; read-only once Ready) ---
    int32_t sampleRate = 0;
    int64_t lengthFrames = 0;
    int64_t barFrames = 0;
    int64_t loopStart = 0, loopEnd = 0;  // the exported pass
    int64_t fade = 1;                    // loop crossfade frames
    int64_t rangeStart = 0;              // first tape frame held (loopStart - fade)
    int64_t rangeFrames = 0;
    uint32_t hasAudioMask = 0;
    int32_t trackCount = 0;
    std::array<int32_t, TapeState::kTracks> slotOfTrack{{-1, -1, -1, -1}};
    int64_t blocksPerTrack() const { return (rangeFrames + kBlockFrames - 1) / kBlockFrames; }
    const int16_t* frame(int slot, int64_t tapeFrame) const {
        return data + (static_cast<int64_t>(slot) * rangeFrames + (tapeFrame - rangeStart)) * 2;
    }
};

enum class TapeLoadPhase : int32_t { Idle, Reading, Ready, Landing, Done, Failed, Cancelled };
enum class TapeSnapshotPhase : int32_t { Idle, Requested, Copying, Ready, Failed };

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
//
// Persistence (F7.5): once a TapePersistPort is attached, every frame the audio thread changes
// (takes, tails, clears, undo/redo swaps, a load landing after earlier changes) is marked in a
// per-track "not yet sent" range list. At the end of every piece the audio thread copies up
// to kPersistFramesPerPiece of those frames from the storage into the port's ring (whatever
// doesn't fit stays marked, so nothing is dropped: an overflowing ring just delays it, and
// the pending ranges merge into a full-track rewrite at worst). Before the first change to a
// track without audio it sends Reset (that track's storage is all zero then). Once nothing is
// pending and no edit is half done, it sends a Meta record: a consistent point the writer
// may commit. The audio thread never touches a file.
//
// Load (F7.6): the loader thread offers a TapeImage; the audio thread then empties the tape
// and copies the image in incrementally (kOpFramesPerFrame frames per rendered frame, like
// undo), with the tape silent and commands waiting, and publishes the new length at the end.
// Undo history starts empty. state().loading covers reading and landing.
//
// Export snapshot (F7.2): requestSnapshot() hands a preallocated buffer to the audio thread,
// which copies the tracks with audio over the exported pass (plus the loop crossfade's
// partner frames before it) block by block, copying a block first whenever it is about to
// change it, so the snapshot is exact even while a take or an undo runs.
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
    static constexpr int64_t kPersistFramesPerPiece = 32768;  // ~128 KB copied per piece at most
    static constexpr int kPersistRanges = 8;                  // pending ranges per track

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
    // Applies every queued command and completes any running undo/redo/clear and tail, a
    // load landing and an export snapshot, and sends what fits to the persistence ring.
    void applyPendingOffline();

    // --- persistence, load and snapshot (M4) ---
    struct Layout {
        int32_t sampleRate = 0;
        int64_t offset = 0;       // storage frames before tape frame 0
        int64_t trackFrames = 0;  // storage frames per track
        int64_t maxFrames = 0;    // longest tape
        int64_t crossfade = 0;
        int64_t guard = 0;
    };
    // Control thread (prepare() is the only writer of the layout).
    Layout layout() const;
    // Control thread, once: from now on the audio thread streams changes into the port.
    void attachPersistence(TapePersistPort* port);
    // The audio thread's change counter as of its last piece (any thread).
    uint64_t changeSequence() const { return outChangeSeq_.load(std::memory_order_acquire); }
    // Control thread: Idle/Done/Failed/Cancelled -> Reading (false if a load is active).
    bool beginLoad();
    // Loader thread: Reading -> Ready with the image (false if cancelled meanwhile; the image
    // stays the loader's). The image must stay alive until loadPhase() is Done or Cancelled.
    bool offerLoad(const TapeImage* image);
    void failLoad();  // loader thread: Reading -> Failed
    TapeLoadPhase loadPhase() const { return static_cast<TapeLoadPhase>(loadPhase_.load(std::memory_order_acquire)); }
    // Only while the audio thread can't run: drops an active load (the tape keeps what it has).
    void cancelLoad();
    // Control thread: Idle -> Requested (false if a snapshot is active or the request is bad).
    bool requestSnapshot(TapeSnapshot* snapshot);
    TapeSnapshotPhase snapshotPhase() const {
        return static_cast<TapeSnapshotPhase>(snapPhase_.load(std::memory_order_acquire));
    }
    // Exporter thread, once the snapshot is Ready or Failed: back to Idle (the buffers are free).
    void releaseSnapshot();
    // Only while the audio thread can't run: an active snapshot fails (its buffers may go).
    void cancelSnapshot();

    // The tape's 1x read path, shared with the offline export so both are bit-identical.
    static float tapeToFloat(int16_t s);
    // Loop crossfade weights for the main head at head2 (half frames) moving step2.
    static void loopFade(int64_t head2, int32_t step2, int64_t loopS, int64_t loopE, int64_t fade, float& cm, float& cp,
                         int64_t& partner2);
    static float blendLoop(float a, float partner, float cm, float cp);

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
    bool persistActive() const { return persistActive_; }
    int64_t persistPendingFrames() const;

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

    // persistence (audio thread)
    void persistMark(int track, int64_t start, int64_t end);
    void persistChanged() {
        if (persistActive_) ++changeSeq_;
    }
    void persistReset(int track);
    void persistResyncAll();
    void persistPiece(int64_t budget);
    bool persistPush(TapePersistPort& port, TapePersistRecord& rec, const void* payload, std::size_t bytes);
    int64_t metaLength() const;
    void extendWritten(int track, int64_t start, int64_t end);

    // load landing (audio thread)
    bool landing() const { return landPhase_ == TapeLoadPhase::Landing; }
    void startLanding();
    void runLanding(int64_t budget);
    void finishLanding();

    // export snapshot (audio thread)
    void acceptSnapshot();
    void runSnapshot(int64_t budget);
    void snapBeforeWrite(int track, int64_t start, int64_t end);
    void copySnapBlock(int slot, int64_t block);

    // overview
    void markDirty(int track, int64_t start, int64_t end);
    void markOverview(int track, int64_t start, int64_t end);
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

    // Written extent per track (tape frames; empty when lo >= hi): the storage is zero outside it.
    std::array<int64_t, kTracks> writtenLo_{}, writtenHi_{};

    // --- persistence (audio thread, except the atomics) ---
    std::atomic<TapePersistPort*> port_{nullptr};
    bool persistActive_ = false;
    bool persistTouched_ = false;  // something was sent since the port was attached
    std::array<std::array<Dirty, kPersistRanges>, kTracks> pdirty_{};
    std::array<int32_t, kTracks> pdirtyCount_{};
    std::array<bool, kTracks> preset_{};
    uint64_t changeSeq_ = 0;
    uint64_t emittedSeq_ = 0;
    std::atomic<uint64_t> outChangeSeq_{0};

    // --- load (phase shared; the rest audio thread once Ready) ---
    std::atomic<int32_t> loadPhase_{static_cast<int32_t>(TapeLoadPhase::Idle)};
    const TapeImage* image_ = nullptr;  // written by the loader before Ready
    TapeLoadPhase landPhase_ = TapeLoadPhase::Idle;  // the audio thread's view
    bool landMark_ = false;  // the landing also rewrites the files
    int32_t landTrack_ = 0;
    int64_t landPos_ = 0;

    // --- export snapshot ---
    std::atomic<int32_t> snapPhase_{static_cast<int32_t>(TapeSnapshotPhase::Idle)};
    TapeSnapshot* snap_ = nullptr;  // written by the control thread before Requested
    bool snapActive_ = false;       // audio thread: copying
    int64_t snapCursor_ = 0;        // next block over all tracks

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
