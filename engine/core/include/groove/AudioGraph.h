#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

#include "groove/DrumMachine.h"
#include "groove/FxBlocks.h"
#include "groove/LevelMeters.h"
#include "groove/MasterBus.h"
#include "groove/MixStore.h"
#include "groove/Pattern.h"
#include "groove/Sequencer.h"
#include "groove/SpscQueue.h"
#include "groove/SynthEngine.h"
#include "groove/Tape.h"

namespace groove {

class TapeStore;

// Everything the audio callback renders (docs/plans/2026-10-16-m3-tape-mixer-fx.md): the
// sequencer clock, 8 instrument slots (slot 0 the DrumMachine, slots 1..7 SynthEngines), the
// mixer, master FX, the tape, the master volume and the master bus limiter, then interleaved
// output. Signal flow per chunk:
//
//   slot s x kSlotGain -> ChannelStrip s (vol, pan, mute; post-fader sends) -> slot mix
//        delay send bus  -> StereoDelay -> x dly.return -> slot mix
//        reverb send bus -> Reverb      -> x rev.return -> slot mix   (= the tape's input)
//   tape track t playback -> ChannelStrip 8 + t (no sends) -> + slot mix
//   -> x mst.volume -> MasterBus limiter -> out
//
// Each slot's post-fader output alone is also a tape source (commit of one slot, F5.6).
//
// Level meters (meters()): per chunk, the peak of each channel strip's output (post-fader,
// post-mute, post-pan; max of L and R), of each FX return after its return level, and of the
// master after the limiter; a channel or return clips at |sample| >= 1, the master when the
// mix reaches 1 before the limiter. The peaks come from the samples the mix already computes,
// so metering never changes the sound.
// Mixer, FX and tape settings come from lock-free stores (mixParams(), channelParams()),
// read once per chunk; the tape's commands go through its own SPSC queue (tape()).
//
// Render order per chunk (<= kChunkFrames): apply the sequencer's queued commands, forward
// the keys' queued notes to their instruments (and to live recording, with this chunk's
// start frame: exactly the frame the instrument applies the note at), then render. The
// render is split at every sequencer event frame: events fire, then every instrument
// renders up to the next event, so sequencer notes and motion land sample-accurately and
// every instrument renders every frame of the stream.
//
// Threading:
//  - Control thread (exactly one): noteOn/noteOff/allNotesOff, the sequencer's and the
//    tape's command sides, mixParams()/channelParams() set,
//    the instruments' parameter side (synth(slot).setParam, drums().setParam...) and their
//    popNoteOnRender. The keys' notes go through this graph's own SPSC queue (not straight
//    to an instrument's), which is what lets live recording use the exact applied frame.
//  - Audio thread: render(). Allocation-free for any numFrames; no locks, logs or I/O.
//  - prepare() and applyPendingCommands() only while render() cannot run.
class AudioGraph {
public:
    static constexpr int32_t kChunkFrames = 256;
    static constexpr int kSlots = kSlotCount;
    static constexpr int kFirstSynthSlot = 1;
    static constexpr int kSynthCount = kSlots - kFirstSynthSlot;
    // The channel strips pan with an equal-power law, so a centred channel is 3 dB down.
    // kPanMakeup restores unity at centre, so a centred slot plays at its M2 level and a tape
    // track plays back at the level it was recorded.
    static constexpr float kPanMakeup = 1.41421356237f;
    // Fixed headroom per slot before its strip (the strip's volume does the rest). A single
    // slot plays about 6 dB below M1's lone synth; when many slots peak together the master
    // limiter catches it (F0.6).
    static constexpr float kSlotGain = 0.5f * kPanMakeup;
    static constexpr std::size_t kNoteCapacity = SynthEngine::kEventCapacity;

    AudioGraph();
    ~AudioGraph();
    AudioGraph(const AudioGraph&) = delete;
    AudioGraph& operator=(const AudioGraph&) = delete;

    void prepare(float sampleRate);

    // --- control thread: notes from the keys ---
    // slot 0..7, note 0..127 (others are rejected); false if the queue is full.
    bool noteOn(int slot, int note, float velocity, int64_t tapTimeNs);
    bool noteOff(int slot, int note);
    bool allNotesOff();  // every slot

    static bool isSynthSlot(int slot) { return slot >= kFirstSynthSlot && slot < kSlots; }
    DrumMachine& drums() { return *drums_; }
    // slot 1..7 (anything else returns slot 1's synth).
    SynthEngine& synth(int slot);
    Instrument& instrument(int slot);
    Sequencer& sequencer() { return *sequencer_; }
    MasterBus& master() { return master_; }
    Tape& tape() { return *tape_; }
    MixParamStore& mixParams() { return mixParams_; }
    ChannelParamStore& channelParams() { return channelParams_; }

