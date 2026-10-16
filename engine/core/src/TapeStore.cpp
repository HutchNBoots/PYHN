// Tape persistence, load and export services (see TapeStore.h). Nothing here runs on the
// audio thread: these threads may allocate, lock, log and do file I/O. They meet the audio
// thread only through the tape's lock-free hand-offs (the persistence ring, the load and
// snapshot phases with acquire/release ordering).

#include "groove/TapeStore.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <limits>
#include <new>

#include "groove/MixParams.h"

namespace groove {

namespace {

using Clock = std::chrono::steady_clock;

std::atomic<TapeStore::LogFn> gLogger{nullptr};

constexpr std::size_t kMaxMetaFile = 4096;
constexpr std::size_t kBatchCommitBytes = std::size_t{32} << 20;
constexpr auto kErrorBackoff = std::chrono::milliseconds(2000);

bool isFinal(TapeLoadPhase p) {
    return p == TapeLoadPhase::Done || p == TapeLoadPhase::Cancelled || p == TapeLoadPhase::Failed ||
           p == TapeLoadPhase::Idle;
}

const char* decodeError(tapefile::DecodeResult r) {
    switch (r) {
        case tapefile::DecodeResult::Ok: return "ok";
        case tapefile::DecodeResult::BadSize: return "wrong size";
        case tapefile::DecodeResult::BadMagic: return "not a tape file";
        case tapefile::DecodeResult::BadVersion: return "unknown version";
        case tapefile::DecodeResult::BadCrc: return "checksum mismatch";
        case tapefile::DecodeResult::BadFormat: return "unsupported format";
    }
    return "?";
}

// Checks a decoded metadata file against the tape it would load into. nullptr if valid.
const char* validate(const tapefile::Meta& m, const Tape::Layout& l) {
    if (static_cast<int32_t>(m.sampleRate) != l.sampleRate) return "sample rate differs from the stream's";
    if (m.hasAudioMask > 0xFu) return "bad track mask";
    if (m.lengthFrames < 0 || m.lengthFrames > l.maxFrames) return "length out of range";
    if ((m.hasAudioMask != 0) != (m.lengthFrames > 0)) return "length and tracks disagree";
    if (m.lengthFrames > 0) {
        if (m.barFrames <= 0 || m.lengthFrames % m.barFrames != 0) return "length is not whole bars";
        if (m.lengthFrames / m.barFrames > Tape::kMaxBars) return "too many bars";
    }
    if (m.prerollFrames < 0 || m.prerollFrames > (int64_t{1} << 24)) return "bad preroll";
    if (m.fileFrames < m.prerollFrames + m.lengthFrames || m.fileFrames > (int64_t{1} << 28)) return "bad file length";
    return nullptr;
}

}  // namespace

// --- logging ---

void TapeStore::setLogger(LogFn fn) { gLogger.store(fn, std::memory_order_release); }

void TapeStore::log(const char* format, ...) {
    char buffer[512];
    va_list args;
    va_start(args, format);
    std::vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    if (LogFn fn = gLogger.load(std::memory_order_acquire)) {
        fn(buffer);
    } else {
        std::fprintf(stderr, "[tape] %s\n", buffer);
    }
}

// --- export renderer ---

TapeExportRenderer::TapeExportRenderer(const TapeSnapshot& snapshot, const TapeExportSettings& settings,
                                       float panMakeup)
    : snap_(snapshot), panMakeup_(panMakeup), masterGain_(dbToGain(settings.masterDb)) {
    const auto rate = static_cast<float>(snapshot.sampleRate);
    for (std::size_t t = 0; t < strips_.size(); ++t) {
        strips_[t] = std::make_unique<ChannelStrip>();
        strips_[t]->setVolumeDb(settings.volumeDb[t]);
        strips_[t]->setPan(settings.pan[t]);
        strips_[t]->setMute(settings.mute[t]);
        strips_[t]->setSends(0.0f, 0.0f);
        strips_[t]->prepare(rate);  // starts at the settings, as the live strips are once settled
    }
    bus_.prepare(rate);
}

void TapeExportRenderer::render(float* left, float* right, int32_t numFrames) {
    const TapeSnapshot& s = snap_;
    const int64_t pass = passFrames();
    for (int32_t done = 0; done < numFrames;) {
        const int32_t n = std::min(kChunk, numFrames - done);
        float* mixL = left + done;
        float* mixR = right + done;
        std::fill(mixL, mixL + n, 0.0f);
        std::fill(mixR, mixR + n, 0.0f);
        for (int t = 0; t < Tape::kTracks; ++t) {
            auto& tl = trackL_[static_cast<std::size_t>(t)];
            auto& tr = trackR_[static_cast<std::size_t>(t)];
            const int slot = s.slotOfTrack[static_cast<std::size_t>(t)];
            for (int32_t i = 0; i < n; ++i) {
                // Tape::renderPlayback for the main head at 1x forward with gain 1.
                float l = 0.0f, r = 0.0f;
                if (slot >= 0) {
                    const int64_t head = s.loopStart + (pos_ + i) % pass;
                    float cm, cp;
                    int64_t partner2 = 0;
                    Tape::loopFade(2 * head, 2, s.loopStart, s.loopEnd, s.fade, cm, cp, partner2);
                    const int16_t* x = s.frame(slot, head);
                    float a = Tape::tapeToFloat(x[0]);
                    float b = Tape::tapeToFloat(x[1]);
                    if (cp > 0.0f) {
                        const int16_t* px = s.frame(slot, partner2 / 2);
                        a = Tape::blendLoop(a, Tape::tapeToFloat(px[0]), cm, cp);
                        b = Tape::blendLoop(b, Tape::tapeToFloat(px[1]), cm, cp);
                    }
                    const float gm = 1.0f;
                    l += gm * a;
                    r += gm * b;
                }
                tl[static_cast<std::size_t>(i)] = l;
                tr[static_cast<std::size_t>(i)] = r;
            }
            // AudioGraph::mix: x kPanMakeup, then the tape channel strip adds into the mix.
            for (int32_t i = 0; i < n; ++i) {
                tl[static_cast<std::size_t>(i)] *= panMakeup_;
                tr[static_cast<std::size_t>(i)] *= panMakeup_;
            }
            strips_[static_cast<std::size_t>(t)]->process(tl.data(), tr.data(), n, mixL, mixR, nullptr, nullptr, nullptr,
                                                          nullptr);
        }
        for (int32_t i = 0; i < n; ++i) {
            const float g = masterGain_;
            mixL[i] *= g;
            mixR[i] *= g;
        }
        bus_.process(mixL, mixR, n);
        pos_ = (pos_ + n) % pass;
        done += n;
    }
}

// --- store ---

struct TapeStore::ExportJob {
    std::vector<int16_t> data;
    std::vector<uint8_t> copied;
    TapeSnapshot snap;
};

TapeStore::TapeStore(Tape& tape) : TapeStore(tape, Options{}) {}

TapeStore::TapeStore(Tape& tape, Options options) : tape_(tape), options_(options) {
    exportProgress_.store(std::numeric_limits<float>::quiet_NaN(), std::memory_order_relaxed);
}

TapeStore::~TapeStore() { shutdown(); }

void TapeStore::setOfflinePump(std::function<void()> pump) {
    std::lock_guard<std::mutex> lock(mutex_);
    offlinePump_ = std::move(pump);
}

bool TapeStore::setStorageDir(const std::string& dir) {
    if (enabled_) {
        if (dir != dir_) log("tape storage dir already set to %s: %s ignored", dir_.c_str(), dir.c_str());
        return false;
    }
    if (dir.empty() || !tapefile::isDirectory(dir) || ::access(dir.c_str(), W_OK | X_OK) != 0) {
        log("tape storage dir unusable (%s): tape persistence is off", dir.c_str());
        return false;
    }
    try {
        port_ = std::make_unique<TapePersistPort>(options_.ringBytes);
        dir_ = dir;
        stop_.store(false);
        writer_ = std::thread(&TapeStore::writerLoop, this);
    } catch (const std::exception& e) {
        log("tape persistence could not start (%s): off", e.what());
        port_.reset();
        dir_.clear();
        return false;
    }
    enabled_ = true;
    tape_.attachPersistence(port_.get());
    return true;
}

// --- writer thread ---

void TapeStore::writerLoop() {
    std::vector<uint8_t> batch;
    std::size_t consistentEnd = 0;  // batch bytes up to the last Meta record
    TapePersistRecord lastMeta;
    bool haveMeta = false;
    auto lastCommit = Clock::now();
    auto retryAt = Clock::time_point{};
    SpscByteRing& ring = port_->ring;
    for (;;) {
        {
            std::unique_lock<std::mutex> lock(mutex_);
            wake_.wait_for(lock, std::chrono::milliseconds(10), [this] {
                return stop_.load() || flushTarget_.load() > durable_.load();
            });
        }
        const bool stopping = stop_.load();
        if (!stopping && ring.readable() == 0) {
            std::function<void()> pump;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                pump = offlinePump_;
            }
            if (pump) pump();  // a stopped graph hands over what it still holds
        }
        TapePersistRecord rec;
        while (ring.readable() >= sizeof(rec)) {
            if (!ring.read(&rec, sizeof(rec))) break;
            const std::size_t at = batch.size();
            const std::size_t payload = rec.type == TapePersistRecord::Data ? static_cast<std::size_t>(rec.frames) * 4 : 0;
            batch.resize(at + sizeof(rec) + payload);
            std::memcpy(batch.data() + at, &rec, sizeof(rec));
            // The producer writes whole records, so the payload is there.
            if (payload > 0) ring.read(batch.data() + at + sizeof(rec), payload);
            if (rec.type == TapePersistRecord::Meta) {
                consistentEnd = batch.size();
                lastMeta = rec;
                haveMeta = true;
            }
        }
        const auto now = Clock::now();
        const bool due = stopping || flushTarget_.load() > durable_.load() ||
                         now - lastCommit >= std::chrono::milliseconds(options_.commitIntervalMs) ||
                         batch.size() >= kBatchCommitBytes;
        if (haveMeta && due && now >= retryAt) {
            const bool ok = commit(batch, consistentEnd, lastMeta);
            batch.erase(batch.begin(), batch.begin() + static_cast<std::ptrdiff_t>(consistentEnd));
            consistentEnd = 0;
            haveMeta = false;
            lastCommit = now;
            if (ok) {
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    durable_.store(lastMeta.sequence, std::memory_order_release);
                }
                durableCv_.notify_all();
            } else {
                // The files no longer match the tape: drop this batch, ask the audio thread
                // to send everything again, and retry a little later.
                errors_.fetch_add(1);
                closeFiles();
                port_->resyncRequested.store(true, std::memory_order_release);
                retryAt = now + kErrorBackoff;
                durableCv_.notify_all();
            }
        }
        if (stopping) break;
    }
    closeFiles();
}

