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

/** Sequencer transport readout (polled by the UI, which never drives timing: F3.10). */
data class TransportState(
    val playing: Boolean,
    val recordArmed: Boolean,
    val tempoBpm: Double,
    /** Per slot: index of the step last triggered in its current pattern, or -1 when stopped. */
    val currentStep: IntArray,
    /** Per slot: the pattern playing (or selected, when stopped), 0..15. */
    val currentPattern: IntArray,
    /** Per slot: the pattern queued to start at the end of the current one, or -1. */
    val queuedPattern: IntArray,
) {
    internal companion object {
        // Layout shared with engine/android/JniBridge.cpp: [playing, armed, tempo, step x8, pattern x8, queued x8]
        const val SIZE = 3 + 3 * NativeEngine.SLOT_COUNT

        fun from(v: DoubleArray): TransportState {
            val n = NativeEngine.SLOT_COUNT
            return TransportState(
                playing = v[0] != 0.0,
                recordArmed = v[1] != 0.0,
                tempoBpm = v[2],
                currentStep = IntArray(n) { v[3 + it].toInt() },
                currentPattern = IntArray(n) { v[3 + n + it].toInt() },
                queuedPattern = IntArray(n) { v[3 + 2 * n + it].toInt() },
            )
        }
    }
}

/**
 * Something the audio thread wrote into a pattern while recording (F3.6, F3.7). The UI keeps
 * a mirror of every pattern and applies these to it with exactly the engine's rules:
 *  - Note: the step turns on, [noteOrTarget] is added to its notes if not already there
 *    (when it already holds 4 notes, the last one is replaced) and its velocity becomes [value].
 *  - Motion: lane [noteOrTarget]'s value at the step becomes [value].
 */
data class RecordedEvent(
    val kind: Kind,
    val slot: Int,
    val pattern: Int,
    val step: Int,
    val noteOrTarget: Int,
    val value: Float,
) {
    enum class Kind { Note, Motion }
}

/** Tape readout (F5), polled by the UI. */
data class TapeState(
    /** Tape length in frames (0 = empty tape), and in bars at the tempo of the first take. */
    val lengthFrames: Long,
    val lengthBars: Int,
    /** Frames per bar of the tape (fixed by the first take; 0 when empty). */
    val barFrames: Long,
    /** Current tape head, in frames from the tape start. */
    val headFrame: Long,
    /** Track being recorded, or -1. */
    val recordingTrack: Int,
    /** Track armed and waiting for the next bar line (or Play), or -1. */
    val armedTrack: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val trackHasAudio: BooleanArray,
    /** A saved tape is still loading in the background (F7.6). */
    val loading: Boolean,
    /**
     * The tape's tempo (its first take's), in BPM to 0.1; 0 when empty. At another tempo the
     * tape plays varispeed (build-20).
     */
    val tempoBpm: Float = 0f,
    /** The sequencer tempo is the tape's (always true when empty): recording needs it. */
    val tempoMatches: Boolean = true,
) {
    internal companion object {
        // Layout shared with engine/android/JniBridge.cpp:
        // [lengthFrames, lengthBars, barFrames, headFrame, recordingTrack, armedTrack, canUndo, canRedo, hasAudio x4, loading,
        //  tempoBpm, tempoMatches]
        const val SIZE = 11 + NativeEngine.TAPE_TRACK_COUNT

        fun from(v: DoubleArray) = TapeState(
            lengthFrames = v[0].toLong(),
            lengthBars = v[1].toInt(),
            barFrames = v[2].toLong(),
            headFrame = v[3].toLong(),
            recordingTrack = v[4].toInt(),
            armedTrack = v[5].toInt(),
            canUndo = v[6] != 0.0,
            canRedo = v[7] != 0.0,
            trackHasAudio = BooleanArray(NativeEngine.TAPE_TRACK_COUNT) { v[8 + it] != 0.0 },
            loading = v[8 + NativeEngine.TAPE_TRACK_COUNT] != 0.0,
            tempoBpm = v[9 + NativeEngine.TAPE_TRACK_COUNT].toFloat(),
            tempoMatches = v[10 + NativeEngine.TAPE_TRACK_COUNT] != 0.0,
        )
    }
}

