// The drum sampler (docs/feature-spec.md F2): plays the built-in kit (DrumKit) from
// memory on 8 voices, with per-key Pitch, Decay, Tone, Level, Pan, Start, Reverse and Choke.
//
// Audio thread rules (CLAUDE.md): everything the audio thread touches is allocated in the
// constructor or prepare(). The UI reaches the audio thread only through an SPSC note
// queue (drained at the start of renderStereo) and per-key std::atomic<float> parameters;
// latency records go back through a second SPSC queue. Motion overrides are written by
// setMotion(), which runs on the audio thread, so they are plain floats.
//
// Voices. Up to kVoiceCount voices sound at once. A voice that has to stop early
// (retrigger, choke, steal, all-notes-off) is not cut: it moves to a "cutting" state and
// fades out linearly over kCutSeconds (5 ms, F2.3) while the new hit starts at once in
// another slot of the pool. The pool has room for kPoolSize voices, so 16 cuts can be in
// flight beside the 8 sounding voices; only if more than that pile up within 5 ms is the
// most-faded cut voice dropped early (its gain is then already the lowest of all).
//
// Parameters. Pitch, Tone, Level and Pan apply live and are smoothed per 16-sample
// sub-block (about 8 ms to settle, F0.7); a voice's gain also ramps linearly across each
// sub-block. Decay, Start, Reverse and Choke shape a hit and are read when it starts, so
// changing them never affects (or clicks) a sound already playing. A key with nothing
// sounding takes new Pitch/Tone/Level/Pan values at once on its next hit, so a motion
// step lands exactly on that step's hit instead of gliding into it.
//
// Note-offs are ignored: every kit sound is a one-shot that plays its full (Decay-trimmed)
// length. allNotesOff / seqAllNotesOff fade every voice out in kCutSeconds.

#include "groove/DrumMachine.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <limits>
#include <memory>
#include <mutex>
#include <thread>

#include "groove/DrumKit.h"
#include "groove/DspMath.h"
#include "groove/SpscQueue.h"

namespace groove {

namespace {

constexpr int kKeys = DrumMachine::kSoundCount;
constexpr int kParams = static_cast<int>(kDrumParamCount);
constexpr int kPoolSize = 24;
constexpr int kSubBlock = 16;
constexpr std::size_t kEventCapacity = 256;
constexpr std::size_t kRecordCapacity = 64;
constexpr float kCutSeconds = 0.005f;          // choke / steal / retrigger / all-off fade
constexpr float kSmoothingSeconds = 0.0018f;   // one-pole time constant, ~8 ms to 1%
constexpr float kStartFadeSeconds = 0.001f;    // fade-in when Start skips into the sound
constexpr float kMinRegionSeconds = 0.005f;    // shortest hit Decay can make
constexpr float kSqrt2 = 1.41421356237f;
// Tone crossfade (log2 Hz): fully filtered at or below ~14 kHz, dry at the 20 kHz maximum.
const float kToneWetLog2 = std::log2(14000.0f);
const float kToneBypassLog2 = std::log2(drumParamInfo(DrumParamId::Tone).max);

static_assert(DrumKit::kSoundCount == DrumMachine::kSoundCount, "kit and machine disagree on key count");
static_assert(std::atomic<float>::is_always_lock_free, "params must be lock-free");

constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();

// Start values that differ from the table's neutral defaults: the hats share choke group
// A, so a closed hat chokes the open hat (F2.3).
float initialParam(int key, int id) {
    if (id == static_cast<int>(DrumParamId::Choke) && key >= 6 && key <= 8) return 1.0f;
    return kDrumParamInfo[static_cast<std::size_t>(id)].def;
}

float clampVelocity(float v) {
    if (std::isnan(v)) return 1.0f;
    return std::clamp(v, 0.0f, 1.0f);
}

// Cubic Hermite (Catmull-Rom) interpolation of d at position pos >= 0.
inline float hermite(const float* d, double pos) {
    const auto i = static_cast<std::ptrdiff_t>(pos);
    const float f = static_cast<float>(pos - static_cast<double>(i));
    const float xm1 = d[i - 1], x0 = d[i], x1 = d[i + 1], x2 = d[i + 2];
    const float c1 = 0.5f * (x1 - xm1);
    const float c2 = xm1 - 2.5f * x0 + 2.0f * x1 - 0.5f * x2;
    const float c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1);
    return ((c3 * f + c2) * f + c1) * f + x0;
}

}  // namespace

struct DrumMachine::Impl {
    enum class State : uint8_t { Idle, Playing, Cutting };

