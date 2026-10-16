#include "groove/Tape.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace groove {

namespace {

constexpr float kToTape = 32767.0f / Tape::kHeadroom;
constexpr float kFromTape = Tape::kHeadroom / 32767.0f;
constexpr double kPi = 3.14159265358979323846;
constexpr int kTaps = 16;       // half-frame interpolator: taps cover p - 7 .. p + 8
constexpr int kTapCentre = 7;
constexpr int kLpTaps = 32;     // 2x anti-alias low-pass: taps cover p - 15 .. p + 16
constexpr int kLpCentre = 15;
constexpr double kLpCutoff = 0.21;  // of the tape rate: below the 2x output's Nyquist (0.25)
constexpr int64_t kLengthTolerance = 2;  // frames: a pass that long over whole bars stays whole

int16_t toTape(float x) {
    if (!(x == x)) return 0;  // NaN
    const float v = std::clamp(x * kToTape, -32768.0f, 32767.0f);
    return static_cast<int16_t>(std::lrint(v));
}

float fromTape(int16_t s) { return static_cast<float>(s) * kFromTape; }

int64_t floorDiv2(int64_t v) { return v >= 0 ? v / 2 : -((-v + 1) / 2); }

int64_t ceilDiv(int64_t a, int64_t b) { return a >= 0 ? (a + b - 1) / b : -((-a) / b); }

double sinc(double t) { return t == 0.0 ? 1.0 : std::sin(kPi * t) / (kPi * t); }

double blackman(double t, double half) {
    if (std::fabs(t) >= half) return 0.0;
    return 0.42 + 0.5 * std::cos(kPi * t / half) + 0.08 * std::cos(2.0 * kPi * t / half);
}

// taps[k] for k = 0..count-1 at offsets (k - centre) - frac; cutoff fc in cycles per sample;
// Blackman window over +-count/2, normalised to unity gain at DC.
void designTaps(float* taps, int count, int centre, double frac, double fc) {
    double sum = 0.0;
    double v[kLpTaps];
    for (int k = 0; k < count; ++k) {
        const double t = static_cast<double>(k - centre) - frac;
        v[k] = 2.0 * fc * sinc(2.0 * fc * t) * blackman(t, static_cast<double>(count) / 2.0);
        sum += v[k];
    }
    for (int k = 0; k < count; ++k) taps[k] = static_cast<float>(v[k] / sum);
}

bool validTrack(int track) { return track >= 0 && track < Tape::kTracks; }

bool loadActive(int32_t phase) {
    const auto p = static_cast<TapeLoadPhase>(phase);
    return p == TapeLoadPhase::Reading || p == TapeLoadPhase::Ready || p == TapeLoadPhase::Landing;
}

}  // namespace

// --- shared read path (live playback and offline export) ---

float Tape::tapeToFloat(int16_t s) { return fromTape(s); }

void Tape::loopFade(int64_t head2, int32_t step2, int64_t loopS, int64_t loopE, int64_t fade, float& cm, float& cp,
                    int64_t& partner2) {
    // Equal power, against the audio before the start (forward) or after the end (reverse).
    cm = 1.0f;
    cp = 0.0f;
    const int64_t loopLen = loopE - loopS;
    double u;
    if (step2 > 0) {
        u = static_cast<double>(head2 + 2 - 2 * (loopE - fade)) / static_cast<double>(2 * fade);
        partner2 = head2 - 2 * loopLen;
    } else {
        u = static_cast<double>(2 * (loopS + fade) - head2) / static_cast<double>(2 * fade);
        partner2 = head2 + 2 * loopLen;
    }
    if (u > 0.0) {
        u = std::min(u, 1.0);
        cm = static_cast<float>(std::cos(u * kPi * 0.5));
        cp = static_cast<float>(std::sin(u * kPi * 0.5));
    }
}

float Tape::blendLoop(float a, float partner, float cm, float cp) { return a * cm + partner * cp; }

Tape::Tape() : Tape(Limits{}) {}

Tape::Tape(Limits limits) : limits_(limits) {
    for (auto& a : outHasAudio_) a.store(false, std::memory_order_relaxed);
    for (auto& t : bins_) {
        for (auto& b : t) b.store(0, std::memory_order_relaxed);
    }
    designTaps(interpHalf_.data(), kTaps, kTapCentre, 0.5, 0.5);
    designTaps(lowpass_.data(), kLpTaps, kLpCentre, 0.0, kLpCutoff);
    designTaps(lowpass_.data() + kLpTaps, kLpTaps, kLpCentre, 0.5, kLpCutoff);
}

Tape::~Tape() = default;

// --- setup ---

void Tape::prepare(float sampleRate) {
    const auto rate = std::max<int32_t>(1, static_cast<int32_t>(std::lround(sampleRate)));
    if (rate == sampleRate_ && storage_) {
        // Same rate: keep the tape. Finish anything in flight (nothing renders now).
        if (take_ == TakeState::Recording) endTake();
        applyPendingOffline();
    } else {
        sampleRate_ = rate;
        const auto frames = [rate](double seconds, int64_t min) {
            return std::max<int64_t>(min, static_cast<int64_t>(std::llround(seconds * rate)));
        };
        xfade_ = frames(kLoopFadeSeconds, 16);
        voiceFade_ = frames(kVoiceFadeSeconds, 8);
        startFade_ = frames(kStartFadeSeconds, 4);
        retroFrames_ = frames(kRetroSeconds, 0);
        guard_ = 2 * xfade_ + 32;
        const int64_t overhead = 2 * (guard_ + xfade_);
        // The tape plus at least one track's worth of undo pool fit the budget.
        const int64_t byBudget = limits_.budgetBytes / 4 / (kTracks + 1) - overhead;
        maxFrames_ = std::max<int64_t>(1, std::min(frames(limits_.maxSeconds, 1), byBudget));
        offset_ = guard_ + xfade_;
        trackFrames_ = offset_ + maxFrames_ + xfade_ + guard_;

        const auto samples = static_cast<std::size_t>(kTracks) * static_cast<std::size_t>(trackFrames_) * 2;
        storage_.reset();
        storage_.reset(static_cast<int16_t*>(std::calloc(samples, sizeof(int16_t))));  // zero pages, lazily
        poolFrames_ = std::max<int64_t>(0, (limits_.budgetBytes - kTracks * trackFrames_ * 4) / 4);
        pool_.reset(new int16_t[static_cast<std::size_t>(std::max<int64_t>(1, poolFrames_)) * 2]);
        blocks_ = (trackFrames_ + kOverviewBlock - 1) / kOverviewBlock;
        blockPeak_.reset(new uint16_t[static_cast<std::size_t>(kTracks * blocks_)]());
        historySize_ = 1;
        while (historySize_ < retroFrames_ + xfade_ + kMaxBlockFrames + 1) historySize_ *= 2;
        history_.reset(new float[static_cast<std::size_t>(kSources * 2 * historySize_)]());
        scratch_.reset(new float[static_cast<std::size_t>(2 * (xfade_ + retroFrames_))]());

        // An empty tape, no history.
        length_ = 0;
        bar_ = 0;
        hasAudio_.fill(false);
        entryStart_ = entryCount_ = undoCount_ = 0;
        entryOpen_ = false;
        poolTail_ = poolUsed_ = 0;
        op_ = Op{};
        tailCount_ = 0;
        if (take_ == TakeState::Recording) take_ = TakeState::None;
        takeEnded_ = true;
        deferred_ = Command{};
        for (auto& d : dirtyCount_) d = 0;
        // M4: the new storage is empty; nothing pending for the files (they keep the old
        // tape until a new change), an active load or snapshot no longer fits.
        writtenLo_.fill(0);
        writtenHi_.fill(0);
        pdirtyCount_.fill(0);
        preset_.fill(false);
        cancelLoad();
        const auto snap = static_cast<TapeSnapshotPhase>(snapPhase_.load(std::memory_order_acquire));
        if (snap == TapeSnapshotPhase::Requested || snap == TapeSnapshotPhase::Copying) {
            snapActive_ = false;
            snapPhase_.store(static_cast<int32_t>(TapeSnapshotPhase::Failed), std::memory_order_release);
        }
    }
    std::fill(history_.get(), history_.get() + kSources * 2 * historySize_, 0.0f);
    historyEnd_ = 0;
    frame_ = 0;
    wasPlaying_ = false;
    main_ = Voice{};
    old_ = Voice{};
    retroOk_ = false;
    latched_ = params_;
    computeLoop();
    main_.head2 = 2 * loopS_;
    recomputeAllBins();
    publish();
}

// --- control thread ---

bool Tape::armRecord(int track) {
    if (!validTrack(track)) return true;
    return push({Command::Type::Arm, track, -1});
}

bool Tape::stopRecord() { return push({Command::Type::Stop, 0, -1}); }

bool Tape::commit(int track, int sourceSlot) {
    if (!validTrack(track) || sourceSlot < -1 || sourceSlot >= kSources - 1) return true;
    return push({Command::Type::Commit, track, sourceSlot});
}