/**
 * Kotlin face of the C++ engine. Call only from the main thread: every UI-to-audio queue
 * allows exactly one producer.
 *
 * Slots (docs/feature-spec.md F3): slot 0 is the drum kit (DrumMachine), slots 1..7 are
 * Brook synths. Synth-only calls ignore slot 0; drum calls have no slot.
 *
 * Patterns: the engine's copy of every pattern lives on the audio thread and is changed only
 * through the set/clear calls below (each is queued and applied at the next audio block),
 * plus live recording, which is reported back through [pollRecorded]. The UI keeps the
 * authoritative mirror and never reads pattern data back from the engine.
 */
object NativeEngine {
    const val SLOT_COUNT = 8
    const val DRUM_SLOT = 0
    const val PATTERN_COUNT = 16
    const val MAX_STEPS = 64
    /** Hits (sounds) per step; a step is on when it has one. The UI caps a synth chord at 4. */
    const val MAX_HITS_PER_STEP = 8
    /** Floats per hit in [setStep]'s array: note, velocity, length, probability, nudge. */
    const val HIT_FIELDS = 5
    const val MAX_MOTION_LANES = 8

    /** Pattern step rates, in the order of the rate argument. */
    val STEP_RATES = listOf("1/8", "1/16", "1/32", "1/16T")

    init {
        System.loadLibrary("groovebox")
    }

    private val statsBuffer = DoubleArray(EngineStats.SIZE)
    private val transportBuffer = DoubleArray(TransportState.SIZE)
    private val latencyBuffer = LongArray(64)
    private val recordedBuffer = DoubleArray(6 * 64)
    private val tapeBuffer = DoubleArray(TapeState.SIZE)

    fun start(): Boolean = nativeStart()
    fun stop() = nativeStop()

    // --- notes (from the keys) ---

    /** velocity 0..1. tapTimeNs is the CLOCK_MONOTONIC time of the touch, or 0 to skip measuring.
     *  While recording is armed and the transport is playing, the engine also records the note
     *  into the slot's current pattern at the nearest step (reported via [pollRecorded]). */
    fun noteOn(slot: Int, note: Int, velocity: Float, tapTimeNs: Long): Boolean =
        nativeNoteOn(slot, note, velocity, tapTimeNs)
    fun noteOff(slot: Int, note: Int): Boolean = nativeNoteOff(slot, note)
    /** Every slot. */
    fun allNotesOff() = nativeAllNotesOff()

    // --- synth slots 1..7 ---

    /** Synth parameter table (SynthParams.def), in ID order. Same for every synth slot. */
    val params: List<ParamSpec> by lazy {
        readTable(nativeParamCount(), ::nativeParamKey, ::nativeParamLabel, ::nativeParamUnit, ::nativeParamInfo)
    }

    /** value in the parameter's real units; the engine clamps it to [min, max]. */
    fun setParam(slot: Int, id: Int, value: Double) = nativeSetParam(slot, id, value.toFloat())
    fun getParam(slot: Int, id: Int): Double = nativeGetParam(slot, id).toDouble()

    val presetNames: List<String> by lazy { List(nativePresetCount()) { nativePresetName(it) } }

    /** Sets every parameter of the slot from the preset; read them back with getParam(). */
    fun loadPreset(slot: Int, index: Int) = nativeLoadPreset(slot, index)

    // --- drum slot ---

    /** Per-key drum parameter table (DrumParams.def), in ID order. */
    val drumParams: List<ParamSpec> by lazy {
        readTable(
            nativeDrumParamCount(), ::nativeDrumParamKey, ::nativeDrumParamLabel, ::nativeDrumParamUnit,
            ::nativeDrumParamInfo,
        )
    }

    /** The 16 kit sounds, key 0 = MIDI note 48 upward. */
    val drumSoundNames: List<String> by lazy { List(DRUM_KEY_COUNT) { nativeDrumSoundName(it) } }
    const val DRUM_KEY_COUNT = 16
    const val DRUM_LOWEST_NOTE = 48

