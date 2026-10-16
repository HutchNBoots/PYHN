package com.groovebox.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.groovebox.engine.NativeEngine
import com.groovebox.engine.ParamSpec
import com.groovebox.engine.RecordedEvent
import com.groovebox.engine.TapeState
import com.groovebox.engine.TransportState
import com.groovebox.ui.model.DemoContent
import com.groovebox.ui.model.DrumLayout
import com.groovebox.ui.model.MixKeys
import com.groovebox.ui.model.MixLimits
import com.groovebox.ui.model.MixerPad
import com.groovebox.ui.model.MotionLane
import com.groovebox.ui.model.NoteMemory
import com.groovebox.ui.model.PadAction
import com.groovebox.ui.model.PatternSettings
import com.groovebox.ui.model.PatternSink
import com.groovebox.ui.model.PatternStore
import com.groovebox.ui.model.RecordButton
import com.groovebox.ui.model.SeqLimits
import com.groovebox.ui.model.Step
import com.groovebox.ui.model.StepPads
import com.groovebox.ui.model.TapeOverview
import com.groovebox.ui.model.TapeRecordAction
import com.groovebox.ui.model.TapeRecordStatus
import com.groovebox.ui.model.TapeView
import kotlin.math.roundToInt

/** The five screens of the top bar's tabs. */
enum class Screen { Synth, Drum, Seq, Tape, Mixer }

/** Sends the pattern store's edits to the engine. */
private object NativePatternSink : PatternSink {
    override fun setPatternSettings(slot: Int, pattern: Int, length: Int, rate: Int, shuffle: Float, gate: Float) =
        NativeEngine.setPatternSettings(slot, pattern, length, rate, shuffle, gate)

    override fun setStep(
        slot: Int, pattern: Int, step: Int, on: Boolean, notes: IntArray, velocity: Float, gate: Float, probability: Float,
    ) = NativeEngine.setStep(slot, pattern, step, on, notes, velocity, gate, probability)

    override fun setMotionValue(slot: Int, pattern: Int, target: Int, step: Int, value: Float) =
        NativeEngine.setMotionValue(slot, pattern, target, step, value)

    override fun clearMotionLane(slot: Int, pattern: Int, target: Int) = NativeEngine.clearMotionLane(slot, pattern, target)
    override fun clearPattern(slot: Int, pattern: Int) = NativeEngine.clearPattern(slot, pattern)
    override fun setTempo(bpm: Double) = NativeEngine.setTempo(bpm)
}

/**
 * Everything the screens share: slots, the selected slot and screen, the pattern mirror,
 * transport, the Record and Shift buttons, the tape and mixer, and where key and pad touches
 * go. The engine's transport and tape are polled once per display frame ([frame],
 * [tapeFrame]); the UI never drives timing (F3.10).
 * Main thread only (NativeEngine's rule).
 */
@Stable
class GrooveState : KeyListener {
    val shift = ShiftController()
    private val recordButton = RecordButton()
    private val stripEdited: () -> Unit = { stripMoved() }

    /** Engine slots 1..7. */
    val synths: List<SynthController> = List(SeqLimits.SLOTS - 1) { SynthController(it + 1, shift, stripEdited) }
    val drum = DrumController(shift, stripEdited)
    val store = PatternStore(NativePatternSink)
    private val noteMemory = NoteMemory { slot -> if (slot == NativeEngine.DRUM_SLOT) DrumLayout.LOWEST_NOTE else DEFAULT_SYNTH_NOTE }
    private val pads = StepPads()
    val seq = SeqController(this)
    val mix = MixValues()
    val tape = TapeController(this, mix)
    val mixer = MixerController(this, mix)

    /** Bumped on every change to the pattern mirror; read it to recompose on pattern edits. */
    var revision by mutableLongStateOf(0L)
        private set

    var screen by mutableStateOf(Screen.Synth)
        private set
    var selectedSlot by mutableIntStateOf(1)
        private set
    var lastSynthSlot by mutableIntStateOf(1)
        private set

    /** The step page (0..3) the pads show. */
    var page by mutableIntStateOf(0)

    /** The step selected with Shift + pad, or null. */
    var selectedStep by mutableStateOf<Int?>(null)
        private set

    /** The held step loop of the selected slot, or null. */
    var stepLoop by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    private var loopSlot = -1

    var armed by mutableStateOf(false)
        private set
    var motionHeld by mutableStateOf(false)
        private set

    var playing by mutableStateOf(false)
        private set
    var tempoBpm by mutableDoubleStateOf(SeqLimits.DEFAULT_TEMPO)
        private set
    var blinkOn by mutableStateOf(true)
        private set
    private val currentStep = List(SeqLimits.SLOTS) { mutableIntStateOf(-1) }
    private val currentPattern = List(SeqLimits.SLOTS) { mutableIntStateOf(0) }
    private val queuedPattern = List(SeqLimits.SLOTS) { mutableIntStateOf(-1) }

