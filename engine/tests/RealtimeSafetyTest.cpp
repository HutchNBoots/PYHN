// F0.5: the whole render path (sequencer, all 8 slots, every DSP block, the mixer, FX, tape
// and the master bus) never allocates, under note, parameter, preset, voice-mode, pattern, transport and
// recording traffic.

#include <gtest/gtest.h>

#include <array>
#include <memory>
#include <vector>

#include "AllocationCounter.h"
#include "TapeTestUtil.h"
#include "groove/Adsr.h"
#include "groove/AudioGraph.h"
#include "groove/Filters.h"
#include "groove/LevelMeters.h"
#include "groove/Lfo.h"
#include "groove/Oscillator.h"
#include "groove/Presets.h"
#include "groove/Sequencer.h"

using namespace groove;

TEST(RealtimeSafety, AudioGraphRenderNeverAllocates) {
    auto owned = std::make_unique<AudioGraph>();
    AudioGraph& graph = *owned;
    graph.prepare(48000.0f);
    std::vector<float> out(4096 * 2);
    groove::test::AllocationScope scope;
    for (int i = 0; i < 3000; ++i) {
        SynthEngine& s = graph.synth(1);
        if (i % 7 == 0) s.noteOn(36 + (i * 5) % 48, 0.5f + 0.5f * static_cast<float>(i % 3) / 2.0f, i + 1);
        if (i % 7 == 4) s.noteOff(36 + ((i - 4) * 5) % 48);
        if (i % 50 == 0) s.loadPreset((i / 50) % presetCount());
        if (i % 3 == 0) {
            const auto id = static_cast<ParamId>(i % static_cast<int>(kParamCount));
            const ParamInfo& p = paramInfo(id);
            s.setParam(id, p.min + (p.max - p.min) * static_cast<float>(i % 11) / 10.0f);
        }
        if (i % 400 == 399) s.allNotesOff();
        const int32_t frames = (i % 5 == 0) ? 4096 : 96 + i % 37;  // includes odd and huge sizes
        graph.render(out.data(), frames, 2);
        NoteOnRender rec;
        while (s.popNoteOnRender(rec)) {
        }
    }
    EXPECT_EQ(scope.count(), 0u);
}

TEST(RealtimeSafety, EveryDspBlockRendersWithoutAllocating) {
    Oscillator osc;
    osc.setSampleRate(48000.0f);
    LadderFilter ladder;
    OnePoleHighPass hpf;
    Adsr env;
    Lfo lfo;
    MasterBus bus;
    const AdsrShape shape = AdsrShape::compute(0.01f, 0.1f, 0.5f, 0.1f, 48000.0f);
    std::vector<float> l(256), r(256);
    groove::test::AllocationScope scope;
    env.gateOn();
    for (int block = 0; block < 100; ++block) {
        osc.setFrequency(100.0f + 50.0f * static_cast<float>(block));
        osc.setWave(static_cast<float>(block % 30) / 10.0f);
        ladder.setCutoff(200.0f + 100.0f * static_cast<float>(block));
        hpf.setCutoff(50.0f, 48000.0f);
        for (size_t i = 0; i < l.size(); ++i) {
            l[i] = r[i] = env.process(shape) * ladder.process(hpf.process(osc.process() + osc.sub())) + lfo.process();
        }
        bus.process(l.data(), r.data(), 256);
    }
    EXPECT_EQ(scope.count(), 0u);
}

