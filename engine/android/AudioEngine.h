#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>

#include "groove/AudioGraph.h"

namespace groove {

struct EngineStats {
    bool running = false;
    int32_t audioApi = 0;         // oboe::AudioApi
    int32_t sharingMode = 0;      // oboe::SharingMode
    int32_t performanceMode = 0;  // oboe::PerformanceMode
    bool mmap = false;
    int32_t sampleRate = 0;
    int32_t channelCount = 0;
    int32_t framesPerBurst = 0;
    int32_t bufferSizeFrames = 0;
    int32_t bufferCapacityFrames = 0;
    int32_t xRunCount = -1;
    double outputLatencyMs = -1.0;  // Oboe's estimate; -1 if unavailable
    int32_t deviceId = 0;
    double callbackLoadAvgPct = 0.0;  // mean render time, % of the callback's buffer duration
    double callbackLoadMaxPct = 0.0;  // worst callback over the last 1-2 s
};

// Owns the Oboe output stream and the audio graph (sequencer, 8 slots, master bus).
//
// Threading:
//  - onAudioReady runs on the audio thread: it calls AudioGraph::render() and times it
//    with clock_gettime(CLOCK_MONOTONIC) (vDSO, no syscall or lock). The load figures
//    go out through relaxed atomics. It never takes mutex_, allocates or logs.
//  - Every other method runs on the app's main thread, except onErrorAfterClose,
//    which Oboe calls on its own thread. Both take mutex_ before touching stream_.
//  - The graph's control side (key notes, sequencer commands, parameters, presets, render
//    records) is used only from the main thread, apart from reopen, which runs while no
//    callback can be running.
//
// Lifetime: stop() only closes the stream. Patterns, pattern settings, parameters, presets,
// drum parameters and the tempo live in the graph for the life of the process (prepare()
// at the next start keeps them), because the UI never resends its mirror.
//
// Sequencer commands are never silently lost: the queue holds kCommandCapacity (4096)
// commands, preallocated, which is far more than a session burst; when there is no stream
// the commands are applied straight from the main thread (nothing renders then), and when
// the stream runs a full queue waits for the audio thread (it drains 512 per 256-frame
// chunk) for up to ~250 ms before the command is logged and dropped.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    bool start();
    void stop();

    AudioGraph& graph() { return graph_; }

    // Queues a sequencer command (push returns false when the queue is full), never losing it
    // silently: see the class comment.
    template <typename Push>
    bool submit(Push&& push) {
        for (int attempt = 0; attempt < kSubmitAttempts; ++attempt) {
            if (push()) return true;
            if (applyCommandsIfStopped()) continue;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        logDropped();
        return false;
    }

    // Main thread. With no stream, applies the queued sequencer commands now (so the
    // transport readout and a full queue never wait for a stream). Returns true if it did.
    bool applyCommandsIfStopped();
    TransportState transport();

    // Control-side mirror of NativeEngine.setMotionRecording (main thread only).
    void setMotionRecording(bool active) { motionRecording_ = active; }
    bool motionRecording() const { return motionRecording_; }

    EngineStats stats();
    void setBufferBursts(int32_t bursts);

    // Turns pending note-on render records from every slot into tap-to-tone latencies, in
    // nanoseconds.
    // Returns how many values were written to out.
    int32_t pollTapToToneNs(int64_t* out, int32_t maxCount);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    static constexpr int kSubmitAttempts = 250;

    void logDropped();
    // Next pending note-on render record from any slot.
    bool popNoteOnRender(NoteOnRender& out);
    bool openAndStartLocked();
    void closeLocked();
    // Audio thread only.
    void recordCallbackLoad(int64_t renderNs, int32_t numFrames);

    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    AudioGraph graph_;

    // Callback load. Written by the audio thread, reset by openAndStartLocked() before the
    // stream starts (no callback can run then).
    static_assert(std::atomic<float>::is_always_lock_free, "audio thread needs lock-free floats");
    std::atomic<float> loadAvgPct_{0.0f};
    std::atomic<float> loadMaxPct_{0.0f};
    double nsPerFrame_ = 1.0e9 / 48000.0;
    int32_t windowFrames_ = 48000;  // 1 s
    float loadAvg_ = 0.0f;          // fraction of the buffer duration
    bool haveLoad_ = false;
    float windowMax_ = 0.0f;
    float previousWindowMax_ = 0.0f;
    int32_t framesInWindow_ = 0;
    int32_t bufferBursts_ = 2;
    bool wantRunning_ = false;
    bool motionRecording_ = false;
};

}  // namespace groove
