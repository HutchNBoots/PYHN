#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

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

// Owns the Oboe output stream and the audio graph (synth + master bus).
//
// Threading:
//  - onAudioReady runs on the audio thread: it calls AudioGraph::render() and times it
//    with clock_gettime(CLOCK_MONOTONIC) (vDSO, no syscall or lock). The load figures
//    go out through relaxed atomics. It never takes mutex_, allocates or logs.
//  - Every other method runs on the app's main thread, except onErrorAfterClose,
//    which Oboe calls on its own thread. Both take mutex_ before touching stream_.
//  - The synth's control side (note events, parameters, presets, render records) is
//    used only from the main thread, apart from reopen, which runs while no callback
//    can be running.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    bool start();
    void stop();

    SynthEngine& synth() { return graph_.synth(); }

    EngineStats stats();
    void setBufferBursts(int32_t bursts);

    // Turns pending note-on render records into tap-to-tone latencies, in nanoseconds.
    // Returns how many values were written to out.
    int32_t pollTapToToneNs(int64_t* out, int32_t maxCount);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
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
};

}  // namespace groove