// The full M2 graph: sequencer playing every slot with shuffle, probability, motion lanes,
// step loops, queued switches, tempo changes, live and motion recording, keys on every slot.
TEST(RealtimeSafety, FullGraphWithSequencerTrafficNeverAllocates) {
    auto owned = std::make_unique<AudioGraph>();
    AudioGraph& g = *owned;
    g.prepare(48000.0f);
    Sequencer& seq = g.sequencer();
    for (int s = 0; s < kSlotCount; ++s) {
        for (int p = 0; p < 2; ++p) {
            seq.setPatternSettings(s, p, 12 + 4 * p, (s + p) % 4, 0.3f * static_cast<float>(p), 0.6f);
            for (int i = 0; i < 16; ++i) {
                // Up to 8 hits: early and late nudges, lengths from 1/16 to 16 steps (long notes
                // across the pattern end, retriggers of held notes), chance.
                std::array<Hit, kMaxHitsPerStep> hits{};
                for (int j = 0; j < kMaxHitsPerStep; ++j) {
                    const int note = s == 0 ? 48 + (i + 3 * j) % 16 : 40 + i + 4 * j;
                    const float length = j % 4 == 0 ? Hit::kPatternGate : 0.0625f * static_cast<float>(1 << ((i + j) % 9));
                    hits[static_cast<std::size_t>(j)] = Hit{note, 0.8f, length, (i + j) % 4 == 0 ? 0.5f : 1.0f,
                                                            static_cast<float>((i + 2 * j) % 11 - 5) * 0.1f};
                }
                seq.setStep(s, p, i, hits.data(), i % 3 == 2 ? 0 : 1 + (i + s) % kMaxHitsPerStep);
            }
            seq.setMotionValue(s, p, s == 0 ? DrumMachine::motionTarget(1, DrumParamId::Tone)
                                            : static_cast<int>(ParamId::FltCutoff), 4, 900.0f);
        }
    }
    std::vector<float> out(4096 * 2);
    g.render(out.data(), 256, 2);  // let the setup commands in

    groove::test::AllocationScope scope;
    for (int i = 0; i < 4000; ++i) {
        const int slot = i % kSlotCount;
        if (i % 500 == 0) seq.play();
        if (i % 500 == 450) seq.stop();
        if (i % 97 == 0) seq.setRecordArmed(i % 194 == 0);
        if (i % 61 == 0) seq.setMotionRecording(i % 122 == 0);
        if (i % 33 == 0) seq.selectPattern(slot, (i / 33) % 2);
        if (i % 41 == 0) seq.setStepLoop(slot, 2, 5);
        if (i % 41 == 20) seq.setStepLoop(slot, -1, -1);
        if (i % 150 == 0) seq.setTempo(60.0f + static_cast<float>(i % 170));
        if (i % 7 == 0) {
            const int note = 60 + i % 5;
            seq.setStep(slot, i % 2, i % 16,
                        {Hit{note, 0.7f, Hit::kPatternGate, 0.9f, -0.5f}, Hit{note + 7, 0.7f, 16.0f, 1.0f, 0.5f}});
        }
        if (i % 29 == 0) seq.setStep(slot, i % 2, (i + 5) % 16, {});
        if (i % 13 == 0) seq.setMotionValue(slot, 0, slot == 0 ? 9 : 1, i % 16, 0.3f);
        if (i % 211 == 0) seq.clearMotionLane(slot, 1, 1);
        if (i % 307 == 0) seq.clearPattern(slot, 1);
        if (i % 17 == 0) seq.setPatternSettings(slot, 0, 8 + i % 9, i % 4, 0.5f, 0.5f);
        if (i % 5 == 0) g.noteOn(slot, slot == 0 ? 48 + i % 16 : 48 + i % 24, 0.8f, i + 1);
        if (i % 5 == 3) g.noteOff(slot, slot == 0 ? 48 + (i - 3) % 16 : 48 + (i - 3) % 24);
        if (i % 3 == 0 && slot != 0) {
            g.synth(slot).setParam(ParamId::FltCutoff, 200.0f + static_cast<float>(i % 50) * 100.0f);
            seq.recordMotion(slot, static_cast<int>(ParamId::FltCutoff), 200.0f + static_cast<float>(i % 50) * 100.0f);
        }
        if (i % 3 == 1) {
            g.drums().setParam(i % 16, DrumParamId::Pitch, static_cast<float>(i % 24) - 12.0f);
            seq.recordMotion(0, DrumMachine::motionTarget(i % 16, DrumParamId::Pitch), 3.0f);
        }
        if (i % 400 == 399) g.allNotesOff();
        const int32_t frames = (i % 9 == 0) ? 4096 : 64 + i % 200;
        g.render(out.data(), frames, 2);

        NoteOnRender rec;
        while (g.drums().popNoteOnRender(rec)) {
        }
        for (int s = 1; s < kSlotCount; ++s) {
            while (g.synth(s).popNoteOnRender(rec)) {
            }
        }
        RecordedEvent ev;
        while (seq.popRecorded(ev)) {
        }
        (void)seq.transport();
    }
    EXPECT_EQ(scope.count(), 0u);
}