    // --- tape readout (F5), from NativeEngine.tapeState() every frame ---

    /** The track the Tape screen records onto and whose Level/Pan the strips show. */
    var tapeTrack by mutableIntStateOf(0)
        private set
    var tapeBars by mutableIntStateOf(0)
        private set
    var tapeLengthFrames by mutableLongStateOf(0L)
        private set

    /** Read it in a draw phase only: it changes every frame while playing. */
    var tapeHeadFrame by mutableLongStateOf(0L)
        private set
    var tapeArmedTrack by mutableIntStateOf(-1)
        private set
    var tapeRecordingTrack by mutableIntStateOf(-1)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private val trackHasAudio = List(MixLimits.TRACKS) { mutableStateOf(false) }

    /** Peak overviews of the four tracks; [overviewRevision] changes when they do. */
    val overview = TapeOverview()
    var overviewRevision by mutableLongStateOf(0L)
        private set
    private val overviewBuffer = ByteArray(MixLimits.OVERVIEW_BINS)
    private var framesSinceOverview = OVERVIEW_IDLE_FRAMES

    private val heldNoteSlot = HashMap<Int, Int>()
    private var seeded = false

    init {
        store.onChange = { revision = store.revision }
        // Leaving the Shift layer ends step editing.
        shift.onChange = { on -> if (!on) deselectStep() }
    }

    fun synth(slot: Int): SynthController = synths[(slot - 1).coerceIn(0, synths.lastIndex)]

    val selectedSynth: SynthController
        get() = synth(lastSynthSlot)

    fun isDrum(slot: Int) = slot == NativeEngine.DRUM_SLOT

    fun stepOf(slot: Int): Int = currentStep[slot].intValue
    fun patternOf(slot: Int): Int = currentPattern[slot].intValue
    fun queuedOf(slot: Int): Int = queuedPattern[slot].intValue

    /** The pattern the Sequencer screen edits: the selected slot's current one. */
    val editPattern: Int
        get() = patternOf(selectedSlot)

    fun settings(): PatternSettings {
        revision
        return store.settings(selectedSlot, editPattern)
    }

    fun step(i: Int): Step {
        revision
        return store.step(selectedSlot, editPattern, i)
    }

    fun latestLane(i: Int): MotionLane? {
        revision
        return store.latestLaneAt(selectedSlot, editPattern, i)
    }

    fun hasMotion(i: Int): Boolean {
        revision
        return store.lanes(selectedSlot, editPattern).any { it.hasValueAt(i) }
    }

    fun laneCount(): Int {
        revision
        return store.lanes(selectedSlot, editPattern).size
    }

    /** The parameter a motion target names on [slot], or null. */
    fun targetSpec(slot: Int, target: Int): ParamSpec? {
        if (!isDrum(slot)) return synth(slot).spec(target)
        val n = drum.params.size
        return if (n == 0 || target < 0) null else drum.params.getOrNull(target % n)
    }

    /** "Cutoff", or "Kick1 Decay" on the drum slot. */
    fun targetName(slot: Int, target: Int): String {
        val spec = targetSpec(slot, target) ?: return "?"
        if (!isDrum(slot)) return spec.label
        val key = target / drum.params.size
        return "${drum.keyLabels[DrumLayout.noteOf(key)] ?: "${key + 1}"} ${spec.label}"
    }

    // --- slots and screens ---

    /**
     * A slot button: selects the slot and shows its instrument (or, from Seq, its sequencer;
     * from the Mixer, its channel).
     */
    fun selectSlot(slot: Int) {
        if (slot !in 0 until SeqLimits.SLOTS) return
        if (slot != selectedSlot) leaveStepEditing()
        selectedSlot = slot
        if (!isDrum(slot)) lastSynthSlot = slot
        when (screen) {
            Screen.Seq -> Unit
            Screen.Mixer -> mixer.select(MixerPad.Slot(slot))
            else -> screen = if (isDrum(slot)) Screen.Drum else Screen.Synth
        }
    }

    fun showSynth() {
        selectSlot(lastSynthSlot)
        screen = Screen.Synth
    }

    fun showDrum() {
        selectSlot(NativeEngine.DRUM_SLOT)
        screen = Screen.Drum
    }

    fun showSeq() {
        screen = Screen.Seq
    }

    fun showTape() {
        if (screen != Screen.Tape) framesSinceOverview = OVERVIEW_IDLE_FRAMES // fresh lanes at once
        screen = Screen.Tape
    }

    fun showMixer() {
        screen = Screen.Mixer
    }