bool TapeStore::ensureFile(int track, int64_t fileFrames) {
    const auto t = static_cast<std::size_t>(track);
    if (fds_[t] < 0) {
        const std::string path = tapefile::join(dir_, tapefile::trackName(track));
        fds_[t] = ::open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0644);
        if (fds_[t] < 0) {
            log("cannot open %s: %s", path.c_str(), std::strerror(errno));
            return false;
        }
        struct stat st {};
        const bool sized = ::fstat(fds_[t], &st) == 0 && st.st_size % tapefile::kBytesPerFrame == 0;
        fileFrames_[t] = sized ? static_cast<int64_t>(st.st_size) / tapefile::kBytesPerFrame : -1;
    }
    if (fileFrames_[t] != fileFrames) {
        if (::ftruncate(fds_[t], static_cast<off_t>(fileFrames * tapefile::kBytesPerFrame)) != 0) {
            log("cannot size track file %d: %s", track + 1, std::strerror(errno));
            return false;
        }
        fileFrames_[t] = fileFrames;
    }
    return true;
}

void TapeStore::closeFiles() {
    for (int& fd : fds_) {
        if (fd >= 0) ::close(fd);
        fd = -1;
    }
    fileFrames_.fill(0);
}

bool TapeStore::commit(const std::vector<uint8_t>& batch, std::size_t end, const TapePersistRecord& meta) {
    std::array<bool, Tape::kTracks> touched{};
    for (std::size_t at = 0; at < end;) {
        TapePersistRecord rec;
        std::memcpy(&rec, batch.data() + at, sizeof(rec));
        at += sizeof(rec);
        if (rec.type == TapePersistRecord::Meta) continue;
        if (rec.track < 0 || rec.track >= Tape::kTracks) return false;
        const auto t = static_cast<std::size_t>(rec.track);
        if (!ensureFile(rec.track, rec.fileFrames)) return false;
        touched[t] = true;
        if (rec.type == TapePersistRecord::Reset) {
            // The track's storage is all zero: so is the file (sparse where the filesystem can).
            const auto size = static_cast<off_t>(rec.fileFrames * tapefile::kBytesPerFrame);
            if (::ftruncate(fds_[t], 0) != 0 || ::ftruncate(fds_[t], size) != 0) return false;
        } else if (rec.type == TapePersistRecord::Data) {
            const int64_t fileFrame = rec.frame + rec.preroll;
            const auto bytes = static_cast<std::size_t>(rec.frames) * 4;
            if (fileFrame < 0 || fileFrame + rec.frames > rec.fileFrames) return false;
            if (!tapefile::pwriteExact(fds_[t], batch.data() + at, bytes, fileFrame * tapefile::kBytesPerFrame)) {
                log("tape write failed: %s", std::strerror(errno));
                return false;
            }
            at += bytes;
        }
    }
    for (std::size_t t = 0; t < touched.size(); ++t) {
        if (touched[t] && ::fdatasync(fds_[t]) != 0) {
            log("tape fsync failed: %s", std::strerror(errno));
            return false;
        }
    }
    tapefile::Meta m;
    m.sampleRate = meta.sampleRate;
    m.hasAudioMask = meta.hasAudioMask;
    m.lengthFrames = meta.length;
    m.barFrames = meta.bar;
    m.prerollFrames = meta.preroll;
    m.fileFrames = meta.fileFrames;
    m.sequence = meta.sequence;
    // A track the metadata calls full must exist at the right size (it does unless never written).
    for (int t = 0; t < Tape::kTracks; ++t) {
        if (((m.hasAudioMask >> t) & 1u) != 0 && !ensureFile(t, m.fileFrames)) return false;
    }
    uint8_t bytes[tapefile::kMetaBytes];
    tapefile::encode(m, bytes);
    if (!tapefile::writeAtomically(dir_, tapefile::kMetaName, tapefile::kMetaTempName, bytes, sizeof(bytes))) {
        log("tape metadata write failed: %s", std::strerror(errno));
        return false;
    }
    return true;
}

