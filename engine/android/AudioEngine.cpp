#include "AudioEngine.h"

#include <android/log.h>
#include <oboe/OboeExtensions.h>

#include <algorithm>
#include <ctime>

namespace groove {

namespace {
constexpr const char* kTag = "GrooveEngine";
constexpr int32_t kMaxBufferBursts = 8;
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

    // No callback can run yet, so the synth's audio-side state is safe to reset.
    // Stale records from a previous stream would map to the wrong frame clock.
    synth_.prepare(static_cast<float>(stream->getSampleRate()));
    NoteOnRender stale;
    while (synth_.popNoteOnRender(stale)) {
    }

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
    while (count < maxCount && synth_.popNoteOnRender(rec)) {
        const double presentedNs =
            static_cast<double>(refTimeNs) + static_cast<double>(rec.frameIndex - refFrame) * nsPerFrame;
        out[count++] = static_cast<int64_t>(presentedNs) - rec.tapTimeNs;
    }
    return count;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                   int32_t numFrames) {
    // Audio thread: no locks, allocation, logging or JNI. render() is checked on the
    // host by SynthCoreTest.RenderNeverAllocates.
    synth_.render(static_cast<float*>(audioData), numFrames, stream->getChannelCount());
    return oboe::DataCallbackResult::Continue;
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