    /** The name a slot shows: "Kit", or the synth's preset. */
    fun instrumentName(slot: Int): String = if (isDrum(slot)) "Kit" else synth(slot).presetName

    private fun leaveStepEditing() {
        deselectStep()
        clearLoop()
    }

    private fun deselectStep() {
        pads.clearSelection()
        selectedStep = null
    }

    // --- keys ---

    override fun keyDown(note: Int, velocity: Float, tapTimeNs: Long) {
        val slot = selectedSlot
        heldNoteSlot[note] = slot
        NativeEngine.noteOn(slot, note, velocity, tapTimeNs)
        val chord = noteMemory.keyDown(slot, note, velocity)
        if (isDrum(slot)) DrumLayout.keyOf(note)?.let(drum::select)
        // F3.5: keys played while a step is selected set its notes (held keys = a chord).
        val step = editingStep
        if (step != null) store.setStepNotes(slot, editPattern, step, chord, velocity)
    }

    override fun keyUp(note: Int) {
        val slot = heldNoteSlot.remove(note) ?: selectedSlot
        NativeEngine.noteOff(slot, note)
        noteMemory.keyUp(slot, note)
    }

    /** The step keys write into, when editing one on the Sequencer screen. */
    val editingStep: Int?
        get() = if (screen == Screen.Seq && shift.shifted) selectedStep else null

    // --- step pads ---

    fun padDown(pointerId: Int, pad: Int) = handle(pads.down(pointerId, page * PADS + pad, shift.shifted))

    fun padUp(pointerId: Int) = handle(pads.up(pointerId))

    /** The pad's touch ended without a release (e.g. the pads left the screen): no toggle. */
    fun padAbandon() = handle(pads.cancel())

    private fun handle(action: PadAction?) {
        val slot = selectedSlot
        val pattern = editPattern
        when (action) {
            is PadAction.Toggle -> {
                val (notes, velocity) = noteMemory.entryFor(slot, store.step(slot, pattern, action.step))
                store.toggleStep(slot, pattern, action.step, notes, velocity)
            }
            is PadAction.Select -> selectedStep = action.step
            is PadAction.Loop -> {
                val last = store.settings(slot, pattern).length - 1
                val start = action.start.coerceIn(0, last)
                val end = action.end.coerceIn(start, last)
                NativeEngine.setStepLoop(slot, start, end)
                loopSlot = slot
                stepLoop = start to end
            }
            PadAction.ClearLoop -> clearLoop()
            null -> Unit
        }
    }

    private fun clearLoop() {
        if (loopSlot >= 0) NativeEngine.clearStepLoop(loopSlot)
        loopSlot = -1
        stepLoop = null
    }

    // --- transport ---

    /** Any strip moved (a held Record button then records motion instead of toggling arm). */
    fun stripMoved() = recordButton.stripMoved()

    fun togglePlay() {
        if (playing) {
            clearLoop()
            NativeEngine.stopTransport()
        } else {
            NativeEngine.play()
        }
        // [playing] follows the engine's transport on the next frame.
    }

    fun recordPress(timeMs: Long) {
        if (!recordButton.press(timeMs)) return
        NativeEngine.setMotionRecording(true)
        motionHeld = true
    }

    fun recordRelease(timeMs: Long) {
        val armChanged = recordButton.release(timeMs)
        NativeEngine.setMotionRecording(false)
        motionHeld = false
        if (armChanged) {
            armed = recordButton.armed
            NativeEngine.setRecordArmed(armed)
        }
    }

    fun recordCancel() {
        recordButton.cancel()
        NativeEngine.setMotionRecording(false)
        motionHeld = false
    }

    fun setTempo(bpm: Double) {
        store.setTempo(bpm)
        tempoBpm = store.tempo
    }

    /** Queued while playing (F3.9), at once when stopped; the transport readout shows which. */
    fun selectPattern(pattern: Int) {
        val p = pattern.coerceIn(0, SeqLimits.PATTERNS - 1)
        val slot = selectedSlot
        if (p == patternOf(slot) && queuedOf(slot) < 0) return
        clearLoop()
        NativeEngine.selectPattern(slot, p)
    }

    /** Once per display frame: the engine's transport and recorded writes. */
    fun frame(transport: TransportState, recorded: List<RecordedEvent>, frameTimeNanos: Long) {
        playing = transport.playing
        if (transport.tempoBpm >= SeqLimits.MIN_TEMPO) tempoBpm = transport.tempoBpm
        for (s in 0 until SeqLimits.SLOTS) {
            currentStep[s].intValue = transport.currentStep.getOrElse(s) { -1 }
            currentPattern[s].intValue = transport.currentPattern.getOrElse(s) { 0 }.coerceIn(0, SeqLimits.PATTERNS - 1)
            queuedPattern[s].intValue = transport.queuedPattern.getOrElse(s) { -1 }
        }
        for (e in recorded) store.applyRecorded(e)
        blinkOn = (frameTimeNanos / BLINK_NS) % 2 == 0L
    }

