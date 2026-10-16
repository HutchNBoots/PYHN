#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>

#include "groove/DrumMachine.h"
#include "groove/MasterBus.h"
#include "groove/Pattern.h"
#include "groove/Sequencer.h"
#include "groove/SpscQueue.h"
#include "groove/SynthEngine.h"

namespace groove {

// Everything the audio callback renders (docs/plans/2026-10-16-m2-sequencer-drums.md):
// the sequencer clock, 8 instrument slots (slot 0 the DrumMachine, slots 1..7 SynthEngines)
// summed at an equal fixed gain, the master bus limiter, then interleaved output. The mixer
// and FX slot in here in M3.
//
// Render order per chunk (<= kChunkFrames): apply the sequencer's queued commands, forward
// the keys' queued notes to their instruments (and to live recording, with this chunk's
// start frame: exactly the frame the instrument applies the note at), then render. The
// render is split at every sequencer event frame: events fire, then every instrument
// renders up to the next event, so sequencer notes and motion land sample-accurately and
// every instrument renders every frame of the stream.
//
// Threading:
//  - Control thread (exactly one): noteOn/noteOff/allNotesOff, the sequencer's command side,
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
    // Equal, fixed gain per slot (M2 has no mixer; M3 adds per-slot volume). A single slot
    // plays about 6 dB below M1's lone synth; when many slots peak together the master
    // limiter catches it (F0.6).
    static constexpr float kSlotGain = 0.5f;
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

    // Applies every queued sequencer command now, from the control thread. Only while
    // render() cannot run (no stream), so a burst of edits can't overflow the queue.
    void applyPendingCommands();

    // Writes numFrames interleaved frames: channel 0 = left, 1 = right, any further
    // channels silent; a mono stream gets the average of left and right.
    void render(float* out, int32_t numFrames, int32_t channelCount);

    // Frames rendered since prepare(). Readable from any thread.
    int64_t framesRendered() const { return frame_.load(std::memory_order_relaxed); }

private:
    struct KeyNote {
        int32_t slot = 0;  // -1: every slot (all notes off)
        NoteEvent event;
    };

    void forwardNotes(int64_t frame);
    void renderSlots(int32_t offset, int32_t numFrames);

    std::unique_ptr<DrumMachine> drums_;
    std::array<std::unique_ptr<SynthEngine>, kSynthCount> synths_;
    std::array<Instrument*, kSlots> instruments_{};
    std::unique_ptr<Sequencer> sequencer_;
    SpscQueue<KeyNote, kNoteCapacity> notes_;
    MasterBus master_;
    std::atomic<int64_t> frame_{0};

    std::array<float, kChunkFrames> left_{};
    std::array<float, kChunkFrames> right_{};
    std::array<float, kChunkFrames> slotLeft_{};
    std::array<float, kChunkFrames> slotRight_{};
};

}  // namespace groove