bool Tape::clearTrack(int track) {
    if (!validTrack(track)) return true;
    return push({Command::Type::Clear, track, -1});
}

bool Tape::undo() { return push({Command::Type::Undo, 0, -1}); }
bool Tape::redo() { return push({Command::Type::Redo, 0, -1}); }

TapeState Tape::state() const {
    TapeState s;
    s.lengthFrames = outLength_.load(std::memory_order_relaxed);
    s.lengthBars = outLengthBars_.load(std::memory_order_relaxed);
    s.barFrames = outBar_.load(std::memory_order_relaxed);
    s.headFrame = outHead_.load(std::memory_order_relaxed);
    s.recordingTrack = outRecording_.load(std::memory_order_relaxed);
    s.armedTrack = outArmed_.load(std::memory_order_relaxed);
    s.canUndo = outCanUndo_.load(std::memory_order_relaxed);
    s.canRedo = outCanRedo_.load(std::memory_order_relaxed);
    for (std::size_t t = 0; t < kTracks; ++t) s.hasAudio[t] = outHasAudio_[t].load(std::memory_order_relaxed);
    s.loading = loadActive(loadPhase_.load(std::memory_order_acquire));
    return s;
}

void Tape::overview(int track, uint8_t* out) const {
    if (!out) return;
    for (int b = 0; b < kOverviewBins; ++b) {
        out[b] = validTrack(track) ? bins_[static_cast<std::size_t>(track)][static_cast<std::size_t>(b)].load(
                                         std::memory_order_relaxed)
                                   : 0;
    }
}

// --- audio thread: chunk start ---

void Tape::beginChunk(int64_t chunkStart, const Transport& transport, const Params& params) {
    frame_ = chunkStart;
    transport_ = transport;
    params_ = params;
    params_.speed = std::clamp(params_.speed, 0, 2);
    params_.loopStartBars = std::max(0, params_.loopStartBars);
    params_.loopLengthBars = std::max(0, params_.loopLengthBars);
    params_.dub = std::isnan(params_.dub) ? 1.0f : std::clamp(params_.dub, 0.0f, 1.0f);

    const bool started = transport.playing && transport.playCount != lastPlayCount_;
    const bool stopped = !transport.playing && wasPlaying_;
    if ((started && wasPlaying_) || stopped) {
        // Stop: the head fades out and returns to the loop start; a take ends here.
        if (main_.active && length_ > 0) {
            old_ = main_;
            old_.gainStep = -old_.gain / static_cast<float>(voiceFade_);
        }
        main_.active = false;
        if (take_ == TakeState::Recording) endTake();
        computeLoop();
        main_.head2 = 2 * loopS_;
        retroOk_ = false;
    }
    if (started) {
        playFrame_ = transport.playFrame;
        computeLoop();
        main_.active = true;
        main_.head2 = 2 * loopS_;
        main_.step2 = effectiveStep2();
        main_.gain = 0.0f;
        main_.gainStep = 1.0f / static_cast<float>(startFade_);
        retroOk_ = main_.step2 == 2;
    }
    wasPlaying_ = transport.playing;
    lastPlayCount_ = transport.playCount;

    // Live parameters (latched while a take records).
    if (take_ != TakeState::Recording) latched_ = params_;
    const int64_t oldS = loopS_, oldE = loopE_;
    computeLoop();
    if (length_ > 0 && (oldS != loopS_ || oldE != loopE_)) {
        const int64_t head = floorDiv2(main_.head2);
        if (head < loopS_ || head >= loopE_) {
            if (main_.active && headRunning()) {
                jumpTo(2 * loopS_, kVoiceFadeSeconds);
            } else {
                main_.head2 = 2 * loopS_;
            }
        }
    }
    const int32_t step = effectiveStep2();
    if (main_.active && step != main_.step2) {
        applyStep(step);
    } else {
        main_.step2 = step;
    }
    // F5.5: recording only at 1x forward; a waiting arm or commit is dropped otherwise.
    const bool forward1x = params_.speed == 1 && !params_.reverse;
    if ((take_ == TakeState::Armed || take_ == TakeState::CommitPending) && !forward1x) cancelPending();

    // F7.6: a loaded tape lands once no edit is running.
    if (!landing() && op_.type == OpType::None &&
        loadPhase_.load(std::memory_order_acquire) == static_cast<int32_t>(TapeLoadPhase::Ready)) {
        startLanding();
    }

    if (deferred_.type != Command::Type::None && tryExecute(deferred_, false)) deferred_ = Command{};
    Command c;
    while (deferred_.type == Command::Type::None && commands_.pop(c)) {
        if (!tryExecute(c, false)) deferred_ = c;
    }
    nextPass_ = CommitPass{};
    playPass_ = CommitPass{};

    if (snapPhase_.load(std::memory_order_acquire) == static_cast<int32_t>(TapeSnapshotPhase::Requested)) {
        acceptSnapshot();
    }
}

bool Tape::commitPassRequest(int& slot) const {
    if (take_ != TakeState::CommitPending) return false;
    slot = takeSource_ - 1;
    return true;
}

void Tape::setCommitPasses(const CommitPass& next, const CommitPass& fromPlay) {
    nextPass_ = next;
    playPass_ = fromPlay;
}

int Tape::inputSource() const {
    return (take_ == TakeState::None) ? 0 : takeSource_;
}

bool Tape::tryExecute(const Command& c, bool offline) {
    using T = Command::Type;
    switch (c.type) {
        case T::None:
            return true;
        case T::Arm:
        case T::Commit: {
            if (landing()) return false;  // wait for the loaded tape
            if (take_ == TakeState::Recording) return true;  // one take at a time
            if (params_.speed != 1 || params_.reverse) return true;  // F5.5
            take_ = c.type == T::Arm ? TakeState::Armed : TakeState::CommitPending;
            takeTrack_ = c.track;
            takeCommit_ = c.type == T::Commit;
            takeSource_ = takeCommit_ && c.slot >= 0 ? c.slot + 1 : 0;
            stopPending_ = false;
            return true;
        }
        case T::Stop:
            if (take_ == TakeState::Armed || take_ == TakeState::CommitPending) cancelPending();
            if (take_ == TakeState::Recording) stopPending_ = true;
            return true;
        case T::Clear:
        case T::Undo:
        case T::Redo: {
            if (landing()) return false;  // wait for the loaded tape
            // An edit during a take ends the take first (it is then undoable like any other).
            if (take_ == TakeState::Recording) endTake();
            if (offline) {
                for (int i = 0; i < tailCount_; ++i) {
                    Tail& t = tails_[static_cast<std::size_t>(i)];
                    const int64_t left = xfade_ - t.done;
                    writeRange(t.track, t.pos + t.done, left, nullptr, nullptr,
                               1.0f - static_cast<float>(t.done + 1) / static_cast<float>(xfade_),
                               -1.0f / static_cast<float>(xfade_), -1);
                }
                tailCount_ = 0;
                finalizeEntryIfDone();
                if (op_.type != OpType::None) runOp(std::numeric_limits<int64_t>::max());
            }
            if (tailCount_ > 0 || op_.type != OpType::None) return false;  // wait (bounded)
            finalizeEntryIfDone();
            if (c.type == T::Clear) {
                if (!hasAudio_[static_cast<std::size_t>(c.track)]) return true;
                startOp(OpType::Clear, c.track);
            } else if (c.type == T::Undo) {
                if (undoCount_ == 0) return true;
                startOp(OpType::Undo, 0);
            } else {
                if (entryCount_ == undoCount_) return true;
                startOp(OpType::Redo, 0);
            }
            if (offline) runOp(std::numeric_limits<int64_t>::max());
            return true;
        }
    }
    return true;
}

// --- takes ---

void Tape::startTake(int32_t pos, int64_t retro) {
    const CommitPass& pass = retro > 0 ? playPass_ : nextPass_;
    newEntry(takeTrack_);
    entryOpen_ = true;
    takeEnded_ = false;
    takeFirst_ = length_ == 0;
    if (takeFirst_) {
        bar_ = std::max<int64_t>(1, transport_.barFrames);
        const int64_t maxBars = std::clamp<int64_t>(maxFrames_ / bar_, 1, kMaxBars);
        firstLimit_ = maxBars * bar_;
        main_.head2 = 2 * retro;
    }
    if (takeCommit_) {
        commitFrames_ = pass.frames > 0 ? pass.frames : static_cast<int64_t>(pass.bars) * bar_;
        if (takeFirst_) commitFrames_ = std::min(commitFrames_, firstLimit_);
        commitFrames_ = std::max<int64_t>(1, commitFrames_);
    }
    take_ = TakeState::Recording;
    takeSourceForTails_ = takeSource_;
    latched_ = params_;
    stopPending_ = false;
    if (!hasAudio_[static_cast<std::size_t>(takeTrack_)]) persistReset(takeTrack_);  // its storage is all zero
    hasAudio_[static_cast<std::size_t>(takeTrack_)] = true;
    persistChanged();
    retroOk_ = false;

    const int64_t head = floorDiv2(main_.head2);
    const int64_t start = head - retro;
    writeLeadIn(takeTrack_, start, pos - static_cast<int32_t>(retro));
    written_ = 0;
    if (retro > 0) {
        float* l = scratch_.get();
        float* r = l + xfade_ + retroFrames_;
        historyAt(pos, retro, retro, l, r);
        writeRange(takeTrack_, start, retro, l, r, 1.0f, 0.0f, -1);
        written_ = retro;
    }
}

