// JNI entry points for com.groovebox.engine.NativeEngine (a Kotlin `object`, so each
// function receives the singleton instance as `thiz`).
// Every function here is called from the app's main thread only (see AudioEngine.h):
// every UI-to-audio queue and parameter store allows exactly one control-thread writer.
// Every slot, pattern, step, key, id, target and index coming from Kotlin is validated
// here; out-of-range calls are ignored (string getters return "", value getters return 0,
// note calls return false). Synth-only calls ignore slot 0 (the drum kit).

#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>

#include "AudioEngine.h"
#include "groove/DrumMachine.h"
#include "groove/DrumParams.h"
#include "groove/Pattern.h"
#include "groove/Presets.h"
#include "groove/Sequencer.h"
#include "groove/SynthParams.h"

namespace {

groove::AudioEngine& engine() {
    // Lives for the whole process; never destroyed so no callback can outlive it.
    static auto* instance = new groove::AudioEngine();
    return *instance;
}

groove::Sequencer& seq() { return engine().graph().sequencer(); }

// Layout shared with NativeEngine.kt (EngineStats.SIZE and EngineStats.from()).
constexpr jsize kStatsSize = 15;
// Layout shared with NativeEngine.readTable: [min, max, default, curve ordinal].
constexpr jsize kParamInfoSize = 4;
// TransportState.SIZE: [playing, armed, tempo, step x8, pattern x8, queued x8].
constexpr jsize kTransportSize = 3 + 3 * groove::kSlotCount;
// RecordedEvent records: [kind (0 note, 1 motion), slot, pattern, step, note or target, value].
constexpr jsize kRecordSize = 6;
constexpr int kMaxRecordsPerPoll = 64;

bool validParam(jint id) { return id >= 0 && id < static_cast<jint>(groove::kParamCount); }
bool validDrumParam(jint id) { return id >= 0 && id < static_cast<jint>(groove::kDrumParamCount); }
bool validDrumKey(jint key) { return key >= 0 && key < groove::DrumMachine::kSoundCount; }
bool validPreset(jint index) { return index >= 0 && index < static_cast<jint>(groove::presetCount()); }
bool validSlot(jint slot) { return slot >= 0 && slot < groove::kSlotCount; }
bool validPattern(jint pattern) { return pattern >= 0 && pattern < groove::kPatternCount; }
bool validStep(jint step) { return step >= 0 && step < groove::kMaxSteps; }
bool synthSlot(jint slot) { return groove::AudioGraph::isSynthSlot(slot); }

const groove::ParamInfo* paramInfo(jint id) {
    return validParam(id) ? &groove::paramInfo(static_cast<groove::ParamId>(id)) : nullptr;
}

const groove::ParamInfo* drumParamInfo(jint id) {
    return validDrumParam(id) ? &groove::drumParamInfo(static_cast<groove::DrumParamId>(id)) : nullptr;
}

void writeInfo(JNIEnv* env, const groove::ParamInfo* p, jdoubleArray out) {
    if (!p || out == nullptr || env->GetArrayLength(out) < kParamInfoSize) return;
    const std::array<jdouble, kParamInfoSize> values = {
        static_cast<jdouble>(p->min),
        static_cast<jdouble>(p->max),
        static_cast<jdouble>(p->def),
        static_cast<jdouble>(static_cast<int32_t>(p->curve)),
    };
    env->SetDoubleArrayRegion(out, 0, kParamInfoSize, values.data());
}

}  // namespace

