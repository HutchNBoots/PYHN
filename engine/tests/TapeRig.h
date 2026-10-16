#pragma once

// A tape driven directly with a fake transport (bar lines every `bar` frames from Play),
// exactly as AudioGraph drives it. Shared by the tape tests.

#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

#include "groove/Tape.h"

namespace groove::test {

constexpr float kRate = 48000.0f;
constexpr float kQuant = 2.0f / 32767.0f;  // one int16 step with the tape's headroom

// Deterministic per-source test signal: band-limited-ish noise, different per source.
inline float noiseAt(int source, int64_t frame, int channel) {
    uint32_t x = static_cast<uint32_t>(frame) * 2654435761u ^ (static_cast<uint32_t>(source * 2 + channel + 1) * 40503u);
    x ^= x >> 13;
    x *= 0x5bd1e995u;
    x ^= x >> 15;
    return (static_cast<float>(x & 0xFFFF) / 65535.0f - 0.5f) * 0.8f;
}

struct Rig {
    using Signal = std::function<float(int source, int64_t frame, int channel)>;

    explicit Rig(Tape::Limits limits = {}, int64_t barFrames = 4800) : tape(std::make_unique<Tape>(limits)), bar(barFrames) {
        tape->prepare(kRate);
        for (int s = 0; s < Tape::kSources; ++s) {
            inL[static_cast<std::size_t>(s)].resize(Tape::kMaxBlockFrames);
            inR[static_cast<std::size_t>(s)].resize(Tape::kMaxBlockFrames);
            pl[static_cast<std::size_t>(s)] = inL[static_cast<std::size_t>(s)].data();
            pr[static_cast<std::size_t>(s)] = inR[static_cast<std::size_t>(s)].data();
        }
        for (int t = 0; t < Tape::kTracks; ++t) {
            outL[static_cast<std::size_t>(t)].resize(Tape::kMaxBlockFrames);
            outR[static_cast<std::size_t>(t)].resize(Tape::kMaxBlockFrames);
            ol[static_cast<std::size_t>(t)] = outL[static_cast<std::size_t>(t)].data();
            orr[static_cast<std::size_t>(t)] = outR[static_cast<std::size_t>(t)].data();
        }
    }

    void play() {
        playing = true;
        ++playCount;
        playFrame = frame;
    }
    void stop() { playing = false; }

    // Commit passes: start at the next multiple of passFrames since Play.
    int64_t passFrames = 0;
    int32_t passBars = 0;

    void run(int64_t frames, int block = 96) {
        const int64_t end = frame + frames;
        while (frame < end) {
            const auto n = static_cast<int32_t>(std::min<int64_t>(block, end - frame));
            Tape::Transport t;
            t.playing = playing;
            t.playCount = playCount;
            t.playFrame = playFrame;
            t.barFrames = bar;
            if (playing) {
                const int64_t since = frame - playFrame;
                t.nextBarFrame = playFrame + (since + bar - 1) / bar * bar;
            }
            tape->beginChunk(frame, t, params);
            int slot = 0;
            if (playing && tape->commitPassRequest(slot)) {
                const int64_t unit = passFrames > 0 ? passFrames : passBars * bar;
                const int64_t since = frame - playFrame;
                Tape::CommitPass next{true, playFrame + (since + unit - 1) / unit * unit, passFrames, passBars};
                Tape::CommitPass fromPlay{since < unit, playFrame, passFrames, passBars};
                tape->setCommitPasses(next, fromPlay);
            }
            for (int s = 0; s < Tape::kSources; ++s) {
                for (int32_t i = 0; i < n; ++i) {
                    inL[static_cast<std::size_t>(s)][static_cast<std::size_t>(i)] = signal(s, frame + i, 0);
                    inR[static_cast<std::size_t>(s)][static_cast<std::size_t>(i)] = signal(s, frame + i, 1);
                }
            }
            tape->process(pl.data(), pr.data(), n, ol.data(), orr.data());
            for (int t2 = 0; t2 < Tape::kTracks; ++t2) {
                for (int32_t i = 0; i < n; ++i) {
                    played[static_cast<std::size_t>(t2)].push_back(outL[static_cast<std::size_t>(t2)][static_cast<std::size_t>(i)]);
                }
            }
            frame += n;
        }
    }
    void runTo(int64_t target, int block = 96) {
        if (target > frame) run(target - frame, block);
    }
    TapeState state() const { return tape->state(); }
    // Left playback of a track from frame `from` (absolute rig frame) on.
    std::vector<float> playedFrom(int track, int64_t from) const {
        const auto& p = played[static_cast<std::size_t>(track)];
        return std::vector<float>(p.begin() + from, p.end());
    }

    std::unique_ptr<Tape> tape;
    int64_t bar;
    int64_t frame = 0;
    bool playing = false;
    uint32_t playCount = 0;
    int64_t playFrame = 0;
    Tape::Params params;
    Signal signal = [](int s, int64_t f, int c) { return noiseAt(s, f, c); };
    std::array<std::vector<float>, Tape::kSources> inL, inR;
    std::array<const float*, Tape::kSources> pl{}, pr{};
    std::array<std::vector<float>, Tape::kTracks> outL, outR;
    std::array<float*, Tape::kTracks> ol{}, orr{};
    std::array<std::vector<float>, Tape::kTracks> played;
};

}  // namespace groove::test
