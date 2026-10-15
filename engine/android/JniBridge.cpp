// JNI entry points for com.groovebox.engine.NativeEngine.
// Every function here is called from the app's main thread only (see AudioEngine.h).

#include <jni.h>

#include <algorithm>
#include <array>

#include "AudioEngine.h"

namespace {

groove::AudioEngine& engine() {
    // Lives for the whole process; never destroyed so no callback can outlive it.
    static auto* instance = new groove::AudioEngine();
    return *instance;
}

// Layout shared with NativeEngine.kt (STATS_* indices).
constexpr jsize kStatsSize = 13;

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_groovebox_engine_NativeEngine_nativeStart(JNIEnv*, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeStop(JNIEnv*, jobject) {
    engine().stop();
}

JNIEXPORT jboolean JNICALL Java_com_groovebox_engine_NativeEngine_nativeNoteOn(JNIEnv*, jobject,
                                                                               jint note,
                                                                               jlong tapTimeNs) {
    return engine().synth().noteOn(note, tapTimeNs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_groovebox_engine_NativeEngine_nativeNoteOff(JNIEnv*, jobject,
                                                                                jint note) {
    return engine().synth().noteOff(note) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeAllNotesOff(JNIEnv*, jobject) {
    engine().synth().allNotesOff();
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetWaveform(JNIEnv*, jobject,
                                                                                jint waveform) {
    if (waveform < 0 || waveform > static_cast<jint>(groove::Waveform::Square)) return;
    engine().synth().setWaveform(static_cast<groove::Waveform>(waveform));
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeSetBufferBursts(JNIEnv*, jobject,
                                                                                    jint bursts) {
    engine().setBufferBursts(bursts);
}

JNIEXPORT void JNICALL Java_com_groovebox_engine_NativeEngine_nativeGetStats(JNIEnv* env, jobject,
                                                                             jdoubleArray out) {
    if (env->GetArrayLength(out) < kStatsSize) return;
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
    };
    env->SetDoubleArrayRegion(out, 0, kStatsSize, values.data());
}

JNIEXPORT jint JNICALL Java_com_groovebox_engine_NativeEngine_nativePollTapToTone(JNIEnv* env,
                                                                                  jobject,
                                                                                  jlongArray out) {
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