    // Applies every queued sequencer and tape command now (and completes a running tape
    // edit), from the control thread. Only while render() cannot run (no stream), so a burst
    // of edits can't overflow the queues.
    void applyPendingCommands();

    // --- control thread: tape persistence and export (M4, docs/feature-spec.md F7) ---
    // See TapeStore. pumpIfStopped, when given, is called on the calling thread to apply the
    // tape's pending audio-side work while nothing renders (applyPendingCommands() or the
    // engine's equivalent); a stream that runs does that work itself.
    bool setTapeStorageDir(const std::string& dir);
    bool loadSavedTape();
    bool flushTape(int timeoutMs, const std::function<void()>& pumpIfStopped = {});
    // Captures the tape channel strips' settings and the master volume now.
    bool startExport(const std::string& path, bool wholeTape, const std::function<void()>& pumpIfStopped = {});
    float exportProgress() const;
    TapeStore& tapeStore() { return *store_; }

    // Writes numFrames interleaved frames: channel 0 = left, 1 = right, any further
    // channels silent; a mono stream gets the average of left and right.
    void render(float* out, int32_t numFrames, int32_t channelCount);

    // Frames rendered since prepare(). Readable from any thread.
    int64_t framesRendered() const { return frame_.load(std::memory_order_relaxed); }

    // Level meters (see the class comment), updated once per chunk by render(). Readable from
    // any thread; prepare() resets them, and so does meters().reset() while render() cannot run.
    const LevelMeters& meters() const { return meters_; }
    LevelMeters& meters() { return meters_; }

    // --- tests only: the buses of the last rendered chunk (valid for its first n frames) ---
    struct Buses {
        const float* delaySendL;
        const float* reverbSendL;
        const float* slotPostL[kSlotCount];
        const float* slotPostR[kSlotCount];
        const float* trackL[Tape::kTracks];
    };
    Buses buses() const;

private:
    struct KeyNote {
        int32_t slot = 0;  // -1: every slot (all notes off)
        NoteEvent event;
    };

    using Chunk = std::array<float, kChunkFrames>;

    void forwardNotes(int64_t frame);
    void renderSlots(int32_t offset, int32_t numFrames);
    void applyMixParams();
    void linkTape(int64_t chunkStart);
    void mix(int32_t n);

    std::unique_ptr<DrumMachine> drums_;
    std::array<std::unique_ptr<SynthEngine>, kSynthCount> synths_;
    std::array<Instrument*, kSlots> instruments_{};
    std::unique_ptr<Sequencer> sequencer_;
    SpscQueue<KeyNote, kNoteCapacity> notes_;
    MasterBus master_;
    std::unique_ptr<Tape> tape_;
    std::unique_ptr<TapeStore> store_;  // after tape_: its threads stop before the tape goes
    std::array<std::unique_ptr<ChannelStrip>, kMixerChannelCount> strips_;
    std::unique_ptr<StereoDelay> delay_;
    std::unique_ptr<Reverb> reverb_;
    MixParamStore mixParams_;
    ChannelParamStore channelParams_;
    std::atomic<int64_t> frame_{0};
    LevelMeters meters_;

    // --- audio-thread state ---
    float masterGain_ = 1.0f;
    float delayReturn_ = 0.0f;
    float reverbReturn_ = 0.0f;
    bool gainsInitialized_ = false;
    Tape::Params tapeParams_;
    // This chunk's meter readings (LevelMeters order), filled by mix() and render().
    std::array<float, LevelMeters::kCount> peaks_{};
    std::array<bool, LevelMeters::kCount> clips_{};

    // Buffers (one chunk each).
    Chunk left_{}, right_{};                          // slot mix, then the master mix
    std::array<Chunk, kSlotCount> slotL_{}, slotR_{};  // each slot's instrument output
    std::array<Chunk, kSlotCount> postL_{}, postR_{};  // each slot's post-fader channel output
    Chunk dlyL_{}, dlyR_{}, revL_{}, revR_{};         // send buses
    Chunk wetL_{}, wetR_{};
    std::array<Chunk, Tape::kTracks> trackL_{}, trackR_{};
    std::array<const float*, Tape::kSources> srcL_{}, srcR_{};
    std::array<float*, Tape::kTracks> trackOutL_{}, trackOutR_{};
};

}  // namespace groove