    fun setDrumParam(key: Int, id: Int, value: Double) = nativeSetDrumParam(key, id, value.toFloat())
    fun getDrumParam(key: Int, id: Int): Double = nativeGetDrumParam(key, id).toDouble()

    /** Motion target numbers: a synth target is its param id; a drum target is key * drumParams.size + id. */
    fun drumMotionTarget(key: Int, id: Int): Int = key * drumParams.size + id

    // --- transport ---

    fun play() = nativePlay()
    /** Stops and rewinds every slot to step 0; sounding sequencer notes are released. */
    fun stopTransport() = nativeStopTransport()
    /** 40.0 .. 240.0 BPM (clamped). */
    fun setTempo(bpm: Double) = nativeSetTempo(bpm.toFloat())
    /** Note recording from the keys while playing (F3.6). */
    fun setRecordArmed(armed: Boolean) = nativeSetRecordArmed(armed)
    /** While true and playing, every setParam/setDrumParam call is also written as motion into
     *  the slot's current pattern at the current step (F3.7), and reported via [pollRecorded]. */
    fun setMotionRecording(active: Boolean) = nativeSetMotionRecording(active)

    fun transport(): TransportState {
        nativeGetTransport(transportBuffer)
        return TransportState.from(transportBuffer)
    }

    // --- patterns ---

    /** Takes effect at the end of the slot's current pattern while playing (F3.9), at once when stopped. */
    fun selectPattern(slot: Int, pattern: Int) = nativeSelectPattern(slot, pattern)

    /** length 1..64; rate index into [STEP_RATES]; shuffle 0..1 (F3.3); gate 0.05..1 = default
     *  note length as a fraction of a step, used by steps whose own gate is negative. */
    fun setPatternSettings(slot: Int, pattern: Int, length: Int, rate: Int, shuffle: Float, gate: Float) =
        nativeSetPatternSettings(slot, pattern, length, rate, shuffle, gate)

    /** Replaces the step's hits. hits holds hitCount * [HIT_FIELDS] floats, hit by hit:
     *  note (MIDI 0..127, as a float), velocity 0.01..1, length (-1 = the pattern's gate, else
     *  1/16..16 steps), probability 0..1 (F3.4), nudge -0.5..0.5 of a step (early..late).
     *  hitCount 0..[MAX_HITS_PER_STEP] (more are ignored); 0 clears the step. The engine clamps
     *  every field. False (nothing sent) if hits holds fewer than hitCount * HIT_FIELDS floats
     *  or the command queue stayed full. */
    fun setStep(slot: Int, pattern: Int, step: Int, hits: FloatArray, hitCount: Int): Boolean =
        nativeSetStep(slot, pattern, step, hits, hitCount)

    /** One motion value at one step; NaN clears it. A pattern holds at most 8 lanes (targets):
     *  a value for a 9th target is ignored. */
    fun setMotionValue(slot: Int, pattern: Int, target: Int, step: Int, value: Float) =
        nativeSetMotionValue(slot, pattern, target, step, value)
    fun clearMotionLane(slot: Int, pattern: Int, target: Int) = nativeClearMotionLane(slot, pattern, target)

    /** Every step off, motion cleared, settings back to defaults (16 steps, 1/16, no shuffle, gate 0.5). */
    fun clearPattern(slot: Int, pattern: Int) = nativeClearPattern(slot, pattern)

    /** Loops steps start..end (inclusive) of the slot's current pattern while held (F3.8);
     *  [clearStepLoop] returns to where the pattern would have been. */
    fun setStepLoop(slot: Int, start: Int, end: Int) = nativeSetStepLoop(slot, start, end)
    fun clearStepLoop(slot: Int) = nativeSetStepLoop(slot, -1, -1)

    /** Pattern writes made by recording since the last call. */
    fun pollRecorded(): List<RecordedEvent> {
        val count = nativePollRecorded(recordedBuffer)
        return List(count) { i ->
            val o = i * 6
            RecordedEvent(
                kind = if (recordedBuffer[o].toInt() == 0) RecordedEvent.Kind.Note else RecordedEvent.Kind.Motion,
                slot = recordedBuffer[o + 1].toInt(),
                pattern = recordedBuffer[o + 2].toInt(),
                step = recordedBuffer[o + 3].toInt(),
                noteOrTarget = recordedBuffer[o + 4].toInt(),
                value = recordedBuffer[o + 5].toFloat(),
            )
        }
    }

