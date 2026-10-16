#include "AudioEngine.h"

#include <android/log.h>
#include <oboe/OboeExtensions.h>

#include <time.h>

#include <algorithm>
#include <ctime>

namespace groove {

namespace {
constexpr const char* kTag = "GrooveEngine";
constexpr int32_t kMaxBufferBursts = 8;

// CLOCK_MONOTONIC via the vDSO: no syscall, no lock, safe on the audio thread.
int64_t monotonicNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
}
}  // namespace

bool AudioEngine::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    wantRunning_ = true;
    if (stream_) return true;
    return openAndStartLocked();
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    wantRunning_ = false;
    closeLocked();
}

bool AudioEngine::openAndStartLocked() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setContentType(oboe::ContentType::Music)
        ->setUsage(oboe::Usage::Media)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "openStream failed: %s",
                            oboe::convertToText(result));
        return false;
    }

    // No callback can run yet, so the graph's audio-side state is safe to reset.
    // Stale records from a previous stream would map to the wrong frame clock.
    const int32_t sampleRate = std::max(stream->getSampleRate(), 1);
    graph_.prepare(static_cast<float>(sampleRate));
    NoteOnRender stale;
    while (popNoteOnRender(stale)) {
    }
    nsPerFrame_ = 1.0e9 / static_cast<double>(sampleRate);
    windowFrames_ = sampleRate;
    loadAvg_ = 0.0f;
    haveLoad_ = false;
    windowMax_ = previousWindowMax_ = 0.0f;
    framesInWindow_ = 0;
    loadAvgPct_.store(0.0f, std::memory_order_relaxed);
    loadMaxPct_.store(0.0f, std::memory_order_relaxed);

    stream->setBufferSizeInFrames(stream->getFramesPerBurst() * bufferBursts_);

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "requestStart failed: %s",
                            oboe::convertToText(result));
        stream->close();
        return false;
    }
    stream_ = std::move(stream);
    return true;
}

void AudioEngine::closeLocked() {
    if (!stream_) return;
    stream_->stop();
    stream_->close();
    stream_.reset();
}

bool AudioEngine::popNoteOnRender(NoteOnRender& out) {
    if (graph_.drums().popNoteOnRender(out)) return true;
    for (int slot = AudioGraph::kFirstSynthSlot; slot < AudioGraph::kSlots; ++slot) {
        if (graph_.synth(slot).popNoteOnRender(out)) return true;
    }
    return false;
}

bool AudioEngine::applyCommandsIfStopped() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_) return false;
    graph_.applyPendingCommands();  // no stream, so no callback can run
    return true;
}

TransportState AudioEngine::transport() {
    applyCommandsIfStopped();  // e.g. a tempo set before the stream started
    return graph_.sequencer().transport();
}

void AudioEngine::logDropped() {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "sequencer command queue full: command dropped");
}

EngineStats AudioEngine::stats() {
    std::lock_guard<std::mutex> lock(mutex_);
    EngineStats s;
    if (!stream_) return s;
    s.running = stream_->getState() == oboe::StreamState::Started;
    s.audioApi = static_cast<int32_t>(stream_->getAudioApi());
    s.sharingMode = static_cast<int32_t>(stream_->getSharingMode());
    s.performanceMode = static_cast<int32_t>(stream_->getPerformanceMode());
    s.mmap = oboe::OboeExtensions::isMMapUsed(stream_.get());
    s.sampleRate = stream_->getSampleRate();
    s.channelCount = stream_->getChannelCount();
    s.framesPerBurst = stream_->getFramesPerBurst();
    s.bufferSizeFrames = stream_->getBufferSizeInFrames();
    s.bufferCapacityFrames = stream_->getBufferCapacityInFrames();
    s.deviceId = stream_->getDeviceId();
    if (auto xruns = stream_->getXRunCount()) s.xRunCount = xruns.value();
    if (auto latency = stream_->calculateLatencyMillis()) s.outputLatencyMs = latency.value();
    s.callbackLoadAvgPct = static_cast<double>(loadAvgPct_.load(std::memory_order_relaxed));
    s.callbackLoadMaxPct = static_cast<double>(loadMaxPct_.load(std::memory_order_relaxed));
    return s;
}

void AudioEngine::setBufferBursts(int32_t bursts) {
    std::lock_guard<std::mutex> lock(mutex_);
    bufferBursts_ = std::clamp(bursts, 1, kMaxBufferBursts);
    if (stream_) stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * bufferBursts_);
}

int32_t AudioEngine::pollTapToToneNs(int64_t* out, int32_t maxCount) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_ || maxCount <= 0) return 0;

    // Maps a stream frame to the CLOCK_MONOTONIC time it reaches the output.
    // If no timestamp is available yet, leave records queued for the next poll.
    auto ts = stream_->getTimestamp(CLOCK_MONOTONIC);
    if (!ts) return 0;
    const int64_t refFrame = ts.value().position;
    const int64_t refTimeNs = ts.value().timestamp;
    const double nsPerFrame = 1.0e9 / static_cast<double>(stream_->getSampleRate());

    int32_t count = 0;
    NoteOnRender rec;
    while (count < maxCount && popNoteOnRender(rec)) {
        const double presentedNs =
            static_cast<double>(refTimeNs) + static_cast<double>(rec.frameIndex - refFrame) * nsPerFrame;
        out[count++] = static_cast<int64_t>(presentedNs) - rec.tapTimeNs;
    }
    return count;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                   int32_t numFrames) {
    // Audio thread: no locks, allocation, logging or JNI. AudioGraph::render() (sequencer,
    // all 8 slots, master bus) is checked on the host by RealtimeSafety.*NeverAllocates.
    const int64_t start = monotonicNs();
    graph_.render(static_cast<float*>(audioData), numFrames, stream->getChannelCount());
    recordCallbackLoad(monotonicNs() - start, numFrames);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::recordCallbackLoad(int64_t renderNs, int32_t numFrames) {
    if (numFrames <= 0) return;
    const double bufferNs = static_cast<double>(numFrames) * nsPerFrame_;
    const float load = static_cast<float>(static_cast<double>(renderNs) / bufferNs);

    // Running mean: exponential, with a time constant of about 1 s of audio.
    if (!haveLoad_) {
        loadAvg_ = load;
        haveLoad_ = true;
    } else {
        const float alpha = std::min(1.0f, static_cast<float>(numFrames) / static_cast<float>(windowFrames_));
        loadAvg_ += (load - loadAvg_) * alpha;
    }

    // Max over the current and the previous 1 s window, so it covers the last 1-2 s.
    windowMax_ = std::max(windowMax_, load);
    framesInWindow_ += numFrames;
    if (framesInWindow_ >= windowFrames_) {
        previousWindowMax_ = windowMax_;
        windowMax_ = 0.0f;
        framesInWindow_ = 0;
    }

    loadAvgPct_.store(100.0f * loadAvg_, std::memory_order_relaxed);
    loadMaxPct_.store(100.0f * std::max(windowMax_, previousWindowMax_), std::memory_order_relaxed);
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    // Oboe's error thread. Typically ErrorDisconnected when headphones are plugged in or out.
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream != stream_.get()) return;  // a stream we already replaced or closed
    stream_.reset();
    __android_log_print(ANDROID_LOG_WARN, kTag, "stream closed: %s", oboe::convertToText(error));
    if (wantRunning_ && error == oboe::Result::ErrorDisconnected) openAndStartLocked();
}

}  // namespace groove