// F0.5, M3: the full graph (sequencer, 8 slots, 12 channel strips, delay, reverb, the tape and
// the master bus) under tape recording, overdub, commits, undo/redo, clears, speed, reverse and
// loop changes, and mixer and FX parameter traffic, never allocates.
TEST(RealtimeSafety, FullM3GraphWithTapeAndMixerTrafficNeverAllocates) {
    auto owned = std::make_unique<AudioGraph>();
    AudioGraph& g = *owned;
    g.prepare(48000.0f);
    Sequencer& seq = g.sequencer();
    Tape& tape = g.tape();
    MixParamStore& mix = g.mixParams();
    ChannelParamStore& ch = g.channelParams();
    seq.setTempo(240.0f);  // short bars: many takes, wraps and passes
    for (int s = 0; s < kSlotCount; ++s) {
        seq.setPatternSettings(s, 0, 4 + 4 * (s % 3), 1, 0.2f, 0.6f);
        for (int i = 0; i < 16; ++i) {
            const int note = s == 0 ? 48 + i : 48 + i + s;
            seq.setStep(s, 0, i, {Hit{note, 0.8f, Hit::kPatternGate, 1.0f, i % 2 ? -0.2f : 0.1f}});
        }
        ch.set(s, ChannelParamId::DelaySend, 0.3f);
        ch.set(s, ChannelParamId::ReverbSend, 0.3f);
    }
    std::vector<float> out(4096 * 2);
    g.render(out.data(), 256, 2);
    seq.play();
    g.render(out.data(), 256, 2);

    groove::test::AllocationScope scope;
    std::array<uint8_t, Tape::kOverviewBins> ov{};
    for (int i = 0; i < 3000; ++i) {
        const int slot = i % kSlotCount;
        switch (i % 50) {
            case 0: tape.armRecord((i / 50) % 4); break;
            case 20: tape.stopRecord(); break;
            case 30: tape.commit((i / 50) % 4, (i / 50) % 9 - 1); break;
            case 40: if (i % 150 == 40) tape.undo(); break;
            case 45: if (i % 300 == 45) tape.redo(); break;
            case 47: if (i % 700 == 47) tape.clearTrack((i / 700) % 4); break;
            default: break;
        }
        if (i % 400 == 199) seq.stop();
        if (i % 400 == 200) seq.play();
        if (i % 90 == 0) mix.set(MixParamId::TapeSpeed, static_cast<float>((i / 90) % 3));
        if (i % 90 == 60) mix.set(MixParamId::TapeSpeed, 1.0f);
        if (i % 130 == 0) mix.set(MixParamId::TapeReverse, static_cast<float>((i / 130) % 2));
        if (i % 77 == 0) mix.set(MixParamId::TapeLoopStart, static_cast<float>((i / 77) % 4));
        if (i % 61 == 0) mix.set(MixParamId::TapeLoopLength, static_cast<float>((i / 61) % 3));
        if (i % 29 == 0) mix.set(MixParamId::TapeDub, static_cast<float>(i % 11) / 10.0f);
        if (i % 13 == 0) {
            const auto id = static_cast<MixParamId>(i % static_cast<int>(kMixParamCount));
            const ParamInfo& p = mixParamInfo(id);
            if (id != MixParamId::TapeSpeed && id != MixParamId::TapeReverse) {
                mix.set(id, p.min + (p.max - p.min) * static_cast<float>(i % 7) / 6.0f);
            }
        }
        if (i % 3 == 0) {
            const auto id = static_cast<ChannelParamId>(i % static_cast<int>(kChannelParamCount));
            const ParamInfo& p = channelParamInfo(id);
            ch.set(i % kMixerChannelCount, id, p.min + (p.max - p.min) * static_cast<float>(i % 5) / 4.0f);
        }
        if (i % 9 == 0) ch.set(slot, ChannelParamId::Volume, 0.0f);
        if (i % 11 == 0) ch.set(slot, ChannelParamId::Mute, 0.0f);
        if (i % 5 == 0) g.noteOn(slot, slot == 0 ? 48 + i % 16 : 48 + i % 24, 0.8f, i + 1);
        if (i % 5 == 3) g.noteOff(slot, slot == 0 ? 48 + (i - 3) % 16 : 48 + (i - 3) % 24);
        if (i % 37 == 0) seq.setTempo(200.0f + static_cast<float>(i % 40));
        const int32_t frames = (i % 9 == 0) ? 4096 : 64 + i % 200;
        g.render(out.data(), frames, 2);

        NoteOnRender rec;
        while (g.drums().popNoteOnRender(rec)) {
        }
        for (int s = 1; s < kSlotCount; ++s) {
            while (g.synth(s).popNoteOnRender(rec)) {
            }
        }
        (void)tape.state();
        tape.overview(i % 4, ov.data());
    }
    EXPECT_EQ(scope.count(), 0u);
    EXPECT_GT(tape.state().lengthFrames, 0);  // the traffic really recorded
}

