#include "groove/SynthEngine.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include "groove/Presets.h"

namespace groove {

namespace {

constexpr float kEnvOctaves = 8.0f;       // flt.envAmt = +-100% sweeps +-8 octaves
constexpr float kVelocityOctaves = 3.0f;  // flt.velAmt = 100%: velocity 0 is 3 octaves darker
constexpr float kLfoCutoffOctaves = 3.0f; // flt.lfoAmt = 100%: +-3 octaves
constexpr float kUnisonGain = 0.6f;       // four stacked voices
constexpr float kLog2Of10Over20 = 0.16609640474f;  // dB -> log2(gain)

constexpr std::array<float, SynthEngine::kVoiceCount> kUnisonDetune = {-1.0f, -1.0f / 3.0f, 1.0f / 3.0f, 1.0f};
constexpr std::array<float, SynthEngine::kVoiceCount> kUnisonPan = {-0.3f, -0.1f, 0.1f, 0.3f};
constexpr std::array<float, SynthEngine::kVoiceCount> kUnisonPhase = {0.0f, 0.37f, 0.61f, 0.83f};

}  // namespace

SynthEngine::SynthEngine() {
    motion_.fill(std::numeric_limits<float>::quiet_NaN());
    prepare(48000.0f);
}

void SynthEngine::prepare(float sampleRate) {
    sampleRate_ = sampleRate;
    controlRate_ = sampleRate / static_cast<float>(kSubBlock);
    smoothingCoef_ = 1.0f - std::exp(-static_cast<float>(kSubBlock) / (kSmoothingSeconds * sampleRate));
    for (int i = 0; i < kVoiceCount; ++i) {
        voices_[static_cast<std::size_t>(i)].prepare(sampleRate, 0x2545F491u * static_cast<uint32_t>(i + 1));
    }
    freeLfo_ = Lfo{};
    freeLfo_.setUpdateRate(controlRate_);
    freeLfo_.reset();
    held_.clear();
    stamp_ = 0;
    ampTimes_.fill(-1.0f);
    filterTimes_.fill(-1.0f);
    hpfCached_ = driveCached_ = lfoRateCached_ = lfoDelayCached_ = lfoTempoCached_ = -1.0f;
    motion_.fill(std::numeric_limits<float>::quiet_NaN());
    noteSources_.fill(0);
    seqEventCount_ = 0;
    subBlockPos_ = 0;

    snapSmoothing_ = true;
    readTargets();
    mode_ = static_cast<VoiceMode>(static_cast<int32_t>(raw_[index(ParamId::OscVoiceMode)]));
    setMode(mode_);
    for (Voice& v : voices_) v.reset();
    advanceControls();
    framesRendered_.store(0, std::memory_order_relaxed);
}

bool SynthEngine::noteOn(int note, float velocity, int64_t tapTimeNs) {
    if (note < 0 || note > 127) return false;
    if (std::isnan(velocity)) velocity = 1.0f;
    velocity = std::clamp(velocity, 0.0f, 1.0f);
    return events_.push({NoteEvent::Type::NoteOn, note, velocity, tapTimeNs});
}

bool SynthEngine::noteOff(int note) {
    if (note < 0 || note > 127) return false;
    return events_.push({NoteEvent::Type::NoteOff, note, 0.0f, 0});
}

bool SynthEngine::allNotesOff() { return events_.push({NoteEvent::Type::AllNotesOff, 0, 0.0f, 0}); }

bool SynthEngine::loadPreset(int index) {
    if (index < 0 || index >= presetCount()) return false;
    params_.setAll(preset(index).values);
    return true;
}

void SynthEngine::readTargets() {
    params_.snapshot(raw_);
    // Motion overrides sit on top of the strips' values and are smoothed like them.
    for (std::size_t i = 0; i < kParamCount; ++i) {
        if (!std::isnan(motion_[i])) raw_[i] = motion_[i];
    }
    for (std::size_t i = 0; i < kParamCount; ++i) {
        target_[i] = kParamInfo[i].curve == Curve::Exp ? std::log2(raw_[i]) : raw_[i];
    }
    if (snapSmoothing_) {
        smoothed_ = target_;
        snapSmoothing_ = false;
    }
}

void SynthEngine::setMode(VoiceMode mode) {
    for (Voice& v : voices_) v.fadeOut();
    held_.clear();
    noteSources_.fill(0);
    mode_ = mode;
    for (std::size_t i = 0; i < voices_.size(); ++i) {
        const bool unison = mode == VoiceMode::Unison;
        voices_[i].setDetuneCents(unison ? kUnisonDetune[i] * kUnisonSpreadCents : 0.0f);
        voices_[i].setStartPhase(unison ? kUnisonPhase[i] : 0.0f);
        // Equal-power pan, normalised to unity in the centre.
        const float pan = unison ? kUnisonPan[i] : 0.0f;
        const float theta = (pan + 1.0f) * 0.25f * dsp::kPi;
        const float gain = unison ? kUnisonGain : 1.0f;
        panLeft_[i] = gain * std::sqrt(2.0f) * std::cos(theta);
        panRight_[i] = gain * std::sqrt(2.0f) * std::sin(theta);
    }
}

void SynthEngine::advanceControls() {
    for (std::size_t i = 0; i < kParamCount; ++i) {
        if (kParamInfo[i].discrete()) {
            smoothed_[i] = target_[i];
            continue;
        }
        const float d = target_[i] - smoothed_[i];
        smoothed_[i] = std::fabs(d) < 1e-5f ? target_[i] : smoothed_[i] + d * smoothingCoef_;
    }

    VoiceControl& c = control_;
    c.waveWeights = Oscillator::morphWeights(smoothed(ParamId::OscWave));
    c.pulseWidth = smoothed(ParamId::OscPulseWidth);
    c.subLevel = smoothed(ParamId::OscSub);
    c.noiseLevel = smoothed(ParamId::OscNoise);
    c.pitchOffsetSemis = 12.0f * smoothed(ParamId::OscOctave) + 0.01f * smoothed(ParamId::OscFine);
    c.glideSeconds = mode_ == VoiceMode::Poly ? 0.0f : smoothed(ParamId::OscGlide);

    c.cutoffOctaves = smoothed(ParamId::FltCutoff);
    c.resonance = smoothed(ParamId::FltReso);
    c.envOctaves = kEnvOctaves * smoothed(ParamId::FltEnvAmount);
    c.keyTrack = smoothed(ParamId::FltKeyTrack);
    const float driveDb = smoothed(ParamId::FltDrive);
    if (driveDb != driveCached_) {
        driveCached_ = driveDb;
        c.drive = std::exp2(driveDb * kLog2Of10Over20);
    }
    const float hpf = smoothed(ParamId::FltHighPass);
    if (hpf != hpfCached_) {
        hpfCached_ = hpf;
        c.hpfCoef = onePoleCoefficient(std::exp2(hpf), sampleRate_);
    }
    c.velocityOctaves = kVelocityOctaves * smoothed(ParamId::FltVelocity);
    c.lfoCutoffOctaves = kLfoCutoffOctaves * smoothed(ParamId::FltLfoAmount);

    const float rateLog2 = smoothed(ParamId::LfoRate);
    const bool synced = smoothed(ParamId::LfoSync) >= 0.5f;
    if (synced && (rateLog2 != lfoRateCached_ || tempoBpm_ != lfoTempoCached_)) {
        lfoRateCached_ = rateLog2;
        lfoTempoCached_ = tempoBpm_;
        lfoSyncedHz_ = Lfo::divisionHz(Lfo::nearestDivision(std::exp2(rateLog2), tempoBpm_), tempoBpm_);
    }
    c.lfoHz = synced ? lfoSyncedHz_ : std::exp2(rateLog2);
    c.lfoShape = static_cast<Lfo::Shape>(static_cast<int32_t>(smoothed(ParamId::LfoWave)));
    c.lfoDest = static_cast<LfoDest>(static_cast<int32_t>(smoothed(ParamId::LfoDest)));
    c.lfoDepth = smoothed(ParamId::LfoDepth);
    const float delay = smoothed(ParamId::LfoDelay);
    if (delay != lfoDelayCached_) {
        lfoDelayCached_ = delay;
        c.lfoFadeStep = delay > 1e-4f ? 1.0f / (delay * controlRate_) : 1.0f;
    }
    c.lfoRetrigger = smoothed(ParamId::LfoRetrigger) >= 0.5f;
    freeLfo_.setShape(c.lfoShape);
    freeLfo_.setFrequency(c.lfoHz);
    c.freeLfo = freeLfo_.process();

    const std::array<float, 4> amp = {std::exp2(smoothed(ParamId::AmpAttack)), std::exp2(smoothed(ParamId::AmpDecay)),
                                      smoothed(ParamId::AmpSustain), std::exp2(smoothed(ParamId::AmpRelease))};
    if (amp != ampTimes_) {
        ampTimes_ = amp;
        c.ampShape = AdsrShape::compute(amp[0], amp[1], amp[2], amp[3], sampleRate_);
    }
    const std::array<float, 4> flt = {std::exp2(smoothed(ParamId::FenvAttack)),
                                      std::exp2(smoothed(ParamId::FenvDecay)), smoothed(ParamId::FenvSustain),
                                      std::exp2(smoothed(ParamId::FenvRelease))};
    if (flt != filterTimes_) {
        filterTimes_ = flt;
        c.filterShape = AdsrShape::compute(flt[0], flt[1], flt[2], flt[3], controlRate_);
    }
}

void SynthEngine::handle(const NoteEvent& e, int64_t blockStartFrame, uint8_t source) {
    switch (e.type) {
        case NoteEvent::Type::NoteOn: {
            noteSources_[static_cast<std::size_t>(e.note)] |= source;
            if (mode_ == VoiceMode::Poly) {
                notePoly(e.note, e.velocity, e.tapTimeNs, blockStartFrame);
                break;
            }
            const bool retrigger = held_.empty();  // legato while any note is held (F1.2)
            held_.push(e.note);
            heldVelocity_ = e.velocity;
            const std::size_t count = mode_ == VoiceMode::Mono ? 1 : voices_.size();
            bool delayed = false;
            for (std::size_t i = 0; i < count; ++i) {
                Voice& v = voices_[i];
                delayed = delayed || v.isFading();
                v.trigger(e.note, e.velocity, retrigger, control_);
                v.startStamp = ++stamp_;
            }
            if (e.tapTimeNs != 0) {
                // A voice still fading out from a mode change starts after its fade.
                const int64_t delay = delayed ? static_cast<int64_t>(Voice::kStealFadeSeconds * sampleRate_) : 0;
                renderRecords_.push({e.tapTimeNs, blockStartFrame + delay});
            }
            break;
        }
        case NoteEvent::Type::NoteOff: {
            uint8_t& held = noteSources_[static_cast<std::size_t>(e.note)];
            if ((held & source) == 0) break;  // this source isn't holding it
            held = static_cast<uint8_t>(held & ~source);
            if (held == 0) releaseNote(e.note);  // nobody else is holding it either
            break;
        }
        case NoteEvent::Type::AllNotesOff:
            if (source == kSequencer) {
                for (std::size_t n = 0; n < noteSources_.size(); ++n) {
                    if ((noteSources_[n] & kSequencer) == 0) continue;
                    noteSources_[n] = static_cast<uint8_t>(noteSources_[n] & ~kSequencer);
                    if (noteSources_[n] == 0) releaseNote(static_cast<int>(n));
                }
                break;
            }
            // From the keys: a panic, everything stops.
            noteSources_.fill(0);
            held_.clear();
            for (Voice& v : voices_) {
                v.cancelPending();
                v.release();
            }
            break;
    }
}

void SynthEngine::releaseNote(int note) {
    if (mode_ == VoiceMode::Poly) {
        noteOffPoly(note);
        return;
    }
    const bool wasTop = held_.top() == note;
    held_.remove(note);
    const std::size_t count = mode_ == VoiceMode::Mono ? 1 : voices_.size();
    for (std::size_t i = 0; i < count; ++i) {
        Voice& v = voices_[i];
        if (held_.empty()) {
            if (v.isFading()) {
                v.cancelPending();
            } else {
                v.release();
            }
        } else if (wasTop) {
            // Back to the previous held note, legato (last-note priority).
            v.trigger(held_.top(), heldVelocity_, false, control_);
        }
    }
}

void SynthEngine::pushSeqEvent(const NoteEvent& e) {
    if (seqEventCount_ < kSeqEventCapacity) {
        seqEvents_[static_cast<std::size_t>(seqEventCount_++)] = e;
        return;
    }
    // Never expected (the graph renders after every event frame); apply rather than drop.
    handle(e, framesRendered_.load(std::memory_order_relaxed), kSequencer);
}

void SynthEngine::seqNoteOn(int note, float velocity) {
    if (note < 0 || note > 127) return;
    if (std::isnan(velocity)) velocity = 1.0f;
    pushSeqEvent({NoteEvent::Type::NoteOn, note, std::clamp(velocity, 0.0f, 1.0f), 0});
}

void SynthEngine::seqNoteOff(int note) {
    if (note < 0 || note > 127) return;
    pushSeqEvent({NoteEvent::Type::NoteOff, note, 0.0f, 0});
}

void SynthEngine::seqAllNotesOff() { pushSeqEvent({NoteEvent::Type::AllNotesOff, 0, 0.0f, 0}); }

void SynthEngine::setMotion(int target, float value) {
    if (target < 0 || target >= static_cast<int>(kParamCount)) return;
    const auto id = static_cast<ParamId>(target);
    motion_[index(id)] = std::isnan(value) ? value : sanitizeParam(id, value);
}

void SynthEngine::clearAllMotion() { motion_.fill(std::numeric_limits<float>::quiet_NaN()); }

void SynthEngine::setTempo(float bpm) {
    if (std::isnan(bpm)) return;
    tempoBpm_ = std::clamp(bpm, 1.0f, 1000.0f);
}

void SynthEngine::notePoly(int note, float velocity, int64_t tapTimeNs, int64_t blockStartFrame) {
    Voice* chosen = nullptr;
    bool steal = false;

    // 1. The same note still sounding (held or releasing): retrigger it in place.
    //    A voice already fading out to start this same note just takes the new velocity.
    for (Voice& v : voices_) {
        if (!v.isIdle() && !v.isFading() && v.note() == note) chosen = &v;
        if (v.isFading() && v.hasPending() && v.pendingNote() == note) chosen = &v;
    }
    // 2. A free voice.
    if (!chosen) {
        for (Voice& v : voices_) {
            if (v.isIdle()) {
                chosen = &v;
                break;
            }
        }
    }
    // 2b. A voice fading out to nothing (its note was cancelled or the mode changed):
    //     it is about to be free anyway, so reuse it before stealing a sounding one.
    if (!chosen) {
        for (Voice& v : voices_) {
            if (v.isFading() && !v.hasPending()) {
                chosen = &v;
                break;
            }
        }
    }
    // 3. The oldest released voice, then 4. the oldest held one (F1.1). A voice already
    //    fading out for a steal counts as held by its pending note.
    if (!chosen) {
        for (Voice& v : voices_) {
            if (v.isFading() || v.isGateOn()) continue;
            if (!chosen || v.releaseStamp < chosen->releaseStamp) chosen = &v;
        }
    }
    if (!chosen) {
        for (Voice& v : voices_) {
            if (!chosen || v.startStamp < chosen->startStamp) chosen = &v;
        }
    }

    if (chosen->isIdle() || !chosen->hasSounded() || (!chosen->isFading() && chosen->note() == note)) {
        chosen->trigger(note, velocity, true, control_);
    } else {
        // Fade the old sound out first (<= 2 ms) so the steal never clicks.
        chosen->steal(note, velocity);
        steal = true;
    }
    chosen->startStamp = ++stamp_;

    if (tapTimeNs != 0) {
        const int64_t delay = steal ? static_cast<int64_t>(Voice::kStealFadeSeconds * sampleRate_) : 0;
        // Dropped if the control thread has stopped reading; measurement only.
        renderRecords_.push({tapTimeNs, blockStartFrame + delay});
    }
}

void SynthEngine::noteOffPoly(int note) {
    for (Voice& v : voices_) {
        if (v.isFading()) {
            if (v.hasPending() && v.pendingNote() == note) v.cancelPending();
        } else if (v.isGateOn() && v.note() == note) {
            v.release();
            v.releaseStamp = ++stamp_;
        }
    }
}

void SynthEngine::renderStereo(float* left, float* right, int32_t numFrames) {
    const int64_t blockStart = framesRendered_.load(std::memory_order_relaxed);

    readTargets();
    const auto mode = static_cast<VoiceMode>(static_cast<int32_t>(raw_[index(ParamId::OscVoiceMode)]));
    if (mode != mode_) setMode(mode);

    // Events apply at the start of the call: the graph splits its render at sequencer
    // event frames, and UI events carry no frame position.
    NoteEvent event;
    while (events_.pop(event)) handle(event, blockStart, kKeys);
    for (int i = 0; i < seqEventCount_; ++i) handle(seqEvents_[static_cast<std::size_t>(i)], blockStart, kSequencer);
    seqEventCount_ = 0;

    for (int32_t offset = 0; offset < numFrames;) {
        const bool newSubBlock = subBlockPos_ == 0;
        if (newSubBlock) advanceControls();
        const int subLeft = kSubBlock - subBlockPos_;
        const int len = static_cast<int>(std::min<int32_t>(subLeft, numFrames - offset));
        float* l = left + offset;
        float* r = right + offset;
        std::fill(l, l + len, 0.0f);
        std::fill(r, r + len, 0.0f);
        for (std::size_t i = 0; i < voices_.size(); ++i) {
            Voice& v = voices_[i];
            if (v.isIdle()) continue;
            v.render(scratch_.data(), len, subLeft, newSubBlock, control_);
            const float pl = panLeft_[i];
            const float pr = panRight_[i];
            for (int j = 0; j < len; ++j) {
                const float s = scratch_[static_cast<std::size_t>(j)];
                l[j] += s * pl;
                r[j] += s * pr;
            }
        }
        subBlockPos_ = (subBlockPos_ + len) % kSubBlock;
        offset += len;
    }

    // Single writer (this thread), so load + store is fine.
    framesRendered_.store(blockStart + numFrames, std::memory_order_relaxed);
}

SynthEngine::VoiceInfo SynthEngine::voiceInfo(int voice) const {
    VoiceInfo info;
    if (voice < 0 || voice >= kVoiceCount) return info;
    const Voice& v = voices_[static_cast<std::size_t>(voice)];
    info.active = !v.isIdle();
    info.gateOn = v.isGateOn();
    info.note = v.note();
    info.frequencyHz = v.frequency();
    info.ampLevel = v.ampLevel();
    return info;
}

float SynthEngine::smoothedParam(ParamId id) const {
    const float v = smoothed(id);
    return paramInfo(id).curve == Curve::Exp ? std::exp2(v) : v;
}

}  // namespace groove