void Tape::endTake() {
    if (take_ != TakeState::Recording) return;
    const int64_t head = floorDiv2(main_.head2);
    take_ = TakeState::None;
    takeEnded_ = true;
    stopPending_ = false;
    latched_ = params_;
    persistChanged();
    startTail(takeTrack_, head);
    if (takeFirst_) {
        // The take's length in whole bars becomes the tape length (a commit or a transport
        // Stop rounds up; the rest of the last bar stays silent).
        const int64_t maxBars = firstLimit_ / bar_;
        const int64_t bars = std::clamp<int64_t>(ceilDiv(std::max<int64_t>(head - kLengthTolerance, 1), bar_), 1, maxBars);
        length_ = bars * bar_;
        computeLoop();
        if (main_.active) {
            main_.step2 = effectiveStep2();
            main_.head2 = 2 * loopS_;
            main_.gain = 0.0f;
            main_.gainStep = 1.0f / static_cast<float>(startFade_);
        } else {
            main_.head2 = 2 * loopS_;
        }
    }
    computeLoop();
}

void Tape::finalizeEntryIfDone() {
    if (!entryOpen_ || !takeEnded_ || tailCount_ > 0) return;
    entryOpen_ = false;
    if (entryCount_ > 0 && entryAt(entryCount_ - 1).overflow) clearHistory();  // couldn't be kept
}

void Tape::startTail(int track, int64_t pos) {
    if (tailCount_ == static_cast<int32_t>(tails_.size())) {
        tails_[0] = tails_[1];  // only with pathological lengths: the older one ends early
        tailCount_ = 1;
    }
    tails_[static_cast<std::size_t>(tailCount_++)] = Tail{track, pos, 0};
    // Keep the whole tail's old audio in the undo entry now: its writes alternate with the
    // next pass's (a loop wrap), and saving them piece by piece would open a new segment
    // each time and overflow the entry, which used to drop the whole undo history.
    if (entryOpen_ && entryCount_ > 0 && entryAt(entryCount_ - 1).track == track) {
        const int64_t lo = std::max(pos, -offset_);
        const int64_t hi = std::min(pos + xfade_, trackFrames_ - offset_);
        if (hi > lo) saveRange(lo, hi);
    }
}

void Tape::runTails(int32_t pos, int32_t run) {
    const float* l = srcL_[takeSourceForTails_] + pieceOffset_ + pos;
    const float* r = srcR_[takeSourceForTails_] + pieceOffset_ + pos;
    for (int i = 0; i < tailCount_;) {
        Tail& t = tails_[static_cast<std::size_t>(i)];
        const int64_t k = std::min<int64_t>(run, xfade_ - t.done);
        writeRange(t.track, t.pos + t.done, k, l, r, 1.0f - static_cast<float>(t.done + 1) / static_cast<float>(xfade_),
                   -1.0f / static_cast<float>(xfade_), pos);
        t.done += k;
        if (t.done >= xfade_) {
            for (int j = i; j + 1 < tailCount_; ++j) tails_[static_cast<std::size_t>(j)] = tails_[static_cast<std::size_t>(j + 1)];
            --tailCount_;
        } else {
            ++i;
        }
    }
    finalizeEntryIfDone();
}

// --- writes ---

void Tape::historyAt(int32_t pos, int64_t back, int64_t count, float* l, float* r) const {
    const auto src = static_cast<std::size_t>(takeSource_);
    const float* ringL = history_.get() + src * 2 * static_cast<std::size_t>(historySize_);
    const float* ringR = ringL + historySize_;
    for (int64_t i = 0; i < count; ++i) {
        const int64_t o = pos - back + i;  // offset in the current piece
        if (o >= 0) {
            l[i] = srcL_[src][pieceOffset_ + o];
            r[i] = srcR_[src][pieceOffset_ + o];
        } else if (historyEnd_ + o >= 0) {
            const auto k = static_cast<std::size_t>((historyEnd_ + o) & (historySize_ - 1));
            l[i] = ringL[k];
            r[i] = ringR[k];
        } else {
            l[i] = r[i] = 0.0f;
        }
    }
}

void Tape::writeLeadIn(int track, int64_t before, int32_t pos) {
    float* l = scratch_.get();
    float* r = l + xfade_ + retroFrames_;
    historyAt(pos, xfade_, xfade_, l, r);
    const float step = 1.0f / static_cast<float>(xfade_);
    writeRange(track, before - xfade_, xfade_, l, r, step, step, -1);
}

void Tape::writeRange(int track, int64_t start, int64_t frames, const float* inL, const float* inR, float w0,
                      float dw, int32_t dubOffset) {
    // Keep inside the storage (the layout guarantees it; this is a guard).
    const int64_t lo = -offset_, hi = trackFrames_ - offset_;
    if (start < lo) {
        const int64_t skip = lo - start;
        if (skip >= frames) return;
        start += skip;
        frames -= skip;
        w0 += dw * static_cast<float>(skip);
        if (inL) inL += skip;
        if (inR) inR += skip;
        if (dubOffset >= 0) dubOffset += static_cast<int32_t>(skip);
    }
    frames = std::min(frames, hi - start);
    if (frames <= 0) return;
    snapBeforeWrite(track, start, start + frames);
    if (entryOpen_) saveRange(start, start + frames);
    extendWritten(track, start, start + frames);
    int16_t* p = at(track, start);
    for (int64_t i = 0; i < frames; ++i) {
        const float w = std::clamp(w0 + dw * static_cast<float>(i), 0.0f, 1.0f);
        const float dub = dubOffset >= 0 ? dubRamp_[static_cast<std::size_t>(dubOffset + i)] : dub_;
        const float keep = 1.0f - w + w * dub;
        const float l = inL ? inL[i] : 0.0f;
        const float r = inR ? inR[i] : 0.0f;
        p[2 * i] = toTape(fromTape(p[2 * i]) * keep + w * l);
        p[2 * i + 1] = toTape(fromTape(p[2 * i + 1]) * keep + w * r);
    }
    markDirty(track, start, start + frames);
}

// --- undo pool ---

void Tape::newEntry(int track) {
    while (entryCount_ > undoCount_) {  // a new action discards the redo entries
        poolUsed_ -= entryAt(entryCount_ - 1).poolCount;
        --entryCount_;
    }
    if (entryCount_ == kMaxUndo) dropOldest();
    Entry& e = entryAt(entryCount_);
    e = Entry{};
    e.track = track;
    e.otherLength = length_;
    e.otherBar = bar_;
    e.otherHasAudio = hasAudio_[static_cast<std::size_t>(track)];
    e.poolStart = poolFrames_ > 0 ? (poolTail_ + poolUsed_) % poolFrames_ : 0;
    ++entryCount_;
    ++undoCount_;
}

void Tape::dropOldest() {
    if (entryCount_ == 0) return;
    const Entry& e = entryAt(0);
    if (poolFrames_ > 0) poolTail_ = (poolTail_ + e.poolCount) % poolFrames_;
    poolUsed_ -= e.poolCount;
    entryStart_ = (entryStart_ + 1) % kMaxUndo;
    --entryCount_;
    if (undoCount_ > 0) --undoCount_;
}

void Tape::clearHistory() {
    entryStart_ = entryCount_ = undoCount_ = 0;
    poolTail_ = poolUsed_ = 0;
}

bool Tape::reserve(int64_t frames) {
    while (poolFree() < frames) {
        if (entryCount_ <= 1) return false;
        dropOldest();  // F5.4: the oldest actions drop off when the budget is full
    }
    return true;
}

bool Tape::saveRange(int64_t start, int64_t end) {
    if (entryCount_ == 0) return false;
    Entry& e = entryAt(entryCount_ - 1);
    if (e.overflow) return false;
    int64_t cursor = start;
    while (cursor < end) {
        bool covered = false;
        int64_t nextStart = end;
        for (int32_t i = 0; i < e.segCount; ++i) {
            const Segment& s = e.segs[static_cast<std::size_t>(i)];
            if (cursor >= s.start && cursor < s.start + s.frames) {
                cursor = s.start + s.frames;
                covered = true;
                break;
            }
            if (s.start > cursor) nextStart = std::min(nextStart, s.start);
        }
        if (covered) continue;
        const int64_t stop = std::min(end, nextStart);
        const int64_t k = stop - cursor;
        Segment* last = e.segCount > 0 ? &e.segs[static_cast<std::size_t>(e.segCount - 1)] : nullptr;
        const bool extend = last && last->start + last->frames == cursor;
        if ((!extend && e.segCount == kMaxSegments) || !reserve(k)) {
            e.overflow = true;
            return false;
        }
        copyToPool(e.track, cursor, k, (e.poolStart + e.poolCount) % poolFrames_);
        if (extend) {
            last->frames += k;
        } else {
            e.segs[static_cast<std::size_t>(e.segCount++)] = Segment{cursor, k};
        }
        e.poolCount += k;
        poolUsed_ += k;
        cursor = stop;
    }
    return true;
}

