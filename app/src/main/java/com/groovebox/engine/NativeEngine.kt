package com.groovebox.engine

/** Snapshot of the output stream's configuration and audio-callback load. */
data class EngineStats(
    val running: Boolean,
    val audioApi: String,
    val sharingMode: String,
    val performanceMode: String,
    val mmap: Boolean,
    val sampleRate: Int,
    val channelCount: Int,
    val framesPerBurst: Int,
    val bufferSizeFrames: Int,
    val bufferCapacityFrames: Int,
    val xRunCount: Int,
    val outputLatencyMs: Double,
    val deviceId: Int,
    /** Mean render time as a percentage of the buffer's duration (F0.4). */
    val callbackLoadAvgPct: Double,
    /** Worst render time over the last ~2 s, as a percentage of the buffer's duration. */
    val callbackLoadMaxPct: Double,
) {
    val bufferMs: Double
        get() = if (sampleRate > 0) bufferSizeFrames * 1000.0 / sampleRate else 0.0

    internal companion object {
        // Index layout shared with engine/android/JniBridge.cpp.
        const val SIZE = 15

        fun from(v: DoubleArray) = EngineStats(
            running = v[0] != 0.0,
            audioApi = when (v[1].toInt()) { 1 -> "OpenSL ES"; 2 -> "AAudio"; else -> "Unspecified" },
            sharingMode = when (v[2].toInt()) { 0 -> "Exclusive"; 1 -> "Shared"; else -> "?" },
            performanceMode = when (v[3].toInt()) {
                10 -> "None"; 11 -> "PowerSaving"; 12 -> "LowLatency"; else -> "?"
            },
            mmap = v[4] != 0.0,
            sampleRate = v[5].toInt(),
            channelCount = v[6].toInt(),
            framesPerBurst = v[7].toInt(),
            bufferSizeFrames = v[8].toInt(),
            bufferCapacityFrames = v[9].toInt(),
            xRunCount = v[10].toInt(),
            outputLatencyMs = v[11],
            deviceId = v[12].toInt(),
            callbackLoadAvgPct = v[13],
            callbackLoadMaxPct = v[14],
        )
    }
}

/**
 * Kotlin face of the C++ engine. Call only from the main thread: the engine's
 * UI-to-audio queues allow exactly one producer.
 */
object NativeEngine {
    init {
        System.loadLibrary("groovebox")
    }

    private val statsBuffer = DoubleArray(EngineStats.SIZE)
    private val latencyBuffer = LongArray(64)

    fun start(): Boolean = nativeStart()
    fun stop() = nativeStop()

    /** velocity 0..1. tapTimeNs is the CLOCK_MONOTONIC time of the touch, or 0 to skip measuring. */
    fun noteOn(note: Int, velocity: Float, tapTimeNs: Long): Boolean = nativeNoteOn(note, velocity, tapTimeNs)
    fun noteOff(note: Int): Boolean = nativeNoteOff(note)
    fun allNotesOff() = nativeAllNotesOff()

    /** Parameter table read from the engine (SynthParams.def), in ID order. */
    val params: List<ParamSpec> by lazy {
        val info = DoubleArray(4)
        List(nativeParamCount()) { id ->
            nativeParamInfo(id, info)
            ParamSpec(
                id = id,
                key = nativeParamKey(id),
                label = nativeParamLabel(id),
                min = info[0],
                max = info[1],
                default = info[2],
                curve = Curve.entries[info[3].toInt()],
                unit = nativeParamUnit(id),
            )
        }
    }

    /** value in the parameter's real units; the engine clamps it to [min, max]. */
    fun setParam(id: Int, value: Double) = nativeSetParam(id, value.toFloat())
    fun getParam(id: Int): Double = nativeGetParam(id).toDouble()

    val presetNames: List<String> by lazy { List(nativePresetCount()) { nativePresetName(it) } }

    /** Sets every parameter from the preset; read them back with getParam(). */
    fun loadPreset(index: Int) = nativeLoadPreset(index)

    fun setBufferBursts(bursts: Int) = nativeSetBufferBursts(bursts)

    fun stats(): EngineStats {
        nativeGetStats(statsBuffer)
        return EngineStats.from(statsBuffer)
    }

    /** Tap-to-tone latencies (ns) measured since the last call. */
    fun pollTapToToneNs(): LongArray {
        val count = nativePollTapToTone(latencyBuffer)
        return latencyBuffer.copyOf(count)
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeNoteOn(note: Int, velocity: Float, tapTimeNs: Long): Boolean
    private external fun nativeNoteOff(note: Int): Boolean
    private external fun nativeAllNotesOff()
    private external fun nativeParamCount(): Int
    private external fun nativeParamKey(id: Int): String
    private external fun nativeParamLabel(id: Int): String
    private external fun nativeParamUnit(id: Int): String
    /** out = [min, max, default, curve ordinal] */
    private external fun nativeParamInfo(id: Int, out: DoubleArray)
    private external fun nativeSetParam(id: Int, value: Float)
    private external fun nativeGetParam(id: Int): Float
    private external fun nativePresetCount(): Int
    private external fun nativePresetName(index: Int): String
    private external fun nativeLoadPreset(index: Int)
    private external fun nativeSetBufferBursts(bursts: Int)
    private external fun nativeGetStats(out: DoubleArray)
    private external fun nativePollTapToTone(out: LongArray): Int
}
