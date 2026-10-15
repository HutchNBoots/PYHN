#pragma once

#include <atomic>
#include <cstdint>

#include "groove/Envelope.h"
#include "groove/MonoNoteStack.h"
#include "groove/Oscillator.h"
#include "groove/SpscQueue.h"

namespace groove {

struct NoteEvent {
    enum class Type : int32_t { NoteOn, NoteOff, AllNotesOff };
    Type type = Type::NoteOn;
    int32_t note = 0;
    // CLOCK_MONOTONIC time of the touch that caused this event, or 0 if not measured.
    int64_t tapTimeNs = 0;
};

// Emitted by the audio thread for every measured note-on: the stream frame where the
// note's first sample lands, so the control thread can map it to a presentation time.
struct NoteOnRender {
    int64_t tapTimeNs = 0;
    int64_t frameIndex = 0;
};

// Monophonic test synth: one oscillator into a gate envelope.
//
// Threading:
//  - Control thread (exactly one): noteOn/noteOff/allNotesOff, popNoteOnRender, setters.
//  - Audio thread: render().
//  - prepare() only while render() cannot run (stream stopped or closed).
class SynthCore {
public:
    static constexpr std::size_t kEventCapacity = 256;
    static constexpr std::size_t kRenderRecordCapacity = 64;

    SynthCore();

    // Not real-time safe with respect to render(); call with the stream stopped.
    // Also restarts the frame counter at 0, matching a freshly opened stream.
    void prepare(float sampleRate);

    // --- control thread ---
    bool noteOn(int note, int64_t tapTimeNs);
    bool noteOff(int note);
    bool allNotesOff();
    bool popNoteOnRender(NoteOnRender& out) { return renderRecords_.pop(out); }
    void setWaveform(Waveform w) { waveform_.store(static_cast<int32_t>(w), std::memory_order_relaxed); }
    void setGain(float gain) { gain_.store(gain, std::memory_order_relaxed); }

    // --- audio thread ---
    // Writes numFrames interleaved frames of channelCount channels (same signal in each).
    void render(float* out, int32_t numFrames, int32_t channelCount);

    // Frames rendered since prepare(). Readable from any thread.
    int64_t framesRendered() const { return framesRendered_.load(std::memory_order_relaxed); }

private:
    void handle(const NoteEvent& e);
    void startNote(int note, bool retrigger);

    SpscQueue<NoteEvent, kEventCapacity> events_;
    SpscQueue<NoteOnRender, kRenderRecordCapacity> renderRecords_;
    std::atomic<int32_t> waveform_{static_cast<int32_t>(Waveform::Saw)};
    std::atomic<float> gain_{0.5f};
    std::atomic<int64_t> framesRendered_{0};

    // Audio-thread state.
    Oscillator osc_;
    Envelope env_;
    MonoNoteStack held_;
    float smoothedGain_ = 0.5f;
    float gainCoeff_ = 0.0f;
};

}  // namespace groove