void Tape::copyToPool(int track, int64_t start, int64_t frames, int64_t ring) {
    const int64_t first = std::min(frames, poolFrames_ - ring);
    std::memcpy(pool_.get() + ring * 2, at(track, start), static_cast<std::size_t>(first) * 4);
    if (frames > first) {
        std::memcpy(pool_.get(), at(track, start + first), static_cast<std::size_t>(frames - first) * 4);
    }
}

void Tape::swapWithPool(int track, int64_t start, int64_t frames, int64_t ring) {
    const int64_t first = std::min(frames, poolFrames_ - ring);
    int16_t* t = at(track, start);
    std::swap_ranges(t, t + first * 2, pool_.get() + ring * 2);
    if (frames > first) std::swap_ranges(t + first * 2, t + frames * 2, pool_.get());
}

void Tape::startOp(OpType type, int track) {
    op_ = Op{};
    op_.type = type;
    if (type == OpType::Clear) {
        newEntry(track);
        Entry& e = entryAt(entryCount_ - 1);
        const int64_t start = -xfade_;
        const int64_t frames = length_ + 2 * xfade_;
        e.segs[0] = Segment{start, frames};
        e.segCount = 1;
        if (reserve(frames)) {
            e.poolCount = frames;
            poolUsed_ += frames;
        } else {
            e.overflow = true;  // bigger than the pool: cleared without undo
        }
        op_.entry = (entryStart_ + entryCount_ - 1) % kMaxUndo;
    } else if (type == OpType::Undo) {
        op_.entry = (entryStart_ + undoCount_ - 1) % kMaxUndo;
    } else {
        op_.entry = (entryStart_ + undoCount_) % kMaxUndo;
    }
    if (type != OpType::Clear) {
        const int t = entries_[static_cast<std::size_t>(op_.entry)].track;
        if (!hasAudio_[static_cast<std::size_t>(t)]) persistReset(t);  // its storage is all zero
    }
}

void Tape::runOp(int64_t budget) {
    if (op_.type == OpType::None) return;
    Entry& e = entries_[static_cast<std::size_t>(op_.entry)];
    while (budget > 0 && op_.seg < e.segCount) {
        const Segment& s = e.segs[static_cast<std::size_t>(op_.seg)];
        const int64_t k = std::min(s.frames - op_.done, budget);
        const int64_t start = s.start + op_.done;
        snapBeforeWrite(e.track, start, start + k);
        extendWritten(e.track, start, start + k);
        if (op_.type == OpType::Clear) {
            if (!e.overflow) copyToPool(e.track, start, k, (e.poolStart + op_.poolDone) % std::max<int64_t>(1, poolFrames_));
            std::fill(at(e.track, start), at(e.track, start + k), int16_t{0});
        } else {
            swapWithPool(e.track, start, k, (e.poolStart + op_.poolDone) % poolFrames_);
        }
        markDirty(e.track, start, start + k);
        op_.done += k;
        op_.poolDone += k;
        budget -= k;
        if (op_.done >= s.frames) {
            ++op_.seg;
            op_.done = 0;
        }
    }
    if (op_.seg >= e.segCount) finishOp();
}

void Tape::finishOp() {
    Entry& e = entries_[static_cast<std::size_t>(op_.entry)];
    const auto t = static_cast<std::size_t>(e.track);
    const int64_t oldLength = length_;
    if (op_.type == OpType::Clear) {
        hasAudio_[t] = false;
        bool any = false;
        for (bool h : hasAudio_) any = any || h;
        if (!any) {  // every track cleared: the tape is empty again
            length_ = 0;
            bar_ = 0;
        }
        if (e.overflow) clearHistory();
    } else {
        std::swap(e.otherLength, length_);
        std::swap(e.otherBar, bar_);
        const bool h = hasAudio_[t];
        hasAudio_[t] = e.otherHasAudio;
        e.otherHasAudio = h;
        if (op_.type == OpType::Undo) {
            --undoCount_;
        } else {
            ++undoCount_;
        }
    }
    op_ = Op{};
    persistChanged();
    computeLoop();
    const int64_t head = floorDiv2(main_.head2);
    if (length_ == 0) {
        main_.head2 = 0;
    } else if (oldLength == 0 || head < loopS_ || head >= loopE_) {
        if (main_.active && headRunning()) {
            jumpTo(2 * loopS_, oldLength == 0 ? kStartFadeSeconds : kVoiceFadeSeconds);
        } else {
            main_.head2 = 2 * loopS_;
        }
    }
    recomputeAllBins();
}

// --- loop and head ---

void Tape::computeLoop() {
    if (length_ <= 0 || bar_ <= 0) {
        loopS_ = loopE_ = 0;
        return;
    }
    // Start clamped into the tape, end cut at the tape length; at least one bar.
    const int64_t bars = std::max<int64_t>(1, length_ / bar_);
    const int64_t startBar = std::clamp<int64_t>(latched_.loopStartBars, 0, bars - 1);
    loopS_ = startBar * bar_;
    loopE_ = latched_.loopLengthBars == 0 ? length_
                                          : std::min(length_, loopS_ + static_cast<int64_t>(latched_.loopLengthBars) * bar_);
}

int32_t Tape::effectiveStep2() const {
    if (take_ == TakeState::Recording) return 2;
    static constexpr int32_t kSteps[3] = {1, 2, 4};
    const int32_t s = kSteps[std::clamp(params_.speed, 0, 2)];
    return params_.reverse ? -s : s;
}

void Tape::applyStep(int32_t step2) {
    old_ = main_;
    old_.gainStep = -old_.gain / static_cast<float>(voiceFade_);
    main_.step2 = step2;
    main_.gain = 0.0f;
    main_.gainStep = 1.0f / static_cast<float>(voiceFade_);
    retroOk_ = false;
}

void Tape::jumpTo(int64_t head2, double fadeSeconds) {
    old_ = main_;
    old_.gainStep = -old_.gain / static_cast<float>(voiceFade_);
    main_.head2 = head2;
    main_.gain = 0.0f;
    const double fade = std::max(1.0, fadeSeconds * static_cast<double>(sampleRate_));
    main_.gainStep = static_cast<float>(1.0 / fade);
    retroOk_ = false;
}

bool Tape::headRunning() const {
    return transport_.playing && main_.active && (length_ > 0 || (take_ == TakeState::Recording && takeFirst_));
}

bool Tape::onBarLine(int64_t head) const {
    if (bar_ <= 0) return false;
    if (length_ == 0) return head > 0 && head % bar_ == 0;
    if (head == loopE_) return true;
    return head >= loopS_ && head < loopE_ && (head - loopS_) % bar_ == 0;
}

int64_t Tape::framesToBarLine(int64_t head) const {
    if (bar_ <= 0) return std::numeric_limits<int64_t>::max();
    if (length_ == 0) return (bar_ - head % bar_) % bar_;
    const int64_t o = head - loopS_;
    return std::min((bar_ - o % bar_) % bar_, loopE_ - head);
}

// --- render ---

void Tape::process(const float* const* sourcesL, const float* const* sourcesR, int32_t numFrames, float* const* outL,
                   float* const* outR) {
    srcL_ = sourcesL;
    srcR_ = sourcesR;
    outL_ = outL;
    outR_ = outR;
    for (int32_t off = 0; off < numFrames;) {
        const int32_t n = std::min(kMaxBlockFrames, numFrames - off);
        pieceOffset_ = off;
        processPiece(n);
        frame_ += n;
        off += n;
    }
}

int64_t Tape::eventDistance(int32_t pos, int32_t n) const {
    int64_t d = n - pos;
    const int64_t now = frame_ + pos;
    const int64_t head = floorDiv2(main_.head2);
    if (headRunning() && length_ > 0) {
        const int64_t step = main_.step2;
        if (step > 0) {
            d = std::min(d, ceilDiv(2 * loopE_ - main_.head2, step));
        } else {
            d = std::min(d, (main_.head2 - 2 * loopS_) / (-step) + 1);
        }
    }
    if (take_ == TakeState::Recording) {
        if (stopPending_) {
            const int64_t f = framesToBarLine(head);
            if (f > 0) d = std::min(d, f);
        }
        if (takeFirst_) d = std::min(d, firstLimit_ - head);
        if (takeCommit_) d = std::min(d, commitFrames_ - written_);
    } else if (take_ == TakeState::Armed && transport_.playing) {
        if (length_ == 0) {
            if (transport_.nextBarFrame > now) d = std::min(d, transport_.nextBarFrame - now);
        } else if (headRunning() && main_.step2 == 2) {
            const int64_t f = framesToBarLine(head);
            if (f > 0) d = std::min(d, f);
        }
    } else if (take_ == TakeState::CommitPending && nextPass_.valid && nextPass_.startFrame > now) {
        d = std::min(d, nextPass_.startFrame - now);
    }
    return std::max<int64_t>(1, d);
}

