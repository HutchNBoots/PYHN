#pragma once

#include <cstdint>

namespace groove {

// Master FX and mixer channel blocks (docs/feature-spec.md F6).
//
// PUBLIC APIs ARE A CONTRACT between the FX module (which implements them, in FxBlocks.cpp
// and any Fx*.h/.cpp files it adds) and the integration module (which wires them into the
// AudioGraph). Don't change them without the lead. Internals are behind a pimpl.
//
// Threading: prepare() only while rendering cannot happen (it allocates). Every other method
// runs on the audio thread and never allocates, locks, logs or does I/O. Setters may be called
// every render chunk with the same value; every audible change is smoothed (F0.7), and a mute
// fades in 5 ms (F6.4). Every block renders offline for host tests.

// Stereo delay with tone filters in the feedback path (F6.1). process() writes the 100% wet
// signal; the graph applies the return level.
class StereoDelay {
public:
    StereoDelay();
    ~StereoDelay();
    StereoDelay(const StereoDelay&) = delete;
    StereoDelay& operator=(const StereoDelay&) = delete;

    void prepare(float sampleRate);  // allocates for at least 2 s plus modulation headroom
    void reset();                    // clears the delay lines

    void setTempo(float bpm);
    // sync true: the time is the division (index into MixParams.def dly.div options) at the tempo;
    // false: timeSeconds (0.01..2). Time changes glide without clicks.
    void setTime(bool sync, int division, float timeSeconds);
    void setFeedback(float feedback);  // 0..0.95, never runs away
    void setTone(float lowCutHz, float highCutHz);

    void process(const float* inL, const float* inR, float* outL, float* outR, int32_t numFrames);

private:
    struct Impl;
    Impl* impl_;
};

// Algorithmic reverb, an original feedback delay network (F6.2). Deterministic; process()
// writes the 100% wet signal; the graph applies the return level.
class Reverb {
public:
    Reverb();
    ~Reverb();
    Reverb(const Reverb&) = delete;
    Reverb& operator=(const Reverb&) = delete;

    void prepare(float sampleRate);
    void reset();

    void setSize(float size);            // 0..1
    void setDecaySeconds(float seconds);  // RT60, 0.2..20
    void setDamping(float damping);       // 0..1 (high-frequency loss)

    void process(const float* inL, const float* inR, float* outL, float* outR, int32_t numFrames);

private:
    struct Impl;
    Impl* impl_;
};

// One mixer channel (F6.4): volume, equal-power pan, click-free mute, post-fader sends.
// process() ADDS its output into the mix and send buses (callers clear them first).
class ChannelStrip {
public:
    ChannelStrip();
    ~ChannelStrip();
    ChannelStrip(const ChannelStrip&) = delete;
    ChannelStrip& operator=(const ChannelStrip&) = delete;

    void prepare(float sampleRate);

    void setVolumeDb(float db);  // -60 (= silence) .. +6
    void setPan(float pan);      // -1 left .. +1 right
    void setMute(bool muted);    // 5 ms fade
    void setSends(float delaySend, float reverbSend);  // 0..1, post-fader and post-mute

    // Any of the send pointers may be null (tape channels have no sends).
    void process(const float* inL, const float* inR, int32_t numFrames, float* mixL, float* mixR,
                 float* delayL, float* delayR, float* reverbL, float* reverbR);

    // The largest |sample| (left or right) this strip added to the mix in the last process()
    // call: the channel's post-fader, post-mute, post-pan peak, for the level meters.
    float lastPeak() const;

private:
    struct Impl;
    Impl* impl_;
};

}  // namespace groove