extern "C" {

// --- stream ---

JNIEXPORT jboolean JNICALL Java_com_groovebox_engine_NativeEngine_nativeStart(JNIEnv*, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeStop(JNIEnv*, jobject) { engine().stop(); }

// --- notes from the keys ---

JNIEXPORT jboolean JNICALL Java_com_groovebox_engine_NativeEngine_nativeNoteOn(JNIEnv*, jobject, jint slot, jint note,
                                                                               jfloat velocity, jlong tapTimeNs) {
    // The graph rejects bad slots and notes outside 0..127 and clamps velocity to 0..1.
    // While armed and playing, the audio thread also records it (at the frame it applies it).
    return engine().graph().noteOn(slot, note, velocity, tapTimeNs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_groovebox_engine_NativeEngine_nativeNoteOff(JNIEnv*, jobject, jint slot,
                                                                                jint note) {
    return engine().graph().noteOff(slot, note) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeAllNotesOff(JNIEnv*, jobject) {
    engine().graph().allNotesOff();
}

// --- synth parameter table and slots 1..7 ---

JNIEXPORT jint JNICALL Java_com_groovebox_engine_NativeEngine_nativeParamCount(JNIEnv*, jobject) {
    return static_cast<jint>(groove::kParamCount);
}

JNIEXPORT jstring JNICALL Java_com_groovebox_engine_NativeEngine_nativeParamKey(JNIEnv* env, jobject, jint id) {
    const groove::ParamInfo* p = paramInfo(id);
    return env->NewStringUTF(p ? p->key : "");
}

JNIEXPORT jstring JNICALL Java_com_groovebox_engine_NativeEngine_nativeParamLabel(JNIEnv* env, jobject, jint id) {
    const groove::ParamInfo* p = paramInfo(id);
    return env->NewStringUTF(p ? p->label : "");
}

JNIEXPORT jstring JNICALL Java_com_groovebox_engine_NativeEngine_nativeParamUnit(JNIEnv* env, jobject, jint id) {
    const groove::ParamInfo* p = paramInfo(id);
    return env->NewStringUTF(p ? p->unit : "");
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeParamInfo(JNIEnv* env, jobject, jint id,
                                                                              jdoubleArray out) {
    writeInfo(env, paramInfo(id), out);
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetParam(JNIEnv*, jobject, jint slot, jint id,
                                                                             jfloat value) {
    if (!synthSlot(slot) || !validParam(id)) return;
    // ParamStore clamps to the range, rounds Int/Enum values and maps NaN to the default.
    engine().graph().synth(slot).setParam(static_cast<groove::ParamId>(id), value);
    if (engine().motionRecording() && !std::isnan(value)) {
        // F3.7: the audio thread writes it only while playing.
        engine().submit([&] { return seq().recordMotion(slot, id, value); });
    }
}

JNIEXPORT jfloat JNICALL Java_com_groovebox_engine_NativeEngine_nativeGetParam(JNIEnv*, jobject, jint slot, jint id) {
    if (!synthSlot(slot) || !validParam(id)) return 0.0f;
    return engine().graph().synth(slot).param(static_cast<groove::ParamId>(id));
}

JNIEXPORT jint JNICALL Java_com_groovebox_engine_NativeEngine_nativePresetCount(JNIEnv*, jobject) {
    return static_cast<jint>(groove::presetCount());
}

JNIEXPORT jstring JNICALL Java_com_groovebox_engine_NativeEngine_nativePresetName(JNIEnv* env, jobject,
                                                                                  jint index) {
    return env->NewStringUTF(validPreset(index) ? groove::preset(index).name : "");
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeLoadPreset(JNIEnv*, jobject, jint slot,
                                                                               jint index) {
    if (!synthSlot(slot) || !validPreset(index)) return;
    engine().graph().synth(slot).loadPreset(index);
}

// --- drum slot ---

JNIEXPORT jint JNICALL Java_com_groovebox_engine_NativeEngine_nativeDrumParamCount(JNIEnv*, jobject) {
    return static_cast<jint>(groove::kDrumParamCount);
}

JNIEXPORT jstring JNICALL Java_com_groovebox_engine_NativeEngine_nativeDrumParamKey(JNIEnv* env, jobject, jint id) {
    const groove::ParamInfo* p = drumParamInfo(id);
    return env->NewStringUTF(p ? p->key : "");
}

JNIEXPORT jstring JNICALL Java_com_groovebox_engine_NativeEngine_nativeDrumParamLabel(JNIEnv* env, jobject,
                                                                                      jint id) {
    const groove::ParamInfo* p = drumParamInfo(id);
    return env->NewStringUTF(p ? p->label : "");
}

JNIEXPORT jstring JNICALL Java_com_groovebox_engine_NativeEngine_nativeDrumParamUnit(JNIEnv* env, jobject, jint id) {
    const groove::ParamInfo* p = drumParamInfo(id);
    return env->NewStringUTF(p ? p->unit : "");
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeDrumParamInfo(JNIEnv* env, jobject, jint id,
                                                                                  jdoubleArray out) {
    writeInfo(env, drumParamInfo(id), out);
}

JNIEXPORT jstring JNICALL Java_com_groovebox_engine_NativeEngine_nativeDrumSoundName(JNIEnv* env, jobject,
                                                                                     jint key) {
    const char* name = validDrumKey(key) ? groove::DrumMachine::soundName(key) : "";
    return env->NewStringUTF(name ? name : "");
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetDrumParam(JNIEnv*, jobject, jint key, jint id,
                                                                                 jfloat value) {
    if (!validDrumKey(key) || !validDrumParam(id)) return;
    const auto param = static_cast<groove::DrumParamId>(id);
    engine().graph().drums().setParam(key, param, value);
    if (engine().motionRecording() && !std::isnan(value)) {
        const int target = groove::DrumMachine::motionTarget(key, param);
        engine().submit([&] { return seq().recordMotion(groove::kDrumSlot, target, value); });
    }
}

JNIEXPORT jfloat JNICALL Java_com_groovebox_engine_NativeEngine_nativeGetDrumParam(JNIEnv*, jobject, jint key,
                                                                                   jint id) {
    if (!validDrumKey(key) || !validDrumParam(id)) return 0.0f;
    return engine().graph().drums().param(key, static_cast<groove::DrumParamId>(id));
}

// --- transport ---

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativePlay(JNIEnv*, jobject) {
    engine().submit([] { return seq().play(); });
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeStopTransport(JNIEnv*, jobject) {
    engine().submit([] { return seq().stop(); });
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetTempo(JNIEnv*, jobject, jfloat bpm) {
    engine().submit([&] { return seq().setTempo(bpm); });  // clamped, 0.1 BPM steps
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetRecordArmed(JNIEnv*, jobject,
                                                                                   jboolean armed) {
    engine().submit([&] { return seq().setRecordArmed(armed == JNI_TRUE); });
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetMotionRecording(JNIEnv*, jobject,
                                                                                       jboolean active) {
    const bool on = active == JNI_TRUE;
    engine().setMotionRecording(on);
    engine().submit([&] { return seq().setMotionRecording(on); });
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeGetTransport(JNIEnv* env, jobject,
                                                                                 jdoubleArray out) {
    if (out == nullptr || env->GetArrayLength(out) < kTransportSize) return;
    const groove::TransportState t = engine().transport();
    std::array<jdouble, kTransportSize> values{};
    values[0] = t.playing ? 1.0 : 0.0;
    values[1] = t.recordArmed ? 1.0 : 0.0;
    values[2] = static_cast<jdouble>(groove::Sequencer::tempoTenths(t.tempoBpm)) / 10.0;  // exact 0.1 steps
    for (std::size_t s = 0; s < static_cast<std::size_t>(groove::kSlotCount); ++s) {
        values[3 + s] = static_cast<jdouble>(t.currentStep[s]);
        values[3 + groove::kSlotCount + s] = static_cast<jdouble>(t.currentPattern[s]);
        values[3 + 2 * groove::kSlotCount + s] = static_cast<jdouble>(t.queuedPattern[s]);
    }
    env->SetDoubleArrayRegion(out, 0, kTransportSize, values.data());
}

// --- patterns ---

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSelectPattern(JNIEnv*, jobject, jint slot,
                                                                                  jint pattern) {
    if (!validSlot(slot) || !validPattern(pattern)) return;
    engine().submit([&] { return seq().selectPattern(slot, pattern); });
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetPatternSettings(JNIEnv*, jobject, jint slot,
                                                                                       jint pattern, jint length,
                                                                                       jint rate, jfloat shuffle,
                                                                                       jfloat gate) {
    if (!validSlot(slot) || !validPattern(pattern)) return;
    // Length, rate, shuffle and gate are clamped exactly like the UI's mirror.
    engine().submit([&] { return seq().setPatternSettings(slot, pattern, length, rate, shuffle, gate); });
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetStep(JNIEnv* env, jobject, jint slot,
                                                                            jint pattern, jint step, jboolean on,
                                                                            jintArray notes, jfloat velocity,
                                                                            jfloat gate, jfloat probability) {
    if (!validSlot(slot) || !validPattern(pattern) || !validStep(step)) return;
    std::array<jint, groove::kMaxNotesPerStep> buffer{};
    jsize count = 0;
    if (notes != nullptr) {
        count = std::min<jsize>(env->GetArrayLength(notes), groove::kMaxNotesPerStep);  // extra notes ignored
        if (count > 0) env->GetIntArrayRegion(notes, 0, count, buffer.data());
    }
    std::array<int, groove::kMaxNotesPerStep> values{};
    for (jsize i = 0; i < count; ++i) values[static_cast<std::size_t>(i)] = buffer[static_cast<std::size_t>(i)];
    engine().submit([&] {
        return seq().setStep(slot, pattern, step, on == JNI_TRUE, values.data(), static_cast<int>(count), velocity,
                             gate, probability);
    });
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetMotionValue(JNIEnv*, jobject, jint slot,
                                                                                   jint pattern, jint target,
                                                                                   jint step, jfloat value) {
    if (!validSlot(slot) || !validPattern(pattern) || !validStep(step) ||
        !groove::Sequencer::validTarget(slot, target)) {
        return;
    }
    engine().submit([&] { return seq().setMotionValue(slot, pattern, target, step, value); });
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeClearMotionLane(JNIEnv*, jobject, jint slot,
                                                                                    jint pattern, jint target) {
    if (!validSlot(slot) || !validPattern(pattern) || !groove::Sequencer::validTarget(slot, target)) return;
    engine().submit([&] { return seq().clearMotionLane(slot, pattern, target); });
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeClearPattern(JNIEnv*, jobject, jint slot,
                                                                                 jint pattern) {
    if (!validSlot(slot) || !validPattern(pattern)) return;
    engine().submit([&] { return seq().clearPattern(slot, pattern); });
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetStepLoop(JNIEnv*, jobject, jint slot,
                                                                                jint start, jint end) {
    if (!validSlot(slot)) return;
    const bool clear = start < 0 || end < 0;
    if (!clear && (!validStep(start) || !validStep(end))) return;
    engine().submit([&] { return seq().setStepLoop(slot, clear ? -1 : start, clear ? -1 : end); });
}

JNIEXPORT jint JNICALL Java_com_groovebox_engine_NativeEngine_nativePollRecorded(JNIEnv* env, jobject,
                                                                                jdoubleArray out) {
    if (out == nullptr) return 0;
    const jsize capacity = std::min<jsize>(env->GetArrayLength(out) / kRecordSize, kMaxRecordsPerPoll);
    std::array<jdouble, static_cast<std::size_t>(kRecordSize) * kMaxRecordsPerPoll> buffer{};
    jsize count = 0;
    groove::RecordedEvent e;
    while (count < capacity && seq().popRecorded(e)) {
        jdouble* r = buffer.data() + static_cast<std::size_t>(count) * kRecordSize;
        r[0] = e.kind == groove::RecordedEvent::Kind::Note ? 0.0 : 1.0;
        r[1] = static_cast<jdouble>(e.slot);
        r[2] = static_cast<jdouble>(e.pattern);
        r[3] = static_cast<jdouble>(e.step);
        r[4] = static_cast<jdouble>(e.noteOrTarget);
        r[5] = static_cast<jdouble>(e.value);
        ++count;
    }
    if (count > 0) env->SetDoubleArrayRegion(out, 0, count * kRecordSize, buffer.data());
    return static_cast<jint>(count);
}

// --- diagnostics ---

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetBufferBursts(JNIEnv*, jobject,
                                                                                    jint bursts) {
    engine().setBufferBursts(bursts);
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeGetStats(JNIEnv* env, jobject,
                                                                             jdoubleArray out) {
    if (out == nullptr || env->GetArrayLength(out) < kStatsSize) return;
    const groove::EngineStats s = engine().stats();
    const std::array<jdouble, kStatsSize> values = {
        s.running ? 1.0 : 0.0,
        static_cast<jdouble>(s.audioApi),
        static_cast<jdouble>(s.sharingMode),
        static_cast<jdouble>(s.performanceMode),
        s.mmap ? 1.0 : 0.0,
        static_cast<jdouble>(s.sampleRate),
        static_cast<jdouble>(s.channelCount),
        static_cast<jdouble>(s.framesPerBurst),
        static_cast<jdouble>(s.bufferSizeFrames),
        static_cast<jdouble>(s.bufferCapacityFrames),
        static_cast<jdouble>(s.xRunCount),
        s.outputLatencyMs,
        static_cast<jdouble>(s.deviceId),
        s.callbackLoadAvgPct,
        s.callbackLoadMaxPct,
    };
    env->SetDoubleArrayRegion(out, 0, kStatsSize, values.data());
}

JNIEXPORT jint JNICALL Java_com_groovebox_engine_NativeEngine_nativePollTapToTone(JNIEnv* env, jobject,
                                                                                  jlongArray out) {
    if (out == nullptr) return 0;
    std::array<int64_t, 64> buffer{};
    const jsize capacity = std::min<jsize>(env->GetArrayLength(out), static_cast<jsize>(buffer.size()));
    const int32_t count = engine().pollTapToToneNs(buffer.data(), capacity);
    if (count > 0) {
        static_assert(sizeof(jlong) == sizeof(int64_t));
        env->SetLongArrayRegion(out, 0, count, reinterpret_cast<const jlong*>(buffer.data()));
    }
    return count;
}

}  // extern "C"