bool Tape::handleEvents(int32_t pos) {
    const int64_t now = frame_ + pos;
    if (take_ == TakeState::Recording) {
        const int64_t head = floorDiv2(main_.head2);
        bool end = takeCommit_ && written_ >= commitFrames_;
        end = end || (takeFirst_ && head >= firstLimit_);
        end = end || (stopPending_ && written_ > 0 && onBarLine(head));
        if (end) endTake();
    }
    if (headRunning() && length_ > 0) {
        const int64_t len2 = 2 * (loopE_ - loopS_);
        if (main_.step2 > 0 && main_.head2 >= 2 * loopE_) {
            const bool rec = take_ == TakeState::Recording;
            if (rec) startTail(takeTrack_, loopE_);
            main_.head2 -= len2;
            if (rec) writeLeadIn(takeTrack_, loopS_, pos);
            retroOk_ = false;
        } else if (main_.step2 < 0 && main_.head2 < 2 * loopS_) {
            main_.head2 += len2;
            retroOk_ = false;
        }
    }
    if ((take_ == TakeState::Armed || take_ == TakeState::CommitPending) && transport_.playing && !busy() &&
        main_.active) {
        const int64_t head = floorDiv2(main_.head2);
        bool go = false;
        if (take_ == TakeState::Armed) {
            go = length_ == 0 ? now == transport_.nextBarFrame : (main_.step2 == 2 && onBarLine(head) && head < loopE_);
        } else {
            go = nextPass_.valid && now >= nextPass_.startFrame;
        }
        int64_t retro = 0;
        if (!go && pos == 0 && retroOk_ && now > playFrame_ && now - playFrame_ <= retroFrames_ &&
            (take_ == TakeState::Armed || playPass_.valid)) {
            // Applied just after Play (the UI sends both at once): start at the Play frame.
            const int64_t elapsed = now - playFrame_;
            const bool atPlay = length_ == 0 ? main_.head2 == 0 : main_.head2 == 2 * (loopS_ + elapsed);
            if (atPlay && main_.step2 == 2) {
                go = true;
                retro = elapsed;
            }
        }
        if (go) startTake(pos, retro);
    }
    return true;
}

void Tape::readFrame(const int16_t* base, int64_t head2, int32_t absStep2, float& l, float& r) const {
    const int64_t p = floorDiv2(head2);
    const bool half = (head2 - 2 * p) != 0;
    if (absStep2 != 4 && !half) {
        const int64_t s = offset_ + p;
        if (s < 0 || s >= trackFrames_) {
            l = r = 0.0f;
            return;
        }
        l = fromTape(base[2 * s]);
        r = fromTape(base[2 * s + 1]);
        return;
    }
    const bool lp = absStep2 == 4;
    const float* taps = lp ? lowpass_.data() + (half ? kLpTaps : 0) : interpHalf_.data();
    const int count = lp ? kLpTaps : kTaps;
    const int64_t s0 = offset_ + p - (lp ? kLpCentre : kTapCentre);
    float al = 0.0f, ar = 0.0f;
    if (s0 >= 0 && s0 + count <= trackFrames_) {
        const int16_t* x = base + 2 * s0;
        for (int k = 0; k < count; ++k) {
            al += taps[k] * static_cast<float>(x[2 * k]);
            ar += taps[k] * static_cast<float>(x[2 * k + 1]);
        }
    } else {
        for (int k = 0; k < count; ++k) {
            const int64_t s = s0 + k;
            if (s < 0 || s >= trackFrames_) continue;
            al += taps[k] * static_cast<float>(base[2 * s]);
            ar += taps[k] * static_cast<float>(base[2 * s + 1]);
        }
    }
    l = al * kFromTape;
    r = ar * kFromTape;
}

void Tape::renderPlayback(int32_t pos, int32_t run) {
    const bool running = headRunning();
    const bool mainRead = running && length_ > 0;
    const bool oldRead = old_.active && length_ > 0;
    const int64_t fade = std::max<int64_t>(1, std::min(xfade_, (loopE_ - loopS_) / 2));
    const int32_t mainAbs = std::abs(main_.step2);
    const int32_t oldAbs = std::abs(old_.step2);
    const int32_t o = pieceOffset_ + pos;
    for (int32_t i = 0; i < run; ++i) {
        // Loop crossfade weight (equal power) against the audio before the start (forward)
        // or after the end (reverse).
        float cm = 1.0f, cp = 0.0f;
        int64_t partner = 0;
        if (mainRead) loopFade(main_.head2, main_.step2, loopS_, loopE_, fade, cm, cp, partner);
        const float gm = main_.gain;
        const float go = old_.gain;
        for (int t = 0; t < kTracks; ++t) {
            float l = 0.0f, r = 0.0f;
            if (hasAudio_[static_cast<std::size_t>(t)]) {
                const int16_t* base = trackBase(t);
                float a, b;
                if (mainRead) {
                    readFrame(base, main_.head2, mainAbs, a, b);
                    if (cp > 0.0f) {
                        float pa, pb;
                        readFrame(base, partner, mainAbs, pa, pb);
                        a = blendLoop(a, pa, cm, cp);
                        b = blendLoop(b, pb, cm, cp);
                    }
                    l += gm * a;
                    r += gm * b;
                }
                if (oldRead) {
                    readFrame(base, old_.head2, oldAbs, a, b);
                    l += go * a;
                    r += go * b;
                }
            }
            outL_[t][o + i] = l;
            outR_[t][o + i] = r;
        }
        if (running) {
            main_.head2 += main_.step2;
            main_.gain = std::min(1.0f, main_.gain + main_.gainStep);
        }
        if (old_.active) {
            old_.head2 += old_.step2;
            old_.gain += old_.gainStep;
            if (old_.gain <= 0.0f) old_.active = false;
        }
    }
}

void Tape::processPiece(int32_t n) {
    for (int t = 0; t < kTracks; ++t) {
        std::fill(outL_[t] + pieceOffset_, outL_[t] + pieceOffset_ + n, 0.0f);
        std::fill(outR_[t] + pieceOffset_, outR_[t] + pieceOffset_ + n, 0.0f);
    }
    // Dub is smoothed across the piece (F0.7).
    const float target = params_.dub;
    for (int32_t i = 0; i < n; ++i) {
        dubRamp_[static_cast<std::size_t>(i)] = dub_ + (target - dub_) * static_cast<float>(i + 1) / static_cast<float>(n);
    }
    for (int32_t pos = 0; pos < n;) {
        handleEvents(pos);
        const auto run = static_cast<int32_t>(std::min<int64_t>(eventDistance(pos, n), n - pos));
        const bool rec = take_ == TakeState::Recording;
        const int64_t recHead = floorDiv2(main_.head2);
        renderPlayback(pos, run);
        if (rec) {
            const auto src = static_cast<std::size_t>(takeSource_);
            writeRange(takeTrack_, recHead, run, srcL_[src] + pieceOffset_ + pos, srcR_[src] + pieceOffset_ + pos,
                       1.0f, 0.0f, pos);
            written_ += run;
        }
        if (tailCount_ > 0) runTails(pos, run);
        pos += run;
    }
    dub_ = target;
    if (op_.type != OpType::None) runOp(static_cast<int64_t>(n) * kOpFramesPerFrame);
    if (landing()) runLanding(static_cast<int64_t>(n) * kOpFramesPerFrame);
    if (snapActive_) runSnapshot(static_cast<int64_t>(n) * kOpFramesPerFrame);
    // Input history of every source, for lead-ins and starts at Play.
    for (int s = 0; s < kSources; ++s) {
        float* ringL = history_.get() + static_cast<std::size_t>(s) * 2 * static_cast<std::size_t>(historySize_);
        float* ringR = ringL + historySize_;
        const float* l = srcL_[s] + pieceOffset_;
        const float* r = srcR_[s] + pieceOffset_;
        for (int32_t i = 0; i < n; ++i) {
            const auto k = static_cast<std::size_t>((historyEnd_ + i) & (historySize_ - 1));
            ringL[k] = l[i];
            ringR[k] = r[i];
        }
    }
    historyEnd_ += n;
    flushOverview();
    persistPiece(kPersistFramesPerPiece);
    publish();
}

// --- offline ---