bool TapeStore::flush(int timeoutMs, const std::function<void()>& pumpIfStopped) {
    if (!enabled_) return false;
    const auto deadline = Clock::now() + std::chrono::milliseconds(std::max(0, timeoutMs));
    const int errorsBefore = errors_.load();
    if (pumpIfStopped) pumpIfStopped();
    const uint64_t target = tape_.changeSequence();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (target > flushTarget_.load()) flushTarget_.store(target);
    }
    wake_.notify_all();
    for (;;) {
        if (errors_.load() != errorsBefore) return false;
        if (durable_.load(std::memory_order_acquire) >= target) return true;
        if (Clock::now() >= deadline) return false;
        if (pumpIfStopped) pumpIfStopped();  // more may fit into the ring now
        wake_.notify_all();
        std::unique_lock<std::mutex> lock(mutex_);
        durableCv_.wait_for(lock, std::chrono::milliseconds(2), [&] {
            return durable_.load() >= target || errors_.load() != errorsBefore;
        });
    }
}

// --- loader ---

bool TapeStore::loadSaved() {
    if (!enabled_) {
        log("no tape storage dir: nothing to load");
        return false;
    }
    if (!isFinal(tape_.loadPhase())) return false;  // already loading
    std::vector<uint8_t> bytes;
    const std::string metaPath = tapefile::join(dir_, tapefile::kMetaName);
    if (!tapefile::readSmallFile(metaPath, kMaxMetaFile, bytes)) return false;  // no saved tape
    tapefile::Meta meta;
    const tapefile::DecodeResult r = tapefile::decode(bytes.data(), bytes.size(), meta);
    if (r != tapefile::DecodeResult::Ok) {
        log("saved tape not loaded: metadata %s", decodeError(r));
        return false;
    }
    if (const char* why = validate(meta, tape_.layout())) {
        log("saved tape not loaded: %s", why);
        return false;
    }
    if (meta.hasAudioMask == 0) return false;  // an empty tape: nothing to load
    for (int t = 0; t < Tape::kTracks; ++t) {
        if (((meta.hasAudioMask >> t) & 1u) == 0) continue;
        const int64_t size = tapefile::fileSize(tapefile::join(dir_, tapefile::trackName(t)));
        if (size != meta.fileFrames * tapefile::kBytesPerFrame) {
            log("saved tape not loaded: track %d file %s", t + 1, size < 0 ? "missing" : "has the wrong length");
            return false;
        }
    }
    if (loader_.joinable()) loader_.join();  // the previous one has finished (its load is final)
    if (!tape_.beginLoad()) return false;
    loaderStop_.store(false);
    try {
        loader_ = std::thread(&TapeStore::loaderRun, this, meta);
    } catch (const std::exception&) {
        tape_.failLoad();
        return false;
    }
    return true;
}

