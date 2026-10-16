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

#include "groove/AudioGraph.h"
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

// The M2 full graph in 2 ms callbacks: the sequencer playing a busy demo-like pattern on all
// 8 slots (a 16-step beat on the drums; 4-note chords on every synth slot, so all 28 synth
// voices sound, with blended waves, resonance, drive and LFO), motion on every slot.
TEST(EngineBenchmark, FullGraphBusyPattern) {
    constexpr int kBlock = 96;
    constexpr float kRate = 48000.0f;
    constexpr double kSeconds = 4.0;

    auto g = std::make_unique<AudioGraph>();
    Sequencer& seq = g->sequencer();
    for (int s = 1; s < kSlotCount; ++s) {
        SynthEngine& e = g->synth(s);
        e.loadPreset(s);
        e.setParam(ParamId::OscVoiceMode, 0.0f);
        e.setParam(ParamId::OscWave, 0.4f);
        e.setParam(ParamId::OscSub, 0.5f);
        e.setParam(ParamId::OscNoise, 0.1f);
        e.setParam(ParamId::FltReso, 0.5f);
        e.setParam(ParamId::FltDrive, 6.0f);
        e.setParam(ParamId::LfoDepth, 0.3f);
        e.setParam(ParamId::AmpSustain, 1.0f);
    }
    g->prepare(kRate);
    seq.setTempo(128.0f);
    for (int s = 0; s < kSlotCount; ++s) {
        seq.setPatternSettings(s, 0, 16, 1, 0.2f, 0.9f);
        for (int i = 0; i < 16; ++i) {
            if (s == 0) {
                const int hits[] = {i % 4 == 0 ? 48 : 54, i % 8 == 4 ? 50 : 55};
                seq.setStep(0, 0, i, true, hits, 2, 0.9f, -1.0f, 1.0f);
            } else if (i % 2 == 0) {
                const int root = 36 + s * 4 + (i / 4) % 3;
                const int chord[] = {root, root + 4, root + 7, root + 11};
                seq.setStep(s, 0, i, true, chord, 4, 0.8f, 1.0f, 1.0f);  // gate 2 steps' worth: always 4 voices
            }
            seq.setMotionValue(s, 0, s == 0 ? DrumMachine::motionTarget(i % 16, DrumParamId::Tone)
                                            : static_cast<int>(ParamId::FltCutoff),
                               i, s == 0 ? 3000.0f + 500.0f * static_cast<float>(i) : 400.0f + 200.0f * static_cast<float>(i));
        }
    }
    seq.play();

    std::vector<float> out(2 * kBlock);
    const int blocks = static_cast<int>(kSeconds * kRate / kBlock);
    g->render(out.data(), kBlock, 2);  // warm up and apply the setup
    const auto start = std::chrono::steady_clock::now();
    for (int b = 0; b < blocks; ++b) g->render(out.data(), kBlock, 2);
    const double cpu = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    const double audio = static_cast<double>(blocks) * kBlock / kRate;
    const double factor = audio / cpu;
    int sounding = 0;
    for (int s = 1; s < kSlotCount; ++s) {
        for (int v = 0; v < SynthEngine::kVoiceCount; ++v) sounding += g->synth(s).voiceInfo(v).active ? 1 : 0;
    }
    std::printf("full graph (drums + 7 synths, %d synth voices sounding, sequencer): %.2f s in %.3f s -> "
                "realtime factor %.1fx (%.1f%% of one core)%s\n",
                sounding, audio, cpu, factor, 100.0 / factor, kSanitized ? " [sanitized build]" : "");
    RecordProperty("full_graph_realtime_factor", std::to_string(factor));
    EXPECT_GE(sounding, 24);
    if (!kSanitized) EXPECT_GT(factor, 1.5);
}