void Tape::applyPendingOffline() {
    // Tails get silence (nothing renders now); an edit in flight completes.
    for (int i = 0; i < tailCount_; ++i) {
        Tail& t = tails_[static_cast<std::size_t>(i)];
        writeRange(t.track, t.pos + t.done, xfade_ - t.done, nullptr, nullptr,
                   1.0f - static_cast<float>(t.done + 1) / static_cast<float>(xfade_), -1.0f / static_cast<float>(xfade_),
                   -1);
    }
    tailCount_ = 0;
    finalizeEntryIfDone();
    if (op_.type != OpType::None) runOp(std::numeric_limits<int64_t>::max());
    if (!landing() && loadPhase_.load(std::memory_order_acquire) == static_cast<int32_t>(TapeLoadPhase::Ready)) {
        startLanding();
    }
    if (landing()) runLanding(std::numeric_limits<int64_t>::max());
    if (deferred_.type != Command::Type::None) {
        tryExecute(deferred_, true);
        deferred_ = Command{};
    }
    Command c;
    while (commands_.pop(c)) tryExecute(c, true);
    if (snapPhase_.load(std::memory_order_acquire) == static_cast<int32_t>(TapeSnapshotPhase::Requested)) {
        acceptSnapshot();
    }
    if (snapActive_) runSnapshot(std::numeric_limits<int64_t>::max());
    flushOverview();
    persistPiece(std::numeric_limits<int64_t>::max());  // as much as the ring takes
    publish();
}

// --- overview ---

void Tape::markDirty(int track, int64_t start, int64_t end) {
    markOverview(track, start, end);
    persistMark(track, start, end);
}

void Tape::markOverview(int track, int64_t start, int64_t end) {
    auto& list = dirty_[static_cast<std::size_t>(track)];
    int32_t& count = dirtyCount_[static_cast<std::size_t>(track)];
    for (int32_t i = 0; i < count; ++i) {
        Dirty& d = list[static_cast<std::size_t>(i)];
        if (start <= d.end && end >= d.start) {
            d.start = std::min(d.start, start);
            d.end = std::max(d.end, end);
            return;
        }
    }
    if (count < static_cast<int32_t>(list.size())) {
        list[static_cast<std::size_t>(count++)] = Dirty{start, end};
        return;
    }
    Dirty& last = list[list.size() - 1];  // full: widen the last one
    last.start = std::min(last.start, start);
    last.end = std::max(last.end, end);
}

int64_t Tape::overviewLength() const {
    if (length_ > 0) return length_;
    if (take_ != TakeState::Recording || !takeFirst_ || bar_ <= 0) return 0;
    // First take in progress: scaled to the bars recorded so far (or the commit's length).
    const int64_t sofar = takeCommit_ ? commitFrames_ : floorDiv2(main_.head2);
    return std::max<int64_t>(1, ceilDiv(std::max<int64_t>(sofar, 1), bar_)) * bar_;
}

void Tape::rescanBlocks(int track, int64_t start, int64_t end) {
    const int64_t s0 = std::max<int64_t>(0, offset_ + start);
    const int64_t s1 = std::min(trackFrames_, offset_ + end);
    if (s1 <= s0) return;
    const int16_t* base = trackBase(track);
    uint16_t* peaks = blockPeak_.get() + static_cast<std::size_t>(track) * static_cast<std::size_t>(blocks_);
    for (int64_t b = s0 / kOverviewBlock; b <= (s1 - 1) / kOverviewBlock; ++b) {
        const int64_t f0 = b * kOverviewBlock;
        const int64_t f1 = std::min(trackFrames_, f0 + kOverviewBlock);
        int peak = 0;
        for (int64_t f = f0; f < f1; ++f) {
            peak = std::max(peak, std::abs(static_cast<int>(base[2 * f])));
            peak = std::max(peak, std::abs(static_cast<int>(base[2 * f + 1])));
        }
        peaks[b] = static_cast<uint16_t>(std::min(peak, 65535));
    }
}

void Tape::recomputeBins(int track, int64_t start, int64_t end) {
    const int64_t len = binLength_;
    auto& bins = bins_[static_cast<std::size_t>(track)];
    if (len <= 0) return;
    start = std::max<int64_t>(0, start);
    end = std::min(len, end);
    if (end <= start) return;
    const uint16_t* peaks = blockPeak_.get() + static_cast<std::size_t>(track) * static_cast<std::size_t>(blocks_);
    const int64_t b0 = start * kOverviewBins / len;
    const int64_t b1 = std::min<int64_t>(kOverviewBins - 1, (end - 1) * kOverviewBins / len);
    for (int64_t bin = b0; bin <= b1; ++bin) {
        const int64_t f0 = offset_ + bin * len / kOverviewBins;
        const int64_t f1 = std::max(f0 + 1, offset_ + (bin + 1) * len / kOverviewBins);
        int peak = 0;
        for (int64_t blk = f0 / kOverviewBlock; blk <= (f1 - 1) / kOverviewBlock && blk < blocks_; ++blk) {
            peak = std::max(peak, static_cast<int>(peaks[blk]));
        }
        // 255 = 0 dBFS (int16 full scale is kHeadroom).
        const long v = std::lround(static_cast<double>(peak) * 255.0 * static_cast<double>(kHeadroom) / 32767.0);
        bins[static_cast<std::size_t>(bin)].store(static_cast<uint8_t>(std::min<long>(255, v)), std::memory_order_relaxed);
    }
}

void Tape::recomputeAllBins() {
    binLength_ = overviewLength();
    for (int t = 0; t < kTracks; ++t) {
        if (binLength_ <= 0) {
            for (auto& b : bins_[static_cast<std::size_t>(t)]) b.store(0, std::memory_order_relaxed);
        } else {
            recomputeBins(t, 0, binLength_);
        }
    }
}

void Tape::flushOverview() {
    for (int t = 0; t < kTracks; ++t) {
        auto& count = dirtyCount_[static_cast<std::size_t>(t)];
        for (int32_t i = 0; i < count; ++i) {
            const Dirty& d = dirty_[static_cast<std::size_t>(t)][static_cast<std::size_t>(i)];
            rescanBlocks(t, d.start, d.end);
            recomputeBins(t, d.start, d.end);
        }
        count = 0;
    }
    if (overviewLength() != binLength_) recomputeAllBins();
}

void Tape::publish() {
    outLength_.store(length_, std::memory_order_relaxed);
    outLengthBars_.store(bar_ > 0 ? static_cast<int32_t>(length_ / bar_) : 0, std::memory_order_relaxed);
    outBar_.store(bar_, std::memory_order_relaxed);
    int64_t head = floorDiv2(main_.head2);
    if (length_ > 0 && head >= loopE_) head -= loopE_ - loopS_;  // wraps at the next frame
    if (length_ > 0 && head < loopS_) head += loopE_ - loopS_;
    outHead_.store(std::max<int64_t>(0, head), std::memory_order_relaxed);
    outRecording_.store(take_ == TakeState::Recording ? takeTrack_ : -1, std::memory_order_relaxed);
    outArmed_.store(take_ == TakeState::Armed || take_ == TakeState::CommitPending ? takeTrack_ : -1,
                    std::memory_order_relaxed);
    outCanUndo_.store(undoCount_ > 0, std::memory_order_relaxed);
    outCanRedo_.store(entryCount_ > undoCount_, std::memory_order_relaxed);
    for (std::size_t t = 0; t < kTracks; ++t) outHasAudio_[t].store(hasAudio_[t], std::memory_order_relaxed);
}

// --- M4: persistence (audio thread unless noted) ---

Tape::Layout Tape::layout() const {
    Layout l;
    l.sampleRate = sampleRate_;
    l.offset = offset_;
    l.trackFrames = trackFrames_;
    l.maxFrames = maxFrames_;
    l.crossfade = xfade_;
    l.guard = guard_;
    return l;
}

void Tape::attachPersistence(TapePersistPort* port) {
    TapePersistPort* expected = nullptr;
    port_.compare_exchange_strong(expected, port, std::memory_order_acq_rel);
}

void Tape::extendWritten(int track, int64_t start, int64_t end) {
    const auto t = static_cast<std::size_t>(track);
    if (writtenLo_[t] >= writtenHi_[t]) {
        writtenLo_[t] = start;
        writtenHi_[t] = end;
    } else {
        writtenLo_[t] = std::min(writtenLo_[t], start);
        writtenHi_[t] = std::max(writtenHi_[t], end);
    }
}

void Tape::persistMark(int track, int64_t start, int64_t end) {
    if (!persistActive_ || end <= start) return;
    ++changeSeq_;
    auto& list = pdirty_[static_cast<std::size_t>(track)];
    int32_t& count = pdirtyCount_[static_cast<std::size_t>(track)];
    for (int32_t i = 0; i < count; ++i) {
        Dirty& d = list[static_cast<std::size_t>(i)];
        if (start <= d.end && end >= d.start) {
            d.start = std::min(d.start, start);
            d.end = std::max(d.end, end);
            return;
        }
    }
    if (count < kPersistRanges) {
        list[static_cast<std::size_t>(count++)] = Dirty{start, end};
        return;
    }
    Dirty& last = list[kPersistRanges - 1];  // full: widen the last one (sends a little extra)
    last.start = std::min(last.start, start);
    last.end = std::max(last.end, end);
}