    struct Voice {
        State state = State::Idle;
        bool fresh = true;  // first sub-block: take the gain target without a ramp
        bool fadeIn = false;
        bool shapeTail = false;
        int key = 0;
        int group = 0;
        int dir = 1;
        const float* data = nullptr;
        double pos = 0.0;
        double regionStart = 0.0;
        double regionEnd = 0.0;
        double invRegionLen = 0.0;
        double fadeInLen = 1.0;
        float velocity = 1.0f;
        float cutGain = 1.0f;
        float gainL = 0.0f;
        float gainR = 0.0f;
        float ic1 = 0.0f;  // low-pass state
        float ic2 = 0.0f;
        uint64_t stamp = 0;
    };

    // Audio-thread view of one key's live parameters.
    struct KeyState {
        float targetPitch = 0.0f, targetTone = 0.0f, targetLevel = 0.0f, targetPan = 0.0f;
        float pitch = 0.0f, tone = 0.0f, level = 0.0f, pan = 0.0f;  // tone in log2(Hz)
        // Derived from the smoothed values when they move.
        float cachedPitch = kNaN, cachedTone = kNaN, cachedLevel = kNaN, cachedPan = kNaN;
        double rate = 1.0;
        float a1 = 1.0f, a2 = 0.0f, a3 = 0.0f;
        float wet = 0.0f;  // low-pass mix: 0 = bypassed (Tone fully open)
        float gainL = 0.0f, gainR = 0.0f;
        bool snap = true;
    };

    // --- shared with the control thread ---
    std::array<std::array<std::atomic<float>, kDrumParamCount>, kKeys> params;
    SpscQueue<NoteEvent, kEventCapacity> events;
    SpscQueue<NoteOnRender, kRecordCapacity> records;
    std::atomic<int64_t> frames{0};

    // --- the kit: rendered by `worker`, published through `kit` (acquire on the audio thread) ---
    std::atomic<const DrumKit*> kit{nullptr};
    mutable std::mutex kitMutex;
    mutable std::condition_variable kitCv;
    std::unique_ptr<DrumKit> ownedKit;  // the published kit (kitMutex)
    float wantRate = 48000.0f;          // kitMutex
    uint64_t wantGen = 1;               // kitMutex: job number
    uint64_t doneGen = 0;               // kitMutex
    bool stopWorker = false;            // kitMutex
    std::thread worker;

    // --- audio thread (and prepare) ---
    float sampleRate = 48000.0f;
    float smoothingCoef = 1.0f;
    float cutStep = 1.0f;
    std::array<std::array<float, kDrumParamCount>, kKeys> motion{};
    std::array<KeyState, kKeys> keys{};
    std::array<Voice, kPoolSize> voices{};
    uint64_t stamp = 0;

    Impl() {
        for (int k = 0; k < kKeys; ++k) {
            for (int i = 0; i < kParams; ++i) {
                params[static_cast<std::size_t>(k)][static_cast<std::size_t>(i)].store(initialParam(k, i));
            }
        }
        clearMotion();
        worker = std::thread([this] { kitLoop(); });  // renders the 48 kHz kit at once
    }

    ~Impl() {
        {
            std::lock_guard<std::mutex> lock(kitMutex);
            stopWorker = true;
        }
        kitCv.notify_all();
        worker.join();
    }

    void kitLoop() {
        std::unique_lock<std::mutex> lock(kitMutex);
        for (;;) {
            kitCv.wait(lock, [this] { return stopWorker || doneGen != wantGen; });
            if (stopWorker) return;
            const float rate = wantRate;
            const uint64_t gen = wantGen;
            lock.unlock();
            auto rendered = std::make_unique<DrumKit>();
            rendered->render(rate);  // slow: outside the lock, so prepare() never waits on it
            lock.lock();
            if (gen == wantGen && !stopWorker) {
                ownedKit = std::move(rendered);
                kit.store(ownedKit.get(), std::memory_order_release);
            }
            doneGen = gen;  // a newer request (wantGen moved on) runs on the next turn
            kitCv.notify_all();
        }
    }

    // Only while nothing renders.
    void requestKit(float rate) {
        {
            std::lock_guard<std::mutex> lock(kitMutex);
            const DrumKit* current = ownedKit.get();
            if (current && current->sampleRate() == rate) return;  // ready at this rate
            if (!current && wantRate == rate) return;              // already being rendered
            kit.store(nullptr, std::memory_order_release);
            ownedKit.reset();  // safe: the audio thread can't be reading it now
            wantRate = rate;
            ++wantGen;
        }
        kitCv.notify_all();
    }

