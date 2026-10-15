#pragma once

#include <oboe/Oboe.h>

#include <cstdint>
#include <memory>
#include <mutex>

#include "groove/SynthCore.h"

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
};

// Owns the Oboe output stream and the synth.
//
// Threading:
//  - onAudioReady runs on the audio thread and only calls SynthCore::render().
//    It never takes mutex_.
//  - Every other method runs on the app's main thread, except onErrorAfterClose,
//    which Oboe calls on its own thread. Both take mutex_ before touching stream_.
//  - SynthCore's control side (note events, render records) is used only from the
//    main thread, apart from reopen, which runs while no callback can be running.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    bool start();
    void stop();

    SynthCore& synth() { return synth_; }

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

    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    SynthCore synth_;
    int32_t bufferBursts_ = 2;
    bool wantRunning_ = false;
};

}  // namespace groove
