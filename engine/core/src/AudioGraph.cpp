#include "groove/AudioGraph.h"

#include <algorithm>
#include <climits>
#include <cmath>

#include "groove/TapeStore.h"

namespace groove {

AudioGraph::AudioGraph()
    : drums_(std::make_unique<DrumMachine>()),
      sequencer_(std::make_unique<Sequencer>()),
      tape_(std::make_unique<Tape>()),
      delay_(std::make_unique<StereoDelay>()),
      reverb_(std::make_unique<Reverb>()) {
    TapeStore::Options storeOptions;
    storeOptions.panMakeup = kPanMakeup;
    store_ = std::make_unique<TapeStore>(*tape_, storeOptions);
    for (auto& s : strips_) s = std::make_unique<ChannelStrip>();
    for (std::size_t i = 0; i < kSlotCount; ++i) {
        srcL_[i + 1] = postL_[i].data();
        srcR_[i + 1] = postR_[i].data();
    }
    srcL_[0] = left_.data();
    srcR_[0] = right_.data();
    for (std::size_t t = 0; t < Tape::kTracks; ++t) {
        trackOutL_[t] = trackL_[t].data();
        trackOutR_[t] = trackR_[t].data();
    }
    instruments_[kDrumSlot] = drums_.get();
    for (std::size_t i = 0; i < synths_.size(); ++i) {
        synths_[i] = std::make_unique<SynthEngine>();
        instruments_[i + kFirstSynthSlot] = synths_[i].get();
    }
    for (int s = 0; s < kSlots; ++s) sequencer_->attach(s, instruments_[static_cast<std::size_t>(s)]);
    prepare(48000.0f);
}

AudioGraph::~AudioGraph() {
    // Writer, loader and exporter threads first: they use the tape.
    store_->shutdown();
}

bool AudioGraph::setTapeStorageDir(const std::string& dir) { return store_->setStorageDir(dir); }

bool AudioGraph::loadSavedTape() { return store_->loadSaved(); }

bool AudioGraph::flushTape(int timeoutMs, const std::function<void()>& pumpIfStopped) {
    return store_->flush(timeoutMs, pumpIfStopped);
}

bool AudioGraph::startExport(const std::string& path, bool wholeTape, const std::function<void()>& pumpIfStopped) {
    TapeExportSettings s;
    for (int t = 0; t < Tape::kTracks; ++t) {
        const int ch = kFirstTapeChannel + t;
        s.volumeDb[static_cast<std::size_t>(t)] = channelParams_.get(ch, ChannelParamId::Volume);
        s.pan[static_cast<std::size_t>(t)] = channelParams_.get(ch, ChannelParamId::Pan);
        s.mute[static_cast<std::size_t>(t)] = channelParams_.get(ch, ChannelParamId::Mute) >= 0.5f;
    }
    s.masterDb = mixParams_.get(MixParamId::MasterVolume);
    return store_->startExport(path, wholeTape, s, pumpIfStopped);
}

float AudioGraph::exportProgress() const { return store_->exportProgress(); }

void AudioGraph::prepare(float sampleRate) {
    for (Instrument* inst : instruments_) inst->prepare(sampleRate);
    master_.prepare(sampleRate);
    for (auto& s : strips_) s->prepare(sampleRate);
    delay_->prepare(sampleRate);
    reverb_->prepare(sampleRate);
    sequencer_->prepare(sampleRate);  // after the instruments: it sends them the tempo
    tape_->prepare(sampleRate);       // keeps the tape at the same rate
    gainsInitialized_ = false;
    frame_.store(0, std::memory_order_relaxed);
}

SynthEngine& AudioGraph::synth(int slot) {
    if (!isSynthSlot(slot)) slot = kFirstSynthSlot;
    return *synths_[static_cast<std::size_t>(slot - kFirstSynthSlot)];
}

Instrument& AudioGraph::instrument(int slot) {
    return *instruments_[static_cast<std::size_t>(std::clamp(slot, 0, kSlots - 1))];
}

bool AudioGraph::noteOn(int slot, int note, float velocity, int64_t tapTimeNs) {
    if (slot < 0 || slot >= kSlots || note < 0 || note > 127) return false;
    if (std::isnan(velocity)) velocity = 1.0f;
    velocity = std::clamp(velocity, 0.0f, 1.0f);
    return notes_.push({slot, {NoteEvent::Type::NoteOn, note, velocity, tapTimeNs}});
}

bool AudioGraph::noteOff(int slot, int note) {
    if (slot < 0 || slot >= kSlots || note < 0 || note > 127) return false;
    return notes_.push({slot, {NoteEvent::Type::NoteOff, note, 0.0f, 0}});
}

bool AudioGraph::allNotesOff() { return notes_.push({-1, {NoteEvent::Type::AllNotesOff, 0, 0.0f, 0}}); }

void AudioGraph::applyPendingCommands() {
    sequencer_->applyCommands(framesRendered(), INT_MAX);
    tape_->applyPendingOffline();
}

void AudioGraph::forwardNotes(int64_t frame) {
    // The audio thread is the only producer of the instruments' UI note queues (the control
    // thread never calls their noteOn directly in the app), so they stay single-producer.
    // Each instrument drains its queue at its next renderStereo(), later in this same chunk,
    // so the note sounds from `frame`: the frame live recording uses (F3.6).
    KeyNote k;
    while (notes_.pop(k)) {
        const NoteEvent& e = k.event;
        if (k.slot < 0) {
            drums_->allNotesOff();
            for (auto& s : synths_) s->allNotesOff();
            continue;
        }
        if (e.type == NoteEvent::Type::NoteOn) {
            const bool queued = k.slot == kDrumSlot ? drums_->noteOn(e.note, e.velocity, e.tapTimeNs)
                                                    : synth(k.slot).noteOn(e.note, e.velocity, e.tapTimeNs);
            if (queued) sequencer_->recordLiveNote(k.slot, e.note, e.velocity, frame);
        } else if (k.slot == kDrumSlot) {
            drums_->noteOff(e.note);
        } else {
            synth(k.slot).noteOff(e.note);
        }
    }
}

void AudioGraph::renderSlots(int32_t offset, int32_t numFrames) {
    for (std::size_t s = 0; s < kSlotCount; ++s) {
        instruments_[s]->renderStereo(slotL_[s].data() + offset, slotR_[s].data() + offset, numFrames);
    }
}

void AudioGraph::applyMixParams() {
    for (int ch = 0; ch < kMixerChannelCount; ++ch) {
        ChannelStrip& strip = *strips_[static_cast<std::size_t>(ch)];
        strip.setVolumeDb(channelParams_.get(ch, ChannelParamId::Volume));
        strip.setPan(channelParams_.get(ch, ChannelParamId::Pan));
        strip.setMute(channelParams_.get(ch, ChannelParamId::Mute) >= 0.5f);
        if (ch < kFirstTapeChannel) {
            strip.setSends(channelParams_.get(ch, ChannelParamId::DelaySend),
                           channelParams_.get(ch, ChannelParamId::ReverbSend));
        } else {
            strip.setSends(0.0f, 0.0f);  // tape channels have no sends
        }
    }
    const MixParamStore& m = mixParams_;
    delay_->setTempo(sequencer_->tempoBpm());
    delay_->setTime(m.get(MixParamId::DelaySync) >= 0.5f, static_cast<int>(m.get(MixParamId::DelayDivision)),
                    m.get(MixParamId::DelayTime));
    delay_->setFeedback(m.get(MixParamId::DelayFeedback));
    delay_->setTone(m.get(MixParamId::DelayLowCut), m.get(MixParamId::DelayHighCut));
    reverb_->setSize(m.get(MixParamId::ReverbSize));
    reverb_->setDecaySeconds(m.get(MixParamId::ReverbDecay));
    reverb_->setDamping(m.get(MixParamId::ReverbDamping));

    tapeParams_.loopStartBars = static_cast<int32_t>(m.get(MixParamId::TapeLoopStart));
    tapeParams_.loopLengthBars = static_cast<int32_t>(m.get(MixParamId::TapeLoopLength));
    tapeParams_.speed = static_cast<int32_t>(m.get(MixParamId::TapeSpeed));
    tapeParams_.reverse = m.get(MixParamId::TapeReverse) >= 0.5f;
    tapeParams_.dub = m.get(MixParamId::TapeDub);
}

void AudioGraph::linkTape(int64_t chunkStart) {
    // The tape follows the transport (after this chunk's commands, before the clock moves).
    const Sequencer& seq = *sequencer_;
    Tape::Transport t;
    t.playing = seq.isPlaying();
    t.playCount = seq.playCount();
    t.playFrame = seq.playFrame();
    t.nextBarFrame = t.playing ? seq.nextBarFrame(chunkStart) : -1;
    t.barFrames = seq.roundedBarFrames();
    tape_->beginChunk(chunkStart, t, tapeParams_);

    int slot = 0;
    if (!t.playing || !tape_->commitPassRequest(slot)) return;
    // F5.6: one slot: exactly its pattern, from its next boundary. All slots: the longest
    // pattern among them, rounded up to whole bars, from that slot's next boundary. The pass
    // that started at Play is offered too, for a commit sent together with play().
    Tape::CommitPass next, fromPlay;
    if (slot >= 0) {
        const Sequencer::PatternPass p = seq.nextPatternPass(slot);
        next = {true, p.startFrame, seq.framesBetween(p.startPosition, p.units), 0};
        const Sequencer::PatternPass c = seq.currentPatternPass(slot);
        if (c.startPosition == 0) fromPlay = {true, seq.playFrame(), seq.framesBetween(0, c.units), 0};
    } else {
        Sequencer::PatternPass longest;
        int64_t longestFromPlay = 0;
        for (int s = 0; s < kSlots; ++s) {
            const Sequencer::PatternPass p = seq.nextPatternPass(s);
            if (p.units > longest.units) longest = p;
            const Sequencer::PatternPass c = seq.currentPatternPass(s);
            if (c.startPosition == 0) longestFromPlay = std::max(longestFromPlay, c.units);
        }
        const int64_t bar = seq.barUnits();
        next = {true, longest.startFrame, 0, static_cast<int32_t>((longest.units + bar - 1) / bar)};
        if (longestFromPlay > 0) {
            fromPlay = {true, seq.playFrame(), 0, static_cast<int32_t>((longestFromPlay + bar - 1) / bar)};
        }
    }
    tape_->setCommitPasses(next, fromPlay);
}

void AudioGraph::mix(int32_t n) {
    const auto un = static_cast<std::size_t>(n);
    std::fill(left_.begin(), left_.begin() + n, 0.0f);
    std::fill(right_.begin(), right_.begin() + n, 0.0f);
    std::fill(dlyL_.begin(), dlyL_.begin() + n, 0.0f);
    std::fill(dlyR_.begin(), dlyR_.begin() + n, 0.0f);
    std::fill(revL_.begin(), revL_.begin() + n, 0.0f);
    std::fill(revR_.begin(), revR_.begin() + n, 0.0f);

    // Slots: fixed headroom gain, then each slot's channel strip (post-fader output kept
    // per slot as a tape source; sends into the FX buses).
    for (std::size_t s = 0; s < kSlotCount; ++s) {
        for (std::size_t i = 0; i < un; ++i) {
            slotL_[s][i] *= kSlotGain;
            slotR_[s][i] *= kSlotGain;
        }
        std::fill(postL_[s].begin(), postL_[s].begin() + n, 0.0f);
        std::fill(postR_[s].begin(), postR_[s].begin() + n, 0.0f);
        strips_[s]->process(slotL_[s].data(), slotR_[s].data(), n, postL_[s].data(), postR_[s].data(), dlyL_.data(),
                            dlyR_.data(), revL_.data(), revR_.data());
        for (std::size_t i = 0; i < un; ++i) {
            left_[i] += postL_[s][i];
            right_[i] += postR_[s][i];
        }
    }

    // FX returns into the slot mix (smoothed return levels, F0.7).
    const float dlyTarget = mixParams_.get(MixParamId::DelayReturn);
    const float revTarget = mixParams_.get(MixParamId::ReverbReturn);
    const float masterTarget = dbToGain(mixParams_.get(MixParamId::MasterVolume));
    if (!gainsInitialized_) {
        delayReturn_ = dlyTarget;
        reverbReturn_ = revTarget;
        masterGain_ = masterTarget;
        gainsInitialized_ = true;
    }
    const float inv = 1.0f / static_cast<float>(n);
    delay_->process(dlyL_.data(), dlyR_.data(), wetL_.data(), wetR_.data(), n);
    for (std::size_t i = 0; i < un; ++i) {
        const float g = delayReturn_ + (dlyTarget - delayReturn_) * static_cast<float>(i + 1) * inv;
        left_[i] += g * wetL_[i];
        right_[i] += g * wetR_[i];
    }
    delayReturn_ = dlyTarget;
    reverb_->process(revL_.data(), revR_.data(), wetL_.data(), wetR_.data(), n);
    for (std::size_t i = 0; i < un; ++i) {
        const float g = reverbReturn_ + (revTarget - reverbReturn_) * static_cast<float>(i + 1) * inv;
        left_[i] += g * wetL_[i];
        right_[i] += g * wetR_[i];
    }
    reverbReturn_ = revTarget;

    // The tape records the slot mix with FX (or one slot's channel, for a commit) and plays
    // its 4 tracks back through their channel strips into the master mix (dry: F5).
    tape_->process(srcL_.data(), srcR_.data(), n, trackOutL_.data(), trackOutR_.data());
    for (std::size_t t = 0; t < Tape::kTracks; ++t) {
        for (std::size_t i = 0; i < un; ++i) {
            trackL_[t][i] *= kPanMakeup;
            trackR_[t][i] *= kPanMakeup;
        }
        strips_[kFirstTapeChannel + t]->process(trackL_[t].data(), trackR_[t].data(), n, left_.data(), right_.data(),
                                                nullptr, nullptr, nullptr, nullptr);
    }

    for (std::size_t i = 0; i < un; ++i) {
        const float g = masterGain_ + (masterTarget - masterGain_) * static_cast<float>(i + 1) * inv;
        left_[i] *= g;
        right_[i] *= g;
    }
    masterGain_ = masterTarget;
}

void AudioGraph::render(float* out, int32_t numFrames, int32_t channelCount) {
    if (channelCount <= 0) return;
    for (int32_t done = 0; done < numFrames;) {
        const int32_t n = std::min(kChunkFrames, numFrames - done);
        const int64_t chunkStart = frame_.load(std::memory_order_relaxed);

        sequencer_->applyCommands(chunkStart);
        forwardNotes(chunkStart);
        applyMixParams();
        linkTape(chunkStart);

        for (int32_t pos = 0; pos < n;) {
            // Fires the events due at this frame, then renders up to the next one.
            const int32_t run = sequencer_->advance(chunkStart + pos, n - pos);
            renderSlots(pos, run);
            pos += run;
        }
        mix(n);
        master_.process(left_.data(), right_.data(), n);

        for (int32_t i = 0; i < n; ++i) {
            const float l = left_[static_cast<std::size_t>(i)];
            const float r = right_[static_cast<std::size_t>(i)];
            if (channelCount == 1) {
                *out++ = 0.5f * (l + r);
                continue;
            }
            *out++ = l;
            *out++ = r;
            for (int32_t ch = 2; ch < channelCount; ++ch) *out++ = 0.0f;
        }
        // Single writer (this thread).
        frame_.store(chunkStart + n, std::memory_order_relaxed);
        done += n;
    }
}

AudioGraph::Buses AudioGraph::buses() const {
    Buses b{};
    b.delaySendL = dlyL_.data();
    b.reverbSendL = revL_.data();
    for (std::size_t s = 0; s < kSlotCount; ++s) b.slotPostL[s] = postL_[s].data();
    for (std::size_t t = 0; t < Tape::kTracks; ++t) b.trackL[t] = trackL_[t].data();
    return b;
}

}  // namespace groove