    bool waitForKit(int timeoutMs) const {
        std::unique_lock<std::mutex> lock(kitMutex);
        return kitCv.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                              [this] { return kit.load(std::memory_order_acquire) != nullptr; });
    }

    void clearMotion() {
        for (auto& key : motion) key.fill(kNaN);
    }

    float effective(int key, DrumParamId id) const {
        const auto k = static_cast<std::size_t>(key);
        const float m = motion[k][index(id)];
        if (!std::isnan(m)) return m;
        return params[k][index(id)].load(std::memory_order_relaxed);
    }

    void prepare(float rate) {
        if (!(rate >= 8000.0f && rate <= 384000.0f)) rate = 48000.0f;
        // The kit renders in the background (and not at all when the rate is unchanged).
        requestKit(rate);
        sampleRate = rate;
        smoothingCoef = 1.0f - std::exp(-static_cast<float>(kSubBlock) / (kSmoothingSeconds * rate));
        cutStep = 1.0f / (kCutSeconds * rate);
        for (Voice& v : voices) v = Voice{};
        for (KeyState& k : keys) k = KeyState{};
        stamp = 0;
        // Notes queued before the restart are stale: drop them (prepare() stands in for
        // the audio thread, the queue's consumer).
        NoteEvent stale;
        while (events.pop(stale)) {
        }
        frames.store(0, std::memory_order_relaxed);
    }

    // --- audio thread ---

    void cut(Voice& v) {
        if (v.state == State::Playing) {
            v.state = State::Cutting;
            v.cutGain = 1.0f;
        }
    }

    void cutAll() {
        for (Voice& v : voices) cut(v);
    }

    void trigger(int key, float velocity) {
        const DrumKit* k = kit.load(std::memory_order_acquire);
        if (!k || velocity <= 0.0f) return;  // no kit yet: hits are ignored (F2 decision)
        const DrumKit& kitRef = *k;
        const int group = static_cast<int>(effective(key, DrumParamId::Choke));
        bool keySounding = false;
        int playing = 0;
        for (Voice& v : voices) {
            if (v.state == State::Idle) continue;
            if (v.key == key) keySounding = true;
            // Retrigger restarts the key; a hit in a choke group silences the group.
            if (v.state == State::Playing && (v.key == key || (group != 0 && v.group == group))) cut(v);
            if (v.state == State::Playing) ++playing;
        }
        if (playing >= DrumMachine::kVoiceCount) {
            Voice* oldest = nullptr;
            for (Voice& v : voices) {
                if (v.state == State::Playing && (!oldest || v.stamp < oldest->stamp)) oldest = &v;
            }
            cut(*oldest);
        }
        Voice* slot = nullptr;
        for (Voice& v : voices) {
            if (v.state == State::Idle) {
                slot = &v;
                break;
            }
        }
        if (!slot) {
            // Every slot is busy fading out: reuse the quietest fade.
            for (Voice& v : voices) {
                if (v.state == State::Cutting && (!slot || v.cutGain < slot->cutGain)) slot = &v;
            }
        }
        if (!keySounding) keys[static_cast<std::size_t>(key)].snap = true;

        const double length = static_cast<double>(kitRef.length(key));
        const double start = static_cast<double>(effective(key, DrumParamId::Start)) * length;
        const double available = length - start;
        const double decay = static_cast<double>(effective(key, DrumParamId::Decay));
        double region = available;
        bool shapeTail = false;
        if (decay < 1.0) {
            region = std::min(available, std::max(decay * available, static_cast<double>(kMinRegionSeconds * sampleRate)));
            shapeTail = true;
        }
        const bool reverse = effective(key, DrumParamId::Reverse) >= 0.5f;

        Voice& v = *slot;
        v = Voice{};
        v.state = State::Playing;
        v.key = key;
        v.group = group;
        v.data = kitRef.data(key);
        v.regionStart = start;
        v.regionEnd = start + region;
        v.invRegionLen = 1.0 / region;
        v.shapeTail = shapeTail;
        v.fadeIn = start > 0.0;
        v.fadeInLen = static_cast<double>(kStartFadeSeconds * sampleRate);
        v.dir = reverse ? -1 : 1;
        v.pos = reverse ? v.regionEnd : v.regionStart;
        v.velocity = velocity;
        v.stamp = ++stamp;
    }

    void handle(const NoteEvent& e, int64_t blockStart) {
        switch (e.type) {
            case NoteEvent::Type::NoteOn:
                trigger(e.note - DrumMachine::kLowestNote, e.velocity);
                // dropped if full; no record for a hit ignored before the kit is ready
                if (e.tapTimeNs != 0 && kit.load(std::memory_order_relaxed)) records.push({e.tapTimeNs, blockStart});
                break;
            case NoteEvent::Type::AllNotesOff:
                cutAll();
                break;
            case NoteEvent::Type::NoteOff:
                break;
        }
    }

    void readTargets() {
        for (int k = 0; k < kKeys; ++k) {
            KeyState& s = keys[static_cast<std::size_t>(k)];
            s.targetPitch = effective(k, DrumParamId::Pitch);
            s.targetTone = std::log2(effective(k, DrumParamId::Tone));
            s.targetLevel = effective(k, DrumParamId::Level);
            s.targetPan = effective(k, DrumParamId::Pan);
            if (s.snap) {
                s.pitch = s.targetPitch;
                s.tone = s.targetTone;
                s.level = s.targetLevel;
                s.pan = s.targetPan;
                s.snap = false;
            }
        }
    }

    void advanceControls() {
        const float c = smoothingCoef;
        for (KeyState& s : keys) {
            s.pitch += c * (s.targetPitch - s.pitch);
            s.tone += c * (s.targetTone - s.tone);
            s.level += c * (s.targetLevel - s.level);
            s.pan += c * (s.targetPan - s.pan);
            if (s.pitch != s.cachedPitch) {
                s.cachedPitch = s.pitch;
                s.rate = std::exp2(static_cast<double>(s.pitch) / 12.0);
            }
            if (s.tone != s.cachedTone) {
                s.cachedTone = s.tone;
                const float fc = std::min(std::exp2(s.tone), 0.45f * sampleRate);
                const float g = std::tan(dsp::kPi * fc / sampleRate);
                s.a1 = 1.0f / (1.0f + g * (g + kSqrt2));
                s.a2 = g * s.a1;
                s.a3 = g * s.a2;
                // A 2-pole low-pass near Nyquist still dulls the top octave, so the top of
                // the Tone range crossfades to the dry signal: fully open is transparent.
                s.wet = std::clamp((kToneBypassLog2 - s.tone) * (1.0f / (kToneBypassLog2 - kToneWetLog2)), 0.0f, 1.0f);
            }
            if (s.level != s.cachedLevel || s.pan != s.cachedPan) {
                s.cachedLevel = s.level;
                s.cachedPan = s.pan;
                // Equal-power pan: L^2 + R^2 = level^2 at every position.
                const float angle = (std::clamp(s.pan, -1.0f, 1.0f) + 1.0f) * (dsp::kPi * 0.25f);
                s.gainL = s.level * std::cos(angle);
                s.gainR = s.level * std::sin(angle);
            }
        }
    }

    float envelope(const Voice& v, double pos) const {
        float e = 1.0f;
        const double fromStart = pos - v.regionStart;
        if (v.fadeIn && fromStart < v.fadeInLen) e = static_cast<float>(std::max(0.0, fromStart / v.fadeInLen));
        if (v.shapeTail) {
            // (1 - x^3)^2: keeps the attack, then bends the tail smoothly to zero at the
            // region's end (zero value and a gentle slope, so the cut never clicks).
            const float x = static_cast<float>(std::clamp(fromStart * v.invRegionLen, 0.0, 1.0));
            const float y = 1.0f - x * x * x;
            e *= y * y;
        }
        return e;
    }

    void renderVoice(Voice& v, float* l, float* r, int len) {
        const KeyState& k = keys[static_cast<std::size_t>(v.key)];
        const float targetL = k.gainL * v.velocity;
        const float targetR = k.gainR * v.velocity;
        if (v.fresh) {
            v.gainL = targetL;
            v.gainR = targetR;
            v.fresh = false;
        }
        const float inv = 1.0f / static_cast<float>(len);
        const float stepL = (targetL - v.gainL) * inv;
        const float stepR = (targetR - v.gainR) * inv;
        const double inc = k.rate * static_cast<double>(v.dir);
        const float a1 = k.a1, a2 = k.a2, a3 = k.a3, wet = k.wet;
        float gl = v.gainL, gr = v.gainR;
        float ic1 = v.ic1, ic2 = v.ic2;
        for (int j = 0; j < len; ++j) {
            if (v.dir > 0 ? v.pos >= v.regionEnd : v.pos <= v.regionStart) {
                v.state = State::Idle;
                break;
            }
            const float x = hermite(v.data, v.pos) * envelope(v, v.pos);
            // TPT state-variable low-pass, Butterworth (Q = 0.707): the Tone control.
            const float v3 = x - ic2;
            const float v1 = a1 * ic1 + a2 * v3;
            const float v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2.0f * v1 - ic1;
            ic2 = 2.0f * v2 - ic2;
            float y = x + wet * (v2 - x);
            if (v.state == State::Cutting) {
                y *= v.cutGain;
                v.cutGain -= cutStep;
                if (v.cutGain <= 0.0f) v.state = State::Idle;  // stops after this sample
            }
            gl += stepL;
            gr += stepR;
            l[j] += y * gl;
            r[j] += y * gr;
            v.pos += inc;
            if (v.state == State::Idle) break;
        }
        v.gainL = targetL;
        v.gainR = targetR;
        v.ic1 = ic1;
        v.ic2 = ic2;
    }

    void render(float* left, float* right, int32_t numFrames) {
        if (numFrames <= 0) return;
        const int64_t blockStart = frames.load(std::memory_order_relaxed);
        NoteEvent e;
        while (events.pop(e)) handle(e, blockStart);
        readTargets();
        for (int32_t offset = 0; offset < numFrames; offset += kSubBlock) {
            const int len = static_cast<int>(std::min<int32_t>(kSubBlock, numFrames - offset));
            float* l = left + offset;
            float* r = right + offset;
            std::fill(l, l + len, 0.0f);
            std::fill(r, r + len, 0.0f);
            advanceControls();
            for (Voice& v : voices) {
                if (v.state != State::Idle) renderVoice(v, l, r, len);
            }
        }
        // Single writer (this thread), so load + store is fine.
        frames.store(blockStart + numFrames, std::memory_order_relaxed);
    }
};