    // --- mixer, FX and tape settings (M3) ---

    /** Global mixer/FX/tape parameter table (MixParams.def), in ID order. */
    val mixParams: List<ParamSpec> by lazy {
        readTable(nativeMixParamCount(), ::nativeMixParamKey, ::nativeMixParamLabel, ::nativeMixParamUnit, ::nativeMixParamInfo)
    }
    fun setMixParam(id: Int, value: Double) = nativeSetMixParam(id, value.toFloat())
    fun getMixParam(id: Int): Double = nativeGetMixParam(id).toDouble()

    /** Mixer channels: 0..7 = sound slots, 8..11 = tape tracks 1..4. */
    const val MIXER_CHANNEL_COUNT = 12
    const val FIRST_TAPE_CHANNEL = 8

    /** Per-channel parameter table (ChannelParams.def), in ID order; sends are ignored on tape channels. */
    val channelParams: List<ParamSpec> by lazy {
        readTable(
            nativeChannelParamCount(), ::nativeChannelParamKey, ::nativeChannelParamLabel, ::nativeChannelParamUnit,
            ::nativeChannelParamInfo,
        )
    }
    fun setChannelParam(channel: Int, id: Int, value: Double) = nativeSetChannelParam(channel, id, value.toFloat())
    fun getChannelParam(channel: Int, id: Int): Double = nativeGetChannelParam(channel, id).toDouble()

    // --- tape (F5) ---

    const val TAPE_TRACK_COUNT = 4
    const val TAPE_OVERVIEW_BINS = 256

    /** Arms recording onto [track]. It starts at the next bar line while playing (or at Play),
     *  and keeps looping over the loop region until [tapeStopRecord]. Ignored unless the tape
     *  speed is 1x and reverse is off. The first take on an empty tape sets the tape length. */
    fun tapeArmRecord(track: Int) = nativeTapeArmRecord(track)
    /** Stops recording at the next bar line (or cancels an arm that has not started). */
    fun tapeStopRecord() = nativeTapeStopRecord()
    /** Commit loop (F5.6): records exactly one pass of a pattern onto [track] while playing.
     *  sourceSlot 0..7: that slot's channel only (post-fader, dry), for exactly its current
     *  pattern's length, starting at that slot's next pattern boundary. sourceSlot -1: the full
     *  mix with FX, for the longest current pattern among the slots (rounded up to whole bars),
     *  starting at that slot's next pattern boundary. On an empty tape the take sets the tape
     *  length; otherwise it writes over the loop region from the head, like a record pass. */
    fun tapeCommit(track: Int, sourceSlot: Int) = nativeTapeCommit(track, sourceSlot)
    fun tapeClearTrack(track: Int) = nativeTapeClearTrack(track)
    fun tapeUndo() = nativeTapeUndo()
    fun tapeRedo() = nativeTapeRedo()

    fun tapeState(): TapeState {
        nativeGetTapeState(tapeBuffer)
        return TapeState.from(tapeBuffer)
    }

    /** Peak overview of one track, 0..255 per bin across the whole tape length (all zeros when empty). */
    fun tapeOverview(track: Int, out: ByteArray) = nativeTapeOverview(track, out)

    // --- persistence and export (M4) ---

    /** Directory for the tape's audio files. Call once, before the first start(). From then on the
     *  engine streams every tape change to disk on its own writer thread (F7.5); the audio thread
     *  never touches files. */
    fun setTapeStorageDir(path: String) = nativeSetTapeStorageDir(path)

    /** Starts loading the tape saved in the storage dir on a background thread (F7.6). Sound and
     *  the rest of the app keep working; tapeState().loading stays true until the audio has landed.
     *  Returns false (and loads nothing) if there is no valid saved tape. */
    fun loadSavedTape(): Boolean = nativeLoadSavedTape()

