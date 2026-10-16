#pragma once

// Helpers for the M4 tape persistence, load and export tests: temporary directories, file
// access and tape comparison.

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iterator>
#include <string>
#include <thread>
#include <vector>

#include "groove/Tape.h"
#include "groove/TapeFiles.h"

namespace groove::test {

// A fresh directory under the system temp dir, removed with everything in it.
class TempDir {
public:
    TempDir() {
        static std::atomic<int> counter{0};
        const auto stamp = std::chrono::steady_clock::now().time_since_epoch().count();
        path_ = (std::filesystem::temp_directory_path() /
                 ("groove-tape-" + std::to_string(stamp) + "-" + std::to_string(counter.fetch_add(1))))
                    .string();
        std::filesystem::create_directories(path_);
    }
    ~TempDir() {
        std::error_code ec;
        std::filesystem::remove_all(path_, ec);
    }
    TempDir(const TempDir&) = delete;
    TempDir& operator=(const TempDir&) = delete;
    const std::string& path() const { return path_; }
    std::string file(const std::string& name) const { return tapefile::join(path_, name); }

private:
    std::string path_;
};

inline std::vector<uint8_t> readAll(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    return std::vector<uint8_t>(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
}

inline void writeAll(const std::string& path, const std::vector<uint8_t>& data) {
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    f.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
}

inline void copyDir(const std::string& from, const std::string& to) {
    for (const auto& e : std::filesystem::directory_iterator(from)) {
        std::filesystem::copy_file(e.path(), std::filesystem::path(to) / e.path().filename(),
                                   std::filesystem::copy_options::overwrite_existing);
    }
}

inline tapefile::Meta readMeta(const std::string& dir) {
    const auto bytes = readAll(tapefile::join(dir, tapefile::kMetaName));
    tapefile::Meta m;
    EXPECT_EQ(tapefile::decode(bytes.data(), bytes.size(), m), tapefile::DecodeResult::Ok);
    return m;
}

// Polls until done() or the timeout; true if done.
inline bool waitFor(const std::function<bool()>& done, int timeoutMs = 20000,
                    const std::function<void()>& between = {}) {
    const auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (!done()) {
        if (std::chrono::steady_clock::now() > end) return false;
        if (between) {
            between();
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }
    return true;
}

// Same length, bars and tracks, and every stored frame that can matter (the written area
// [-crossfade, length + crossfade) of each track) equal; tracks without audio are silent.
inline void expectSameTape(const Tape& a, const Tape& b) {
    const TapeState sa = a.state(), sb = b.state();
    ASSERT_EQ(sa.lengthFrames, sb.lengthFrames);
    ASSERT_EQ(sa.barFrames, sb.barFrames);
    for (int t = 0; t < Tape::kTracks; ++t) {
        ASSERT_EQ(sa.hasAudio[static_cast<std::size_t>(t)], sb.hasAudio[static_cast<std::size_t>(t)]) << t;
        const int64_t x = a.crossfadeFrames();
        int64_t mismatches = 0;
        for (int64_t f = -x; f < sa.lengthFrames + x; ++f) {
            for (int c = 0; c < 2; ++c) {
                const float va = sa.hasAudio[static_cast<std::size_t>(t)] ? a.sample(t, f, c) : 0.0f;
                if (va != b.sample(t, f, c) && ++mismatches < 4) {
                    ADD_FAILURE() << "track " << t << " frame " << f << " ch " << c << ": " << va << " vs "
                                  << b.sample(t, f, c);
                }
            }
        }
        EXPECT_EQ(mismatches, 0) << "track " << t;
    }
}

}  // namespace groove::test
