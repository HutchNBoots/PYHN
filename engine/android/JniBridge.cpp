// JNI entry points for com.groovebox.engine.NativeEngine (a Kotlin `object`, so each
// function receives the singleton instance as `thiz`).
// Every function here is called from the app's main thread only (see AudioEngine.h):
// the synth's note queue and parameter store allow exactly one control-thread writer.
// Ids and indices coming from Kotlin are validated here; out-of-range calls are ignored
// (string getters return "", value getters return 0).

#include <jni.h>

#include <algorithm>
#include <array>

#include "AudioEngine.h"
#include "groove/Presets.h"
#include "groove/SynthParams.h"

namespace {

groove::AudioEngine& engine() {
    // Lives for the whole process; never destroyed so no callback can outlive it.
    static auto* instance = new groove::AudioEngine();
    return *instance;
}

// Layout shared with NativeEngine.kt (EngineStats.SIZE and EngineStats.from()).
constexpr jsize kStatsSize = 15;
// Layout shared with NativeEngine.params: [min, max, default, curve ordinal].
constexpr jsize kParamInfoSize = 4;

bool validParam(jint id) { return id >= 0 && id < static_cast<jint>(groove::kParamCount); }

bool validPreset(jint index) { return index >= 0 && index < static_cast<jint>(groove::presetCount()); }

const groove::ParamInfo* paramInfo(jint id) {
    return validParam(id) ? &groove::paramInfo(static_cast<groove::ParamId>(id)) : nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_groovebox_engine_NativeEngine_nativeStart(JNIEnv*, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeStop(JNIEnv*, jobject) { engine().stop(); }

JNIEXPORT jboolean JNICALL Java_com_groovebox_engine_NativeEngine_nativeNoteOn(JNIEnv*, jobject, jint note,
                                                                               jfloat velocity, jlong tapTimeNs) {
    // The engine rejects notes outside 0..127 and clamps velocity to 0..1.
    return engine().synth().noteOn(note, velocity, tapTimeNs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_groovebox_engine_NativeEngine_nativeNoteOff(JNIEnv*, jobject, jint note) {
    return engine().synth().noteOff(note) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeAllNotesOff(JNIEnv*, jobject) {
    engine().synth().allNotesOff();
}

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
    const groove::ParamInfo* p = paramInfo(id);
    if (!p || out == nullptr || env->GetArrayLength(out) < kParamInfoSize) return;
    const std::array<jdouble, kParamInfoSize> values = {
        static_cast<jdouble>(p->min),
        static_cast<jdouble>(p->max),
        static_cast<jdouble>(p->def),
        static_cast<jdouble>(static_cast<int32_t>(p->curve)),
    };
    env->SetDoubleArrayRegion(out, 0, kParamInfoSize, values.data());
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetParam(JNIEnv*, jobject, jint id,
                                                                             jfloat value) {
    if (!validParam(id)) return;
    // ParamStore clamps to the range, rounds Int/Enum values and rejects NaN.
    engine().synth().setParam(static_cast<groove::ParamId>(id), value);
}

JNIEXPORT jfloat JNICALL Java_com_groovebox_engine_NativeEngine_nativeGetParam(JNIEnv*, jobject, jint id) {
    if (!validParam(id)) return 0.0f;
    return engine().synth().param(static_cast<groove::ParamId>(id));
}

JNIEXPORT jint JNICALL Java_com_groovebox_engine_NativeEngine_nativePresetCount(JNIEnv*, jobject) {
    return static_cast<jint>(groove::presetCount());
}

JNIEXPORT jstring JNICALL Java_com_groovebox_engine_NativeEngine_nativePresetName(JNIEnv* env, jobject,
                                                                                  jint index) {
    return env->NewStringUTF(validPreset(index) ? groove::preset(index).name : "");
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeLoadPreset(JNIEnv*, jobject, jint index) {
    if (!validPreset(index)) return;
    engine().synth().loadPreset(index);
}

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