void Tape::persistReset(int track) {
    if (!persistActive_) return;
    // The track's storage is all zero now (a track without audio always is): the file is
    // zeroed instead, and older pending ranges on it are moot.
    preset_[static_cast<std::size_t>(track)] = true;
    pdirtyCount_[static_cast<std::size_t>(track)] = 0;
    ++changeSeq_;
}

void Tape::persistResyncAll() {
    for (int t = 0; t < kTracks; ++t) {
        const auto ut = static_cast<std::size_t>(t);
        preset_[ut] = true;
        pdirtyCount_[ut] = 0;
        const bool audio = hasAudio_[ut] || (take_ == TakeState::Recording && takeTrack_ == t);
        if (audio && writtenLo_[ut] < writtenHi_[ut]) {
            pdirty_[ut][0] = Dirty{writtenLo_[ut], writtenHi_[ut]};
            pdirtyCount_[ut] = 1;
        }
    }
    ++changeSeq_;
}

int64_t Tape::metaLength() const {
    if (length_ > 0) return length_;
    if (take_ != TakeState::Recording || !takeFirst_ || bar_ <= 0) return 0;
    // A first take in progress: the whole bars recorded so far, so a crash keeps them.
    const int64_t head = floorDiv2(main_.head2);
    const int64_t maxBars = std::max<int64_t>(1, firstLimit_ / bar_);
    return std::clamp<int64_t>(ceilDiv(std::max<int64_t>(head, 1), bar_), 1, maxBars) * bar_;
}

bool Tape::persistPush(TapePersistPort& port, TapePersistRecord& rec, const void* payload, std::size_t bytes) {
    if (!port.ring.write(&rec, sizeof(rec), payload, bytes)) return false;
    persistTouched_ = true;
    return true;
}

void Tape::persistPiece(int64_t budget) {
    TapePersistPort* port = port_.load(std::memory_order_acquire);
    if (!port) return;
    if (!persistActive_) {
        if (landing()) return;  // attached mid-landing: start once the tape has landed
        persistActive_ = true;
        bool any = take_ == TakeState::Recording;
        for (int t = 0; t < kTracks; ++t) any = any || hasAudio_[static_cast<std::size_t>(t)];
        if (any) persistResyncAll();  // attached late: the files must catch up with the tape
    }
    if (!landing() && port->resyncRequested.load(std::memory_order_relaxed) &&
        port->resyncRequested.exchange(false, std::memory_order_acq_rel)) {
        persistResyncAll();
    }

    TapePersistRecord rec;
    rec.preroll = offset_;
    rec.fileFrames = trackFrames_;
    rec.sampleRate = static_cast<uint32_t>(sampleRate_);
    bool blocked = false;
    for (int t = 0; t < kTracks && !blocked; ++t) {
        if (!preset_[static_cast<std::size_t>(t)]) continue;
        rec.type = TapePersistRecord::Reset;
        rec.track = t;
        if (persistPush(*port, rec, nullptr, 0)) {
            preset_[static_cast<std::size_t>(t)] = false;
        } else {
            blocked = true;  // resets go before any data: wait for room
        }
    }
    for (int t = 0; t < kTracks && !blocked; ++t) {
        auto& list = pdirty_[static_cast<std::size_t>(t)];
        int32_t& count = pdirtyCount_[static_cast<std::size_t>(t)];
        while (count > 0 && budget > 0) {
            Dirty& d = list[0];
            d.start = std::max(d.start, -offset_);
            d.end = std::min(d.end, trackFrames_ - offset_);
            const auto room = (static_cast<int64_t>(port->ring.freeBytes()) - static_cast<int64_t>(sizeof(rec))) / 4;
            if (d.end > d.start && room <= 0) {
                blocked = true;  // stays pending: nothing is dropped
                break;
            }
            const int64_t k = std::min({d.end - d.start, budget, room});
            if (k > 0) {
                rec.type = TapePersistRecord::Data;
                rec.track = t;
                rec.frame = d.start;
                rec.frames = k;
                persistPush(*port, rec, at(t, d.start), static_cast<std::size_t>(k) * 4);  // fits: room checked
                d.start += k;
                budget -= k;
            }
            if (d.start >= d.end) {
                for (int32_t j = 0; j + 1 < count; ++j) list[static_cast<std::size_t>(j)] = list[static_cast<std::size_t>(j + 1)];
                --count;
            }
        }
    }
    bool pending = blocked;
    for (int t = 0; t < kTracks; ++t) {
        pending = pending || pdirtyCount_[static_cast<std::size_t>(t)] > 0 || preset_[static_cast<std::size_t>(t)];
    }
    // A consistent point: everything changed so far is in the ring and no edit is half done.
    if (!pending && op_.type == OpType::None && !landing() && emittedSeq_ != changeSeq_) {
        rec = TapePersistRecord{};
        rec.type = TapePersistRecord::Meta;
        rec.preroll = offset_;
        rec.fileFrames = trackFrames_;
        rec.sampleRate = static_cast<uint32_t>(sampleRate_);
        rec.length = metaLength();
        rec.bar = rec.length > 0 ? bar_ : 0;
        for (int t = 0; t < kTracks; ++t) {
            if (rec.length > 0 && hasAudio_[static_cast<std::size_t>(t)]) rec.hasAudioMask |= 1u << t;
        }
        if (rec.hasAudioMask == 0) rec.length = rec.bar = 0;
        rec.sequence = changeSeq_;
        if (persistPush(*port, rec, nullptr, 0)) emittedSeq_ = changeSeq_;
    }
    outChangeSeq_.store(changeSeq_, std::memory_order_release);
}

int64_t Tape::persistPendingFrames() const {
    int64_t n = 0;
    for (int t = 0; t < kTracks; ++t) {
        for (int32_t i = 0; i < pdirtyCount_[static_cast<std::size_t>(t)]; ++i) {
            const Dirty& d = pdirty_[static_cast<std::size_t>(t)][static_cast<std::size_t>(i)];
            n += std::max<int64_t>(0, d.end - d.start);
        }
    }
    return n;
}

// --- M4: load ---

bool Tape::beginLoad() {
    int32_t cur = loadPhase_.load(std::memory_order_acquire);
    for (;;) {
        if (loadActive(cur)) return false;
        if (loadPhase_.compare_exchange_weak(cur, static_cast<int32_t>(TapeLoadPhase::Reading), std::memory_order_acq_rel)) {
            return true;
        }
    }
}

bool Tape::offerLoad(const TapeImage* image) {
    image_ = image;
    auto expected = static_cast<int32_t>(TapeLoadPhase::Reading);
    return loadPhase_.compare_exchange_strong(expected, static_cast<int32_t>(TapeLoadPhase::Ready),
                                              std::memory_order_acq_rel);
}

void Tape::failLoad() {
    auto expected = static_cast<int32_t>(TapeLoadPhase::Reading);
    loadPhase_.compare_exchange_strong(expected, static_cast<int32_t>(TapeLoadPhase::Failed), std::memory_order_acq_rel);
}

void Tape::cancelLoad() {
    int32_t cur = loadPhase_.load(std::memory_order_acquire);
    while (loadActive(cur) && !loadPhase_.compare_exchange_weak(cur, static_cast<int32_t>(TapeLoadPhase::Cancelled),
                                                                std::memory_order_acq_rel)) {
    }
    landPhase_ = TapeLoadPhase::Idle;
}

void Tape::startLanding() {
    const TapeImage& im = *image_;
    const bool fits = im.lo >= -offset_ && im.hi <= trackFrames_ - offset_ && im.lo <= im.hi && im.lengthFrames >= 0 &&
                      im.lengthFrames <= maxFrames_ && (im.lengthFrames == 0 || im.barFrames > 0);
    if (!fits) {
        image_ = nullptr;
        loadPhase_.store(static_cast<int32_t>(TapeLoadPhase::Failed), std::memory_order_release);
        return;
    }
    landPhase_ = TapeLoadPhase::Landing;
    loadPhase_.store(static_cast<int32_t>(TapeLoadPhase::Landing), std::memory_order_release);
    // The loaded tape replaces this one: a take or tail in progress is dropped, history too,
    // and the tape is silent until the new audio has landed.
    take_ = TakeState::None;
    takeEnded_ = true;
    stopPending_ = false;
    tailCount_ = 0;
    entryOpen_ = false;
    clearHistory();
    length_ = 0;
    bar_ = 0;
    hasAudio_.fill(false);
    old_.active = false;
    computeLoop();
    main_.head2 = 0;
    if (persistActive_ && persistTouched_) {
        // The files saw this session's changes: rewrite them from the loaded tape.
        landMark_ = true;
        preset_.fill(true);
        pdirtyCount_.fill(0);
    } else {
        // Nothing was ever sent, so the files still hold exactly the tape being loaded.
        landMark_ = false;
        preset_.fill(false);
        pdirtyCount_.fill(0);
        emittedSeq_ = changeSeq_;
    }
    landTrack_ = 0;
    landPos_ = std::numeric_limits<int64_t>::min();
}