// F0.5, M4: the audio path with persistence active (every take, edit and undo streams into the
// writer's ring, which overflows into pending ranges), a saved tape landing, and export
// snapshots copying (with copy-on-write under takes) never allocates. Only render() is counted:
// the control calls in between (flush, load, export) run on this thread too and may allocate.
TEST(RealtimeSafety, TapePersistenceLoadAndExportNeverAllocateOnTheAudioThread) {
    groove::test::TempDir dir;
    {
        auto saver = std::make_unique<AudioGraph>();
        ASSERT_TRUE(saver->setTapeStorageDir(dir.path()));
        saver->sequencer().setTempo(240.0f);
        saver->sequencer().play();
        saver->tape().armRecord(2);
        saver->noteOn(3, 60, 1.0f, 0);
        std::vector<float> o(512);
        for (int i = 0; i < 48000 * 2 / 256; ++i) saver->render(o.data(), 256, 2);
        saver->tape().stopRecord();
        for (int i = 0; i < 48000 / 256; ++i) saver->render(o.data(), 256, 2);
        ASSERT_TRUE(saver->flushTape(5000, {}));
    }
    auto owned = std::make_unique<AudioGraph>();
    AudioGraph& g = *owned;
    ASSERT_TRUE(g.setTapeStorageDir(dir.path()));
    Sequencer& seq = g.sequencer();
    Tape& tape = g.tape();
    seq.setTempo(240.0f);
    for (int s = 0; s < kSlotCount; ++s) {
        for (int i = 0; i < 16; ++i) {
            const int note = s == 0 ? 48 + i : 48 + i + s;
            seq.setStep(s, 0, i, {Hit{note, 0.8f, Hit::kPatternGate, 1.0f, i % 2 ? -0.2f : 0.1f}});
        }
    }
    std::vector<float> out(4096 * 2);
    g.render(out.data(), 256, 2);
    seq.play();
    std::size_t allocations = 0;
    int loads = 0, exports = 0, landed = 0, snapshots = 0;
    for (int i = 0; i < 3000; ++i) {
        switch (i % 50) {
            case 0: tape.armRecord((i / 50) % 4); break;
            case 20: tape.stopRecord(); break;
            case 30: tape.commit((i / 50) % 4, (i / 50) % 9 - 1); break;
            case 40: if (i % 150 == 40) tape.undo(); break;
            case 45: if (i % 300 == 45) tape.redo(); break;
            case 47: if (i % 700 == 47) tape.clearTrack((i / 700) % 4); break;
            default: break;
        }
        if (i % 400 == 100) loads += g.loadSavedTape() ? 1 : 0;
        if (i % 250 == 10) exports += g.startExport(dir.file("x" + std::to_string(i) + ".wav"), i % 500 == 10) ? 1 : 0;
        if (i % 97 == 0) (void)g.flushTape(0, {});
        if (i % 90 == 0) g.mixParams().set(MixParamId::TapeLoopLength, static_cast<float>((i / 90) % 3));
        if (i % 5 == 0) g.noteOn(i % kSlotCount, 48 + i % 16, 0.8f, 0);
        const bool wasLoading = tape.state().loading;
        const bool wasCopying = tape.snapshotPhase() == TapeSnapshotPhase::Copying ||
                                tape.snapshotPhase() == TapeSnapshotPhase::Requested;
        const int32_t frames = (i % 9 == 0) ? 4096 : 64 + i % 200;
        {
            groove::test::AllocationScope scope;
            g.render(out.data(), frames, 2);
            allocations += scope.count();
        }
        landed += wasLoading && tape.loadPhase() == TapeLoadPhase::Done ? 1 : 0;
        snapshots += wasCopying ? 1 : 0;
        NoteOnRender rec;
        for (int s = 1; s < kSlotCount; ++s) {
            while (g.synth(s).popNoteOnRender(rec)) {
            }
        }
        while (g.drums().popNoteOnRender(rec)) {
        }
    }
    EXPECT_EQ(allocations, 0u);
    EXPECT_GT(loads, 0);
    EXPECT_GT(landed, 0) << "a load landed during the counted renders";
    EXPECT_GT(exports, 0);
    EXPECT_GT(snapshots, 0) << "a snapshot was copied during the counted renders";
    EXPECT_GT(tape.persistPendingFrames() + static_cast<int64_t>(tape.changeSequence()), 0);
}