DrumMachine::DrumMachine() : impl_(new Impl()) {}
DrumMachine::~DrumMachine() { delete impl_; }

bool DrumMachine::noteOn(int note, float velocity, int64_t tapTimeNs) {
    if (note < kLowestNote || note >= kLowestNote + kSoundCount) return false;
    return impl_->events.push({NoteEvent::Type::NoteOn, note, clampVelocity(velocity), tapTimeNs});
}

// One-shot sounds ignore note-off; accepted for any valid MIDI note so callers can treat
// every slot alike.
bool DrumMachine::noteOff(int note) { return note >= 0 && note <= 127; }

bool DrumMachine::allNotesOff() { return impl_->events.push({NoteEvent::Type::AllNotesOff, 0, 0.0f, 0}); }

bool DrumMachine::popNoteOnRender(NoteOnRender& out) { return impl_->records.pop(out); }

void DrumMachine::setParam(int key, DrumParamId id, float value) {
    if (key < 0 || key >= kSoundCount || index(id) >= kDrumParamCount) return;
    impl_->params[static_cast<std::size_t>(key)][index(id)].store(sanitizeDrumParam(id, value),
                                                                  std::memory_order_relaxed);
}

float DrumMachine::param(int key, DrumParamId id) const {
    if (key < 0 || key >= kSoundCount || index(id) >= kDrumParamCount) return 0.0f;
    return impl_->params[static_cast<std::size_t>(key)][index(id)].load(std::memory_order_relaxed);
}