    // --- tape (F5) ---

    fun selectTrack(track: Int) {
        if (track in 0 until MixLimits.TRACKS) tapeTrack = track
    }

    fun trackHasAudio(track: Int): Boolean = trackHasAudio.getOrNull(track)?.value == true

    val tapeStatus: TapeRecordStatus
        get() = TapeView.status(tapeArmedTrack, tapeRecordingTrack)

    /** The engine ignores an arm unless the tape runs at 1x forward. */
    val tapeCanRecord: Boolean
        get() = TapeView.canRecord(mix.mix(MixKeys.SPEED).roundToInt(), mix.mix(MixKeys.REVERSE) >= 0.5)

    /** Record on the Tape screen: arms the selected track, or stops (or cancels an arm). */
    fun tapeRecordPress() {
        when (val action = TapeView.recordPress(tapeStatus, tapeTrack, tapeCanRecord)) {
            is TapeRecordAction.Arm -> NativeEngine.tapeArmRecord(action.track)
            TapeRecordAction.Stop -> NativeEngine.tapeStopRecord()
            TapeRecordAction.None -> Unit
        }
    }

    /** The top bar's Undo button: undo, or redo with Shift. */
    fun tapeUndoPress() {
        if (shift.shifted) NativeEngine.tapeRedo() else NativeEngine.tapeUndo()
    }

    /**
     * Commit loop (F5.6) onto the selected track: the selected slot's current pattern, or
     * with [allSlots] the full mix. A commit records while playing, so it starts the
     * transport when stopped; it begins at the slot's next pattern boundary.
     */
    fun commit(allSlots: Boolean) {
        if (!playing) NativeEngine.play()
        NativeEngine.tapeCommit(tapeTrack, if (allSlots) -1 else selectedSlot)
    }

    /** Clears one track (undoable, so no confirmation). */
    fun clearTrack(track: Int) {
        if (track !in 0 until MixLimits.TRACKS) return
        NativeEngine.tapeClearTrack(track)
        framesSinceOverview = OVERVIEW_IDLE_FRAMES
    }

    /**
     * Once per display frame: the tape readout, and on the Tape screen the lane overviews
     * (every frame while recording, otherwise a few times a second and on any change).
     */
    fun tapeFrame(t: TapeState) {
        val changed = t.lengthFrames != tapeLengthFrames || t.canUndo != canUndo || t.canRedo != canRedo ||
            t.recordingTrack != tapeRecordingTrack
        tapeBars = t.lengthBars
        tapeLengthFrames = t.lengthFrames
        tapeHeadFrame = t.headFrame
        tapeArmedTrack = t.armedTrack
        tapeRecordingTrack = t.recordingTrack
        canUndo = t.canUndo
        canRedo = t.canRedo
        for (i in 0 until MixLimits.TRACKS) trackHasAudio[i].value = t.trackHasAudio.getOrElse(i) { false }

        if (screen != Screen.Tape) return
        framesSinceOverview++
        if (changed || t.recordingTrack >= 0 || framesSinceOverview >= OVERVIEW_IDLE_FRAMES) {
            framesSinceOverview = 0
            for (track in 0 until MixLimits.TRACKS) {
                NativeEngine.tapeOverview(track, overviewBuffer)
                overview.update(track, overviewBuffer)
            }
            overviewRevision = overview.revision
        }
    }

    // --- lifecycle ---

    /** After the stream starts: the first time, the launch presets and demo; later, re-read the sounds. */
    fun onStart() {
        if (!seeded) {
            seeded = true
            val presets = DemoContent.presetIndexes(synths.first().presetNames)
            synths.forEachIndexed { i, s -> presets.getOrNull(i)?.let(s::loadPreset) ?: s.refreshFromEngine() }
            drum.refreshFromEngine()
            mix.refreshFromEngine()
            mix.seedSends(::instrumentName)
            DemoContent.write(store)
            tempoBpm = store.tempo
        } else {
            synths.forEach { it.refreshFromEngine() }
            drum.refreshFromEngine()
            mix.refreshFromEngine()
        }
    }

    /** Before the stream stops: let go of everything held. */
    fun onStop() {
        if (motionHeld) recordCancel()
        clearLoop()
        NativeEngine.allNotesOff()
        heldNoteSlot.clear()
        noteMemory.releaseAll()
    }

    private companion object {
        const val PADS = 16
        const val DEFAULT_SYNTH_NOTE = 60
        const val BLINK_NS = 300_000_000L

        /** Overview refresh when nothing is recording: about 6 times a second at 60 Hz. */
        const val OVERVIEW_IDLE_FRAMES = 10
    }
}
