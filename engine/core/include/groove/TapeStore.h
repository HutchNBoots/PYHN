#pragma once

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "groove/FxBlocks.h"
#include "groove/MasterBus.h"
#include "groove/Tape.h"
#include "groove/TapeFiles.h"

namespace groove {

// Mixer settings an export renders with (F7.2), captured when it starts: the tape channels
// (mixer channels 8..11) and the master volume. The limiter starts fresh and is primed by
// one pass of the exported loop, so it is in the state looped playback keeps it in.
struct TapeExportSettings {
    std::array<float, Tape::kTracks> volumeDb{};
    std::array<float, Tape::kTracks> pan{};
    std::array<bool, Tape::kTracks> mute{};
    float masterDb = 0.0f;
};

// Renders a snapshot's pass offline with the same code and arithmetic as AudioGraph's live
// path for tape playback at 1x forward: the tape's read path with its loop crossfade (head
// gain 1), x kPanMakeup, the tape ChannelStrips, x master volume, MasterBus. So an export is
// bit-identical to a live capture of the tape-only output with the same settings, in steady
// looped playback. The pass is rendered cyclically: call render() with priming frames first.
class TapeExportRenderer {
public:
    TapeExportRenderer(const TapeSnapshot& snapshot, const TapeExportSettings& settings, float panMakeup);
    int64_t passFrames() const { return snap_.loopEnd - snap_.loopStart; }
    void render(float* left, float* right, int32_t numFrames);

private:
    static constexpr int32_t kChunk = 256;
    const TapeSnapshot& snap_;
    float panMakeup_;
    float masterGain_;
    std::array<std::unique_ptr<ChannelStrip>, Tape::kTracks> strips_;
    MasterBus bus_;
    int64_t pos_ = 0;  // frames into the pass
    std::array<std::array<float, kChunk>, Tape::kTracks> trackL_{}, trackR_{};
};

// The tape's persistence, load and export services (docs/feature-spec.md F7.2, F7.5, F7.6):
//  - a writer thread that keeps tape-track1..4.pcm and tape-meta.bin (TapeFiles.h) in sync
//    with what the audio thread hands over through a TapePersistPort. Data records are
//    batched in memory until a Meta record (a consistent point); a commit then writes the
//    batch into the track files, fdatasyncs them, and replaces the metadata atomically
//    (temp + fsync + rename + directory fsync). So the metadata always describes data that
//    is on disk. Commits happen at most every commitIntervalMs, or at once for flush().
//  - a loader thread per loadSaved(): reads the validated files into a TapeImage and offers
//    it to the tape, then frees it once the tape has landed it.
//  - an exporter thread per startExport(): waits for the tape's snapshot, renders it with
//    TapeExportRenderer faster than real time and writes a 24-bit stereo WAV (to path +
//    ".part", renamed to path when complete).
//
// Threading: every public method is for the control thread (exactly one). No thread here
// touches the tape's audio-side state except through the tape's lock-free hand-offs, or
// through the offline pump, which its owner guarantees only runs while nothing renders.
class TapeStore {
public:
    using LogFn = void (*)(const char* message);

    struct Options {
        std::size_t ringBytes = std::size_t{4} << 20;
        int commitIntervalMs = 500;
        float panMakeup = 1.41421356237f;  // AudioGraph::kPanMakeup
    };

    explicit TapeStore(Tape& tape);
    TapeStore(Tape& tape, Options options);
    ~TapeStore();
    TapeStore(const TapeStore&) = delete;
    TapeStore& operator=(const TapeStore&) = delete;

    // Where diagnostics go (default: stderr). Never called on the audio thread.
    static void setLogger(LogFn fn);
    static void log(const char* format, ...);

    // Called by the writer thread when it is idle, so a stopped graph can hand over what is
    // still pending on its audio side (the owner must make it a no-op while a stream runs).
    void setOfflinePump(std::function<void()> pump);

    // Enables persistence into dir (which must exist). Only the first successful call counts:
    // later calls, and a bad path, are logged and change nothing. False if not enabled by it.
    bool setStorageDir(const std::string& dir);
    bool enabled() const { return enabled_; }

    // F7.6: validates the saved tape and starts loading it; false (nothing loads) if there is
    // no valid saved tape at the tape's sample rate, or a load is already running.
    bool loadSaved();

    // Waits until every tape change made so far is durable, at most timeoutMs. pumpIfStopped
    // runs on this thread between waits (it hands pending changes over if nothing renders).
    // False on timeout, an I/O error meanwhile, or when persistence is off.
    bool flush(int timeoutMs, const std::function<void()>& pumpIfStopped);

    // F7.2: false if the tape is empty or loading, or an export is running. Progress is 0
    // when this returns true.
    bool startExport(const std::string& path, bool wholeTape, const TapeExportSettings& settings,
                     const std::function<void()>& pumpIfStopped);
    // 0..1 while exporting, exactly 1 once the file is complete and closed, -1 on failure,
    // NaN when no export has run.
    float exportProgress() const { return exportProgress_.load(std::memory_order_acquire); }
    bool exportRunning() const { return exportBusy_.load(std::memory_order_acquire); }

    uint64_t durableSequence() const { return durable_.load(std::memory_order_acquire); }
    int writeErrors() const { return errors_.load(std::memory_order_acquire); }
    // Tests: waits for the loader and exporter threads to finish (joins them).
    void joinWorkers();

    // Stops and joins every thread (the destructor does it too). Only while the audio thread
    // can't run.
    void shutdown();

private:
    struct ExportJob;

    void writerLoop();
    bool commit(const std::vector<uint8_t>& batch, std::size_t end, const TapePersistRecord& meta);
    bool ensureFile(int track, int64_t fileFrames);
    void closeFiles();
    void loaderRun(tapefile::Meta meta);
    void exportRun(ExportJob* job, std::string path, TapeExportSettings settings);

    Tape& tape_;
    Options options_;
    bool enabled_ = false;
    std::string dir_;
    std::unique_ptr<TapePersistPort> port_;
    std::function<void()> offlinePump_;

    // writer
    std::thread writer_;
    std::mutex mutex_;
    std::condition_variable wake_;     // -> writer
    std::condition_variable durableCv_;  // -> flush
    std::atomic<bool> stop_{false};
    std::atomic<uint64_t> flushTarget_{0};
    std::atomic<uint64_t> durable_{0};
    std::atomic<int> errors_{0};
    std::array<int, Tape::kTracks> fds_{{-1, -1, -1, -1}};
    std::array<int64_t, Tape::kTracks> fileFrames_{};

    // loader
    std::thread loader_;
    std::atomic<bool> loaderStop_{false};

    // exporter
    std::thread exporter_;
    std::atomic<bool> exportStop_{false};
    std::atomic<bool> exportBusy_{false};
    std::atomic<float> exportProgress_;
    // The snapshot buffers: kept until the next export or destruction, never while the tape
    // may still copy into them.
    std::unique_ptr<ExportJob> job_;
};

}  // namespace groove