const char* DrumMachine::soundName(int key) { return DrumKit::name(key); }

void DrumMachine::prepare(float sampleRate) { impl_->prepare(sampleRate); }

void DrumMachine::seqNoteOn(int note, float velocity) {
    if (note < kLowestNote || note >= kLowestNote + kSoundCount) return;
    impl_->trigger(note - kLowestNote, clampVelocity(velocity));
}

void DrumMachine::seqNoteOff(int) {}

void DrumMachine::seqAllNotesOff() { impl_->cutAll(); }

void DrumMachine::setMotion(int target, float value) {
    if (target < 0 || target >= kSoundCount * kParams) return;
    const auto key = static_cast<std::size_t>(target / kParams);
    const auto id = static_cast<DrumParamId>(target % kParams);
    impl_->motion[key][index(id)] = std::isnan(value) ? kNaN : sanitizeDrumParam(id, value);
}

void DrumMachine::clearAllMotion() { impl_->clearMotion(); }

void DrumMachine::setTempo(float) {}  // nothing in the kit is tempo-synced

void DrumMachine::renderStereo(float* left, float* right, int32_t numFrames) {
    impl_->render(left, right, numFrames);
}

int64_t DrumMachine::framesRendered() const { return impl_->frames.load(std::memory_order_relaxed); }

bool DrumMachine::kitReady() const { return impl_->kit.load(std::memory_order_acquire) != nullptr; }

bool DrumMachine::waitForKit(int timeoutMs) const { return impl_->waitForKit(timeoutMs); }

}  // namespace groove