// Level meters (v1 close-out): metering every channel, both returns and the master, through
// clips (channels and pre-limiter master), mutes, holds and releases, at odd chunk sizes, with
// the control side reading the meters between callbacks, never allocates.
TEST(RealtimeSafety, LevelMeteringUnderLoudTrafficNeverAllocates) {
    auto owned = std::make_unique<AudioGraph>();
    AudioGraph& g = *owned;
    g.prepare(48000.0f);
    ChannelParamStore& ch = g.channelParams();
    for (int s = 0; s < kSlotCount; ++s) {
        ch.set(s, ChannelParamId::DelaySend, 0.7f);
        ch.set(s, ChannelParamId::ReverbSend, 0.7f);
    }
    std::vector<float> out(4096 * 2);
    std::array<float, LevelMeters::kCount> levels{};
    std::array<bool, LevelMeters::kCount> clips{};
    g.render(out.data(), 256, 2);
    int clipped = 0;

    groove::test::AllocationScope scope;
    for (int i = 0; i < 2000; ++i) {
        const int slot = i % kSlotCount;
        if (i % 3 == 0) g.noteOn(slot, slot == 0 ? 48 + i % 16 : 40 + i % 36, 1.0f, 0);
        if (i % 7 == 0) g.noteOff(slot, slot == 0 ? 48 + (i - 3) % 16 : 40 + (i - 3) % 36);
        if (i % 11 == 0) ch.set(slot, ChannelParamId::Volume, i % 22 == 0 ? 6.0f : -12.0f);
        if (i % 13 == 0) ch.set(slot, ChannelParamId::Mute, static_cast<float>((i / 13) % 2));
        if (i % 17 == 0) ch.set(slot, ChannelParamId::Pan, static_cast<float>(i % 3) - 1.0f);
        if (i % 19 == 0) g.mixParams().set(MixParamId::MasterVolume, i % 38 == 0 ? 6.0f : -6.0f);
        if (i % 300 == 299) g.allNotesOff();
        const int32_t frames = (i % 9 == 0) ? 4096 : 1 + (i * 37) % 300;
        g.render(out.data(), frames, i % 2 == 0 ? 2 : 1);
        g.meters().read(levels.data(), clips.data());
        clipped += clips[LevelMeters::kMaster] ? 1 : 0;
        NoteOnRender rec;
        while (g.drums().popNoteOnRender(rec)) {
        }
        for (int s = 1; s < kSlotCount; ++s) {
            while (g.synth(s).popNoteOnRender(rec)) {
            }
        }
    }
    EXPECT_EQ(scope.count(), 0u);
    EXPECT_GT(clipped, 0) << "the traffic really clipped the master";
}

