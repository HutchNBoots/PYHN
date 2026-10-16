// Saved-tape file format and POSIX helpers (see TapeFiles.h). Runs on the control, writer,
// loader and exporter threads only; never on the audio thread.

#include "groove/TapeFiles.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cmath>
#include <cstring>

namespace groove::tapefile {

namespace {

constexpr char kMagic[8] = {'G', 'R', 'V', 'T', 'A', 'P', 'E', '\0'};
constexpr std::size_t kCrcOffset = 76;

void put16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
}
void put32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
void put64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
uint16_t get16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t get32(const uint8_t* p) {
    uint32_t v = 0;
    for (int i = 3; i >= 0; --i) v = (v << 8) | p[i];
    return v;
}
uint64_t get64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
    return v;
}

std::array<uint32_t, 256> makeCrcTable() {
    std::array<uint32_t, 256> t{};
    for (uint32_t n = 0; n < 256; ++n) {
        uint32_t c = n;
        for (int k = 0; k < 8; ++k) c = (c & 1u) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
        t[n] = c;
    }
    return t;
}

int openRetry(const char* path, int flags, mode_t mode = 0644) {
    int fd;
    do {
        fd = ::open(path, flags | O_CLOEXEC, mode);
    } while (fd < 0 && errno == EINTR);
    return fd;
}

}  // namespace

std::string trackName(int track) { return "tape-track" + std::to_string(track + 1) + ".pcm"; }

std::string join(const std::string& dir, const std::string& name) {
    if (!dir.empty() && dir.back() == '/') return dir + name;
    return dir + "/" + name;
}

uint32_t crc32(const uint8_t* data, std::size_t size) {
    static const std::array<uint32_t, 256> table = makeCrcTable();
    uint32_t c = 0xFFFFFFFFu;
    for (std::size_t i = 0; i < size; ++i) c = table[(c ^ data[i]) & 0xFFu] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

void encode(const Meta& m, uint8_t* out) {
    std::memset(out, 0, kMetaBytes);
    std::memcpy(out, kMagic, sizeof(kMagic));
    put32(out + 8, kVersion);
    put32(out + 12, static_cast<uint32_t>(kMetaBytes));
    put32(out + 16, m.sampleRate);
    put16(out + 20, 2);
    put16(out + 22, 16);
    put32(out + 24, static_cast<uint32_t>(kTracks));
    put32(out + 28, m.hasAudioMask);
    put64(out + 32, static_cast<uint64_t>(m.lengthFrames));
    put64(out + 40, static_cast<uint64_t>(m.barFrames));
    put64(out + 48, static_cast<uint64_t>(m.prerollFrames));
    put64(out + 56, static_cast<uint64_t>(m.fileFrames));
    put64(out + 64, m.sequence);
    put32(out + 72, 0);
    put32(out + kCrcOffset, crc32(out, kCrcOffset));
}

DecodeResult decode(const uint8_t* p, std::size_t size, Meta& m) {
    if (size != kMetaBytes) return DecodeResult::BadSize;
    if (std::memcmp(p, kMagic, sizeof(kMagic)) != 0) return DecodeResult::BadMagic;
    if (get32(p + kCrcOffset) != crc32(p, kCrcOffset)) return DecodeResult::BadCrc;
    if (get32(p + 8) != kVersion) return DecodeResult::BadVersion;
    if (get32(p + 12) != kMetaBytes || get16(p + 20) != 2 || get16(p + 22) != 16 ||
        get32(p + 24) != static_cast<uint32_t>(kTracks)) {
        return DecodeResult::BadFormat;
    }
    m.sampleRate = get32(p + 16);
    m.hasAudioMask = get32(p + 28);
    m.lengthFrames = static_cast<int64_t>(get64(p + 32));
    m.barFrames = static_cast<int64_t>(get64(p + 40));
    m.prerollFrames = static_cast<int64_t>(get64(p + 48));
    m.fileFrames = static_cast<int64_t>(get64(p + 56));
    m.sequence = get64(p + 64);
    return DecodeResult::Ok;
}

bool readSmallFile(const std::string& path, std::size_t maxBytes, std::vector<uint8_t>& out) {
    const int fd = openRetry(path.c_str(), O_RDONLY);
    if (fd < 0) return false;
    struct stat st {};
    bool ok = ::fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size >= 0 &&
              static_cast<uint64_t>(st.st_size) <= maxBytes;
    if (ok) {
        out.resize(static_cast<std::size_t>(st.st_size));
        ok = out.empty() || preadExact(fd, out.data(), out.size(), 0);
    }
    ::close(fd);
    return ok;
}

int64_t fileSize(const std::string& path) {
    struct stat st {};
    if (::stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) return -1;
    return static_cast<int64_t>(st.st_size);
}

bool isDirectory(const std::string& path) {
    struct stat st {};
    return ::stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool preadExact(int fd, void* out, std::size_t size, int64_t offset) {
    auto* p = static_cast<uint8_t*>(out);
    while (size > 0) {
        const ssize_t n = ::pread(fd, p, size, static_cast<off_t>(offset));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= static_cast<std::size_t>(n);
        offset += n;
    }
    return true;
}

bool pwriteExact(int fd, const void* data, std::size_t size, int64_t offset) {
    const auto* p = static_cast<const uint8_t*>(data);
    while (size > 0) {
        const ssize_t n = ::pwrite(fd, p, size, static_cast<off_t>(offset));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= static_cast<std::size_t>(n);
        offset += n;
    }
    return true;
}

bool writeAtomically(const std::string& dir, const char* name, const char* tempName, const uint8_t* data,
                     std::size_t size) {
    const std::string temp = join(dir, tempName);
    const int fd = openRetry(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC);
    if (fd < 0) return false;
    bool ok = pwriteExact(fd, data, size, 0) && ::fsync(fd) == 0;
    ok = (::close(fd) == 0) && ok;
    if (ok) ok = ::rename(temp.c_str(), join(dir, name).c_str()) == 0;
    if (!ok) {
        ::unlink(temp.c_str());
        return false;
    }
    const int dfd = openRetry(dir.c_str(), O_RDONLY | O_DIRECTORY);
    if (dfd < 0) return false;
    ok = ::fsync(dfd) == 0;
    ::close(dfd);
    return ok;
}

void wavHeader(uint32_t sampleRate, uint16_t channels, uint16_t bits, int64_t frames, uint8_t* out) {
    const uint32_t blockAlign = static_cast<uint32_t>(channels) * (bits / 8u);
    const auto dataBytes = static_cast<uint32_t>(static_cast<uint64_t>(frames) * blockAlign);
    std::memcpy(out, "RIFF", 4);
    put32(out + 4, 36u + dataBytes + (dataBytes & 1u));
    std::memcpy(out + 8, "WAVE", 4);
    std::memcpy(out + 12, "fmt ", 4);
    put32(out + 16, 16);
    put16(out + 20, 1);  // PCM
    put16(out + 22, channels);
    put32(out + 24, sampleRate);
    put32(out + 28, sampleRate * blockAlign);
    put16(out + 32, static_cast<uint16_t>(blockAlign));
    put16(out + 34, bits);
    std::memcpy(out + 36, "data", 4);
    put32(out + 40, dataBytes);
}

int32_t toPcm24(float x) {
    if (!(x == x)) return 0;
    const float v = x * 8388607.0f;
    if (v >= 8388607.0f) return 8388607;
    if (v <= -8388608.0f) return -8388608;
    return static_cast<int32_t>(std::lrint(v));
}

}  // namespace groove::tapefile