    /** Blocks until every tape change so far is on disk, for at most [timeoutMs]. Call on pause.
     *  Returns false on timeout or I/O error. */
    fun flushTape(timeoutMs: Int = 1000): Boolean = nativeFlushTape(timeoutMs)

    /** Starts rendering the tape mix to a 24-bit stereo WAV at the stream rate, at [path], on a
     *  background thread, faster than real time (F7.2): the four tracks at 1x forward through their
     *  channel strips (volume, pan, mute), master volume and limiter; over the loop region, or the
     *  whole tape when [wholeTape]. Playback carries on meanwhile. Returns false if the tape is
     *  empty or an export is already running. */
    fun startExport(path: String, wholeTape: Boolean): Boolean = nativeStartExport(path, wholeTape)

    /** 0..1 while exporting, exactly 1 once the file is complete and closed, -1 if it failed,
     *  NaN when no export has run. */
    fun exportProgress(): Float = nativeExportProgress()

    // --- level meters (v1 close-out) ---

    /** Meters, in this order: mixer channels 0..11 (= their channel numbers), then the delay
     *  return, the reverb return and the master. */
    const val METER_COUNT = 15
    const val METER_DELAY = 12
    const val METER_REVERB = 13
    const val METER_MASTER = 14
    /** Index of the first clip flag in the [meters] buffer. */
    const val METER_CLIP_OFFSET = 16
    /** Size of the buffer [meters] fills (layout shared with engine/android/JniBridge.cpp). */
    const val METER_BUFFER_SIZE = 32

    /**
     * Live peak levels, read lock-free from the audio thread (cheap: poll it every frame).
     * Fills [out] (at least [METER_BUFFER_SIZE] long, else it is left untouched):
     *  - out[0 until METER_COUNT]: each meter's level as a linear peak amplitude (1.0 = 0 dBFS;
     *    capped at 16), with an instant attack and a 300 ms exponential release. Channels read
     *    their strip's output (post-fader, post-mute, post-pan, the louder of L and R), the FX
     *    returns read after their return level, the master after the limiter.
     *  - out[METER_COUNT]: reserved (0).
     *  - out[METER_CLIP_OFFSET + i]: 1 while meter i clipped in the last ~1 s, else 0. A channel
     *    or return clips at full scale (|sample| >= 1); the master when its mix reached full
     *    scale before the limiter.
     *  - out[METER_BUFFER_SIZE - 1]: reserved (0).
     * Every meter reads 0 while the stream is stopped.
     */
    fun meters(out: FloatArray) = nativeMeters(out)

    // --- diagnostics ---

    fun setBufferBursts(bursts: Int) = nativeSetBufferBursts(bursts)

    fun stats(): EngineStats {
        nativeGetStats(statsBuffer)
        return EngineStats.from(statsBuffer)
    }

    /** Tap-to-tone latencies (ns) measured since the last call, from every slot. */
    fun pollTapToToneNs(): LongArray {
        val count = nativePollTapToTone(latencyBuffer)
        return latencyBuffer.copyOf(count)
    }