// Build-20 hits at their limits on the sequencer alone: 8 hits per step on every slot, all
// 128 notes held (16-step lengths, so every pending-off list is full), the most waiting hits
// (heavy shuffle, +-50% nudges, look-ahead), the fastest steps, tempo changes, loops,
// switches, live recording onto 8-hit steps and Stop with hits waiting. Nothing allocates.
TEST(RealtimeSafety, SequencerHitsAtTheirLimitsNeverAllocate) {
    auto seq = std::make_unique<Sequencer>();
    seq->prepare(48000.0f);
    for (int s = 0; s < kSlotCount; ++s) {
        for (int p = 0; p < 2; ++p) {
            seq->setPatternSettings(s, p, 16, 2 * p, 1.0f - 0.25f * static_cast<float>(p), 0.5f);
            for (int i = 0; i < 16; ++i) {
                std::array<Hit, kMaxHitsPerStep> hits{};
                for (int j = 0; j < kMaxHitsPerStep; ++j) {
                    hits[static_cast<std::size_t>(j)] =
                        Hit{i * 8 + j, 0.8f, 16.0f, j % 3 == 0 ? 0.5f : 1.0f, j % 2 ? -0.5f : 0.5f};
                }
                seq->setStep(s, p, i, hits.data(), kMaxHitsPerStep);
            }
        }
    }
    seq->setRecordArmed(true);
    seq->applyCommands(0, 1 << 30);

    groove::test::AllocationScope scope;
    int64_t frame = 0;
    for (int i = 0; i < 6000; ++i) {
        const int slot = i % kSlotCount;
        if (i % 1500 == 0) seq->play();
        if (i % 1500 == 1400) seq->stop();
        if (i % 200 == 0) seq->setTempo(i % 400 == 0 ? 240.0f : 97.3f);
        if (i % 90 == 0) seq->selectPattern(slot, (i / 90) % 2);
        if (i % 70 == 0) seq->setStepLoop(slot, 15, 1);
        if (i % 70 == 35) seq->setStepLoop(slot, -1, -1);
        if (i % 11 == 0) seq->setStep(slot, i % 2, (i * 3) % 16, {Hit{i % 128, 0.5f, 16.0f, 1.0f, -0.5f}});
        seq->applyCommands(frame);
        if (i % 3 == 0) seq->recordLiveNote(slot, slot == 0 ? 48 + i % 16 : i % 128, 0.9f, frame);
        const int32_t n = (i % 9 == 0) ? 4096 : 1 + (i * 37) % 300;
        for (int32_t pos = 0; pos < n;) {
            const int32_t step = seq->advance(frame, n - pos);
            frame += step;
            pos += step;
        }
        RecordedEvent ev;
        while (seq->popRecorded(ev)) {
        }
    }
    EXPECT_EQ(scope.count(), 0u);
}
