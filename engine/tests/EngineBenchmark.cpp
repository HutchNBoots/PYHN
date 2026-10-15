// Host benchmark: the M3 full load is 8 synth slots x 4 voices plus FX. Renders 8 engines
// with all 4 voices busy (saw, resonant filter with envelope, LFO, sub and noise on) in
// 2 ms buffers, and prints the realtime factor (seconds of audio per second of CPU).
// The assertion is deliberately loose: the real budget (F0.4) is checked on the phone.

#include <gtest/gtest.h>

#include <array>
#include <chrono>
#include <cstdio>
#include <memory>
#include <vector>

#include "groove/MasterBus.h"
#include "groove/SynthEngine.h"

using namespace groove;

namespace {

#if defined(__SANITIZE_ADDRESS__) || defined(__SANITIZE_THREAD__)
constexpr bool kSanitized = true;
#elif defined(__has_feature)
#if __has_feature(address_sanitizer) || __has_feature(thread_sanitizer)
constexpr bool kSanitized = true;
#else
constexpr bool kSanitized = false;
#endif
#else
constexpr bool kSanitized = false;
#endif

}  // namespace

TEST(EngineBenchmark, EightEnginesOfFourVoices) {
    constexpr int kEngines = 8;
    constexpr int kBlock = 96;
    constexpr float kRate = 48000.0f;
    constexpr double kSeconds = 4.0;

    std::vector<std::unique_ptr<SynthEngine>> engines;
    for (int e = 0; e < kEngines; ++e) {
        auto s = std::make_unique<SynthEngine>();
        s->setParam(ParamId::OscWave, 0.4f);  // two waves blended: the costlier case
        s->setParam(ParamId::OscSub, 0.5f);
        s->setParam(ParamId::OscNoise, 0.1f);
        s->setParam(ParamId::FltReso, 0.5f);
        s->setParam(ParamId::FltDrive, 6.0f);
        s->setParam(ParamId::LfoDepth, 0.3f);
        s->setParam(ParamId::AmpSustain, 1.0f);
        s->prepare(kRate);
        engines.push_back(std::move(s));
    }
    MasterBus bus;
    std::vector<float> l(kBlock), r(kBlock), mixL(kBlock), mixR(kBlock);

    const int blocks = static_cast<int>(kSeconds * kRate / kBlock);
    const auto start = std::chrono::steady_clock::now();
    for (int b = 0; b < blocks; ++b) {
        if (b % 250 == 0) {  // new chords every 0.5 s keep envelopes and steals busy
            for (int e = 0; e < kEngines; ++e) {
                for (int v = 0; v < 4; ++v) engines[static_cast<size_t>(e)]->noteOn(36 + e * 3 + v * 4 + (b / 250) % 5, 0.8f, 0);
            }
        }
        std::fill(mixL.begin(), mixL.end(), 0.0f);
        std::fill(mixR.begin(), mixR.end(), 0.0f);
        for (auto& s : engines) {
            s->renderStereo(l.data(), r.data(), kBlock);
            for (int i = 0; i < kBlock; ++i) {
                mixL[static_cast<size_t>(i)] += l[static_cast<size_t>(i)];
                mixR[static_cast<size_t>(i)] += r[static_cast<size_t>(i)];
            }
        }
        bus.process(mixL.data(), mixR.data(), kBlock);
    }
    const double cpu = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    const double audio = static_cast<double>(blocks) * kBlock / kRate;
    const double factor = audio / cpu;
    const double loadPct = 100.0 / factor;
    std::printf("8 engines x 4 voices: %.2f s of audio in %.3f s -> realtime factor %.1fx (%.1f%% of one core)%s\n",
                audio, cpu, factor, loadPct, kSanitized ? " [sanitized build]" : "");
    RecordProperty("realtime_factor", std::to_string(factor));
    if (!kSanitized) EXPECT_GT(factor, 1.5);
}