void Tape::runLanding(int64_t budget) {
    const TapeImage& im = *image_;
    while (budget > 0 && landTrack_ < kTracks) {
        const int t = landTrack_;
        const auto ut = static_cast<std::size_t>(t);
        const bool img = ((im.hasAudioMask >> t) & 1u) != 0 && im.hi > im.lo;
        // Everything that may be non-zero now, plus the image's range.
        int64_t a = writtenLo_[ut], b = writtenHi_[ut];
        if (a >= b) {
            a = img ? im.lo : 0;
            b = img ? im.hi : 0;
        } else if (img) {
            a = std::min(a, im.lo);
            b = std::max(b, im.hi);
        }
        if (landPos_ < a) landPos_ = a;
        if (landPos_ >= b) {
            ++landTrack_;
            landPos_ = std::numeric_limits<int64_t>::min();
            continue;
        }
        int64_t e = std::min(b, landPos_ + budget);
        const bool inImage = img && landPos_ >= im.lo && landPos_ < im.hi;
        if (inImage) {
            e = std::min(e, im.hi);
        } else if (img && landPos_ < im.lo) {
            e = std::min(e, im.lo);
        }
        snapBeforeWrite(t, landPos_, e);
        if (inImage) {
            std::memcpy(at(t, landPos_), im.data[ut].data() + (landPos_ - im.lo) * 2, static_cast<std::size_t>(e - landPos_) * 4);
            if (landMark_) persistMark(t, landPos_, e);
        } else {
            std::fill(at(t, landPos_), at(t, e), int16_t{0});
        }
        markOverview(t, landPos_, e);
        budget -= e - landPos_;
        landPos_ = e;
    }
    if (landTrack_ >= kTracks) finishLanding();
}

void Tape::finishLanding() {
    const TapeImage& im = *image_;
    length_ = im.lengthFrames;
    bar_ = length_ > 0 ? im.barFrames : 0;
    for (int t = 0; t < kTracks; ++t) {
        const auto ut = static_cast<std::size_t>(t);
        const bool img = ((im.hasAudioMask >> t) & 1u) != 0 && im.hi > im.lo;
        hasAudio_[ut] = length_ > 0 && ((im.hasAudioMask >> t) & 1u) != 0;
        writtenLo_[ut] = img ? im.lo : 0;
        writtenHi_[ut] = img ? im.hi : 0;
    }
    clearHistory();
    computeLoop();
    main_.head2 = 2 * loopS_;
    if (main_.active) {
        main_.step2 = effectiveStep2();
        main_.gain = 0.0f;
        main_.gainStep = 1.0f / static_cast<float>(startFade_);
    }
    retroOk_ = false;
    recomputeAllBins();
    if (landMark_) persistChanged();
    image_ = nullptr;
    landPhase_ = TapeLoadPhase::Idle;
    loadPhase_.store(static_cast<int32_t>(TapeLoadPhase::Done), std::memory_order_release);
}

// --- M4: export snapshot ---

bool Tape::requestSnapshot(TapeSnapshot* snapshot) {
    if (!snapshot || !snapshot->data || snapshot->capacityFrames <= 0 || !snapshot->copied) return false;
    if (snapPhase_.load(std::memory_order_acquire) != static_cast<int32_t>(TapeSnapshotPhase::Idle)) return false;
    snap_ = snapshot;
    snapPhase_.store(static_cast<int32_t>(TapeSnapshotPhase::Requested), std::memory_order_release);
    return true;
}

void Tape::releaseSnapshot() {
    const auto p = snapshotPhase();
    if (p == TapeSnapshotPhase::Ready || p == TapeSnapshotPhase::Failed) {
        snapPhase_.store(static_cast<int32_t>(TapeSnapshotPhase::Idle), std::memory_order_release);
    }
}

void Tape::cancelSnapshot() {
    const auto p = snapshotPhase();
    if (p == TapeSnapshotPhase::Requested || p == TapeSnapshotPhase::Copying) {
        snapActive_ = false;
        snapPhase_.store(static_cast<int32_t>(TapeSnapshotPhase::Failed), std::memory_order_release);
    }
}

void Tape::acceptSnapshot() {
    TapeSnapshot& s = *snap_;
    const auto fail = [this] {
        snapActive_ = false;
        snapPhase_.store(static_cast<int32_t>(TapeSnapshotPhase::Failed), std::memory_order_release);
    };
    if (length_ <= 0 || bar_ <= 0 || landing()) return fail();
    const int64_t loopS = s.wholeTape ? 0 : loopS_;
    const int64_t loopE = s.wholeTape ? length_ : loopE_;
    if (loopE <= loopS) return fail();
    s.sampleRate = sampleRate_;
    s.lengthFrames = length_;
    s.barFrames = bar_;
    s.loopStart = loopS;
    s.loopEnd = loopE;
    s.fade = std::max<int64_t>(1, std::min(xfade_, (loopE - loopS) / 2));  // as renderPlayback
    s.rangeStart = loopS - s.fade;
    s.rangeFrames = loopE - s.rangeStart;
    s.hasAudioMask = 0;
    s.trackCount = 0;
    for (int t = 0; t < kTracks; ++t) {
        s.slotOfTrack[static_cast<std::size_t>(t)] = -1;
        if (!hasAudio_[static_cast<std::size_t>(t)]) continue;
        s.hasAudioMask |= 1u << t;
        s.slotOfTrack[static_cast<std::size_t>(t)] = s.trackCount++;
    }
    if (s.trackCount == 0 || s.rangeStart < -offset_ || s.trackCount * s.rangeFrames > s.capacityFrames ||
        s.trackCount * s.blocksPerTrack() > s.blockCapacity) {
        return fail();
    }
    snapCursor_ = 0;
    snapActive_ = true;
    snapPhase_.store(static_cast<int32_t>(TapeSnapshotPhase::Copying), std::memory_order_relaxed);
}

void Tape::copySnapBlock(int slot, int64_t block) {
    TapeSnapshot& s = *snap_;
    const int64_t nb = s.blocksPerTrack();
    uint8_t& done = s.copied[static_cast<std::size_t>(slot * nb + block)];
    if (done) return;
    int track = 0;
    for (int t = 0; t < kTracks; ++t) {
        if (s.slotOfTrack[static_cast<std::size_t>(t)] == slot) track = t;
    }
    const int64_t f0 = s.rangeStart + block * TapeSnapshot::kBlockFrames;
    const int64_t n = std::min(TapeSnapshot::kBlockFrames, s.rangeFrames - block * TapeSnapshot::kBlockFrames);
    std::memcpy(s.data + (slot * s.rangeFrames + block * TapeSnapshot::kBlockFrames) * 2, at(track, f0),
                static_cast<std::size_t>(n) * 4);
    done = 1;
}

void Tape::runSnapshot(int64_t budget) {
    TapeSnapshot& s = *snap_;
    const int64_t nb = s.blocksPerTrack();
    const int64_t total = s.trackCount * nb;
    while (budget > 0 && snapCursor_ < total) {
        const auto slot = static_cast<int>(snapCursor_ / nb);
        const int64_t block = snapCursor_ % nb;
        if (!s.copied[static_cast<std::size_t>(snapCursor_)]) {
            copySnapBlock(slot, block);
            budget -= TapeSnapshot::kBlockFrames;
        } else {
            budget -= 16;  // skipping a block copied early still costs a little
        }
        ++snapCursor_;
    }
    if (snapCursor_ >= total) {
        snapActive_ = false;
        snapPhase_.store(static_cast<int32_t>(TapeSnapshotPhase::Ready), std::memory_order_release);
    }
}

void Tape::snapBeforeWrite(int track, int64_t start, int64_t end) {
    if (!snapActive_) return;
    const TapeSnapshot& s = *snap_;
    const int slot = s.slotOfTrack[static_cast<std::size_t>(track)];
    if (slot < 0) return;
    const int64_t a = std::max(start, s.rangeStart);
    const int64_t e = std::min(end, s.rangeStart + s.rangeFrames);
    if (a >= e) return;
    // Copy-on-write: a block about to change is copied first, so the snapshot stays exact.
    for (int64_t b = (a - s.rangeStart) / TapeSnapshot::kBlockFrames; b <= (e - 1 - s.rangeStart) / TapeSnapshot::kBlockFrames;
         ++b) {
        copySnapBlock(slot, b);
    }
}

// --- tests ---

float Tape::sample(int track, int64_t frame, int channel) const {
    if (!validTrack(track) || offset_ + frame < 0 || offset_ + frame >= trackFrames_) return 0.0f;
    return fromTape(at(track, frame)[channel == 0 ? 0 : 1]);
}

}  // namespace groove