    private fun readTable(
        count: Int,
        key: (Int) -> String,
        label: (Int) -> String,
        unit: (Int) -> String,
        info: (Int, DoubleArray) -> Unit,
    ): List<ParamSpec> {
        val buffer = DoubleArray(4)
        return List(count) { id ->
            info(id, buffer)
            ParamSpec(
                id = id,
                key = key(id),
                label = label(id),
                min = buffer[0],
                max = buffer[1],
                default = buffer[2],
                curve = Curve.entries[buffer[3].toInt()],
                unit = unit(id),
            )
        }
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeNoteOn(slot: Int, note: Int, velocity: Float, tapTimeNs: Long): Boolean
    private external fun nativeNoteOff(slot: Int, note: Int): Boolean
    private external fun nativeAllNotesOff()
    private external fun nativeParamCount(): Int
    private external fun nativeParamKey(id: Int): String
    private external fun nativeParamLabel(id: Int): String
    private external fun nativeParamUnit(id: Int): String
    /** out = [min, max, default, curve ordinal] */
    private external fun nativeParamInfo(id: Int, out: DoubleArray)
    private external fun nativeSetParam(slot: Int, id: Int, value: Float)
    private external fun nativeGetParam(slot: Int, id: Int): Float
    private external fun nativePresetCount(): Int
    private external fun nativePresetName(index: Int): String
    private external fun nativeLoadPreset(slot: Int, index: Int)
    private external fun nativeDrumParamCount(): Int
    private external fun nativeDrumParamKey(id: Int): String
    private external fun nativeDrumParamLabel(id: Int): String
    private external fun nativeDrumParamUnit(id: Int): String
    /** out = [min, max, default, curve ordinal] */
    private external fun nativeDrumParamInfo(id: Int, out: DoubleArray)
    private external fun nativeDrumSoundName(key: Int): String
    private external fun nativeSetDrumParam(key: Int, id: Int, value: Float)
    private external fun nativeGetDrumParam(key: Int, id: Int): Float
    private external fun nativePlay()
    private external fun nativeStopTransport()
    private external fun nativeSetTempo(bpm: Float)
    private external fun nativeSetRecordArmed(armed: Boolean)
    private external fun nativeSetMotionRecording(active: Boolean)
    /** out = TransportState layout */
    private external fun nativeGetTransport(out: DoubleArray)
    private external fun nativeSelectPattern(slot: Int, pattern: Int)
    private external fun nativeSetPatternSettings(slot: Int, pattern: Int, length: Int, rate: Int, shuffle: Float, gate: Float)
    private external fun nativeSetStep(slot: Int, pattern: Int, step: Int, hits: FloatArray, hitCount: Int): Boolean
    private external fun nativeSetMotionValue(slot: Int, pattern: Int, target: Int, step: Int, value: Float)
    private external fun nativeClearMotionLane(slot: Int, pattern: Int, target: Int)
    private external fun nativeClearPattern(slot: Int, pattern: Int)
    private external fun nativeSetStepLoop(slot: Int, start: Int, end: Int)
    /** out = up to out.size / 6 records of [kind (0 note, 1 motion), slot, pattern, step, note or target, value]; returns the count */
    private external fun nativePollRecorded(out: DoubleArray): Int
    private external fun nativeSetBufferBursts(bursts: Int)
    private external fun nativeGetStats(out: DoubleArray)
    private external fun nativePollTapToTone(out: LongArray): Int
    private external fun nativeMixParamCount(): Int
    private external fun nativeMixParamKey(id: Int): String
    private external fun nativeMixParamLabel(id: Int): String
    private external fun nativeMixParamUnit(id: Int): String
    private external fun nativeMixParamInfo(id: Int, out: DoubleArray)
    private external fun nativeSetMixParam(id: Int, value: Float)
    private external fun nativeGetMixParam(id: Int): Float
    private external fun nativeChannelParamCount(): Int
    private external fun nativeChannelParamKey(id: Int): String
    private external fun nativeChannelParamLabel(id: Int): String
    private external fun nativeChannelParamUnit(id: Int): String
    private external fun nativeChannelParamInfo(id: Int, out: DoubleArray)
    private external fun nativeSetChannelParam(channel: Int, id: Int, value: Float)
    private external fun nativeGetChannelParam(channel: Int, id: Int): Float
    private external fun nativeTapeArmRecord(track: Int)
    private external fun nativeTapeStopRecord()
    private external fun nativeTapeCommit(track: Int, sourceSlot: Int)
    private external fun nativeTapeClearTrack(track: Int)
    private external fun nativeTapeUndo()
    private external fun nativeTapeRedo()
    /** out = TapeState layout */
    private external fun nativeGetTapeState(out: DoubleArray)
    private external fun nativeTapeOverview(track: Int, out: ByteArray)
    private external fun nativeSetTapeStorageDir(path: String)
    private external fun nativeLoadSavedTape(): Boolean
    private external fun nativeFlushTape(timeoutMs: Int): Boolean
    private external fun nativeStartExport(path: String, wholeTape: Boolean): Boolean
    private external fun nativeExportProgress(): Float
    /** out = [level x15, reserved, clip x15, reserved]; see [meters] */
    private external fun nativeMeters(out: FloatArray)
}