void TapeStore::loaderRun(tapefile::Meta meta) {
    const Tape::Layout l = tape_.layout();
    std::unique_ptr<TapeImage> image;
    bool ok = true;
    try {
        image = std::make_unique<TapeImage>();
        image->lengthFrames = meta.lengthFrames;
        image->barFrames = meta.barFrames;
        image->hasAudioMask = meta.hasAudioMask;
        // Only the written area matters: the tape is zero outside [-crossfade, length + crossfade).
        image->lo = std::max(-meta.prerollFrames, -l.offset);
        image->hi = std::min({meta.fileFrames - meta.prerollFrames, l.trackFrames - l.offset,
                              meta.lengthFrames + l.crossfade + l.guard});
        image->hi = std::max(image->hi, image->lo);
        const int64_t frames = image->hi - image->lo;
        for (int t = 0; t < Tape::kTracks && ok; ++t) {
            if (((meta.hasAudioMask >> t) & 1u) == 0) continue;
            auto& data = image->data[static_cast<std::size_t>(t)];
            data.resize(static_cast<std::size_t>(frames) * 2);
            const std::string path = tapefile::join(dir_, tapefile::trackName(t));
            const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
            ok = fd >= 0 && tapefile::preadExact(fd, data.data(), static_cast<std::size_t>(frames) * 4,
                                                 (image->lo + meta.prerollFrames) * tapefile::kBytesPerFrame);
            if (fd >= 0) ::close(fd);
            if (!ok) log("saved tape not loaded: track %d unreadable", t + 1);
        }
    } catch (const std::bad_alloc&) {
        log("saved tape not loaded: out of memory");
        ok = false;
    }
    if (!ok) {
        tape_.failLoad();
        return;
    }
    if (!tape_.offerLoad(image.get())) return;  // cancelled meanwhile
    // The audio thread reads the image until the load is final.
    while (!isFinal(tape_.loadPhase())) {
        if (loaderStop_.load()) {
            // shutdown(): nothing renders now, so the load can be dropped here.
            tape_.cancelLoad();
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
}

// --- export ---

bool TapeStore::startExport(const std::string& path, bool wholeTape, const TapeExportSettings& settings,
                            const std::function<void()>& pumpIfStopped) {
    if (exportBusy_.load() || path.empty()) return false;
    const TapeState st = tape_.state();
    if (st.lengthFrames <= 0 || st.loading) return false;
    int tracks = 0;
    for (bool h : st.hasAudio) tracks += h ? 1 : 0;
    if (tracks == 0) return false;
    if (exporter_.joinable()) exporter_.join();  // finished (not busy)
    if (tape_.snapshotPhase() != TapeSnapshotPhase::Idle) return false;

    job_.reset();  // the previous snapshot is Idle: the tape no longer uses it
    auto job = std::make_unique<ExportJob>();
    const Tape::Layout l = tape_.layout();
    const int64_t perTrack = st.lengthFrames + l.crossfade + 1;  // the longest pass plus its fade
    try {
        job->data.resize(static_cast<std::size_t>(tracks * perTrack) * 2);
        job->copied.assign(static_cast<std::size_t>(tracks * (perTrack / TapeSnapshot::kBlockFrames + 2)), 0);
    } catch (const std::bad_alloc&) {
        log("export: out of memory");
        return false;
    }
    job->snap.data = job->data.data();
    job->snap.capacityFrames = tracks * perTrack;
    job->snap.copied = job->copied.data();
    job->snap.blockCapacity = static_cast<int64_t>(job->copied.size());
    job->snap.wholeTape = wholeTape;
    if (!tape_.requestSnapshot(&job->snap)) return false;
    job_ = std::move(job);
    exportProgress_.store(0.0f, std::memory_order_release);  // never a stale 1 or -1 after true
    exportBusy_.store(true, std::memory_order_release);
    exportStop_.store(false);
    try {
        exporter_ = std::thread(&TapeStore::exportRun, this, job_.get(), path, settings);
    } catch (const std::exception&) {
        // job_ stays alive for the snapshot; it is released once it is no longer copied into.
        log("export: no thread");
        exportProgress_.store(-1.0f);
        exportBusy_.store(false);
        if (pumpIfStopped) pumpIfStopped();
        return false;
    }
    if (pumpIfStopped) pumpIfStopped();  // no stream: the snapshot completes right here
    return true;
}

void TapeStore::exportRun(ExportJob* job, std::string path, TapeExportSettings settings) {
    const auto finish = [&](bool ok) {
        tape_.releaseSnapshot();
        exportProgress_.store(ok ? 1.0f : -1.0f, std::memory_order_release);
        exportBusy_.store(false, std::memory_order_release);
    };
    // The audio thread copies the tape into the job's buffers.
    for (;;) {
        const TapeSnapshotPhase p = tape_.snapshotPhase();
        if (p == TapeSnapshotPhase::Ready) break;
        if (p == TapeSnapshotPhase::Failed || p == TapeSnapshotPhase::Idle) {
            log("export: the tape changed before it could be copied");
            return finish(false);
        }
        if (exportStop_.load()) {
            tape_.cancelSnapshot();  // shutdown(): nothing renders now
            return finish(false);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    const TapeSnapshot& snap = job->snap;
    const std::string temp = path + ".part";
    FILE* f = std::fopen(temp.c_str(), "wb");
    if (!f) {
        log("export: cannot create %s: %s", temp.c_str(), std::strerror(errno));
        return finish(false);
    }
    bool ok = true;
    try {
        TapeExportRenderer renderer(snap, settings, options_.panMakeup);
        const int64_t pass = renderer.passFrames();
        uint8_t header[tapefile::kWavHeaderBytes];
        tapefile::wavHeader(static_cast<uint32_t>(snap.sampleRate), 2, 24, pass, header);
        ok = std::fwrite(header, 1, sizeof(header), f) == sizeof(header);
        constexpr int32_t kBlock = 4096;
        std::vector<float> l(kBlock), r(kBlock);
        std::vector<uint8_t> pcm(static_cast<std::size_t>(kBlock) * 6);
        // One pass primes the limiter (looped playback has always just played the pass), the
        // next is written.
        for (int round = 0; round < 2 && ok; ++round) {
            for (int64_t done = 0; done < pass && ok;) {
                if (exportStop_.load()) {
                    ok = false;
                    break;
                }
                const auto n = static_cast<int32_t>(std::min<int64_t>(kBlock, pass - done));
                renderer.render(l.data(), r.data(), n);
                if (round == 1) {
                    for (int32_t i = 0; i < n; ++i) {
                        const int32_t sl = tapefile::toPcm24(l[static_cast<std::size_t>(i)]);
                        const int32_t sr = tapefile::toPcm24(r[static_cast<std::size_t>(i)]);
                        uint8_t* p = pcm.data() + static_cast<std::size_t>(i) * 6;
                        for (int b = 0; b < 3; ++b) {
                            p[b] = static_cast<uint8_t>(static_cast<uint32_t>(sl) >> (8 * b));
                            p[3 + b] = static_cast<uint8_t>(static_cast<uint32_t>(sr) >> (8 * b));
                        }
                    }
                    const auto bytes = static_cast<std::size_t>(n) * 6;
                    ok = std::fwrite(pcm.data(), 1, bytes, f) == bytes;
                }
                done += n;
                const double fraction = (static_cast<double>(round * pass + done)) / static_cast<double>(2 * pass);
                exportProgress_.store(static_cast<float>(std::min(0.99, fraction)), std::memory_order_release);
            }
        }
    } catch (const std::bad_alloc&) {
        ok = false;
    }
    ok = std::fflush(f) == 0 && ok;
    ok = ::fsync(::fileno(f)) == 0 && ok;
    ok = std::fclose(f) == 0 && ok;
    ok = ok && std::rename(temp.c_str(), path.c_str()) == 0;
    if (!ok) {
        log("export to %s failed", path.c_str());
        std::remove(temp.c_str());
    }
    finish(ok);
}

// --- lifetime ---

void TapeStore::joinWorkers() {
    if (loader_.joinable()) loader_.join();
    if (exporter_.joinable()) exporter_.join();
}

void TapeStore::shutdown() {
    // The writer first: its offline pump may be doing the tape's audio-side work, which the
    // exporter and loader (and this thread) touch below when they cancel.
    if (writer_.joinable()) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stop_.store(true);
        }
        wake_.notify_all();
        writer_.join();
    }
    exportStop_.store(true);
    loaderStop_.store(true);
    if (exporter_.joinable()) exporter_.join();
    if (loader_.joinable()) loader_.join();
    tape_.cancelLoad();
    tape_.cancelSnapshot();
}

}  // namespace groove
