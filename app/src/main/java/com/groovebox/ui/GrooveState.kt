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
import com.groovebox.storage.Session
import com.groovebox.storage.UiSnapshot
import com.groovebox.ui.model.ClearConfirm
import com.groovebox.ui.model.DemoContent
import com.groovebox.ui.model.ExportFiles
import com.groovebox.ui.model.ExportHost
import com.groovebox.ui.model.ExportStatus
import com.groovebox.ui.model.ExportTracker
import com.groovebox.ui.model.DrumLayout
import com.groovebox.ui.model.Hit
import com.groovebox.ui.model.KeyScale
import com.groovebox.ui.model.MixKeys
import com.groovebox.ui.model.MixLimits
import com.groovebox.ui.model.MixerLayout
import com.groovebox.ui.model.MixerPad
import com.groovebox.ui.model.MixerSync
import com.groovebox.ui.model.SlotChip
import com.groovebox.ui.model.TapTempo
import com.groovebox.ui.model.MotionLane
import com.groovebox.ui.model.NoteMemory
import com.groovebox.ui.model.Accent
import com.groovebox.ui.model.ChordEdit
import com.groovebox.ui.model.EditScope
import com.groovebox.ui.model.PadAction
import com.groovebox.ui.model.PageFollow
import com.groovebox.ui.model.PatternChain
import com.groovebox.ui.model.PatternEdits
import com.groovebox.ui.model.PatternRowAction
import com.groovebox.ui.model.PatternSnapshot
import com.groovebox.ui.model.StepGrid
import com.groovebox.ui.model.PatternHistory
import com.groovebox.ui.model.PatternSettings
import com.groovebox.ui.model.PatternSink
import com.groovebox.ui.model.PatternStore
import com.groovebox.ui.model.RecordButton
import com.groovebox.ui.model.RecordTake
import com.groovebox.ui.model.Scale
import com.groovebox.ui.model.SeqLimits
import com.groovebox.ui.model.SlotSwipe
import com.groovebox.ui.model.Step
import com.groovebox.ui.model.StepPads
import com.groovebox.ui.model.TapeClearConfirm
import com.groovebox.ui.model.TapeOverview
import com.groovebox.ui.model.TapeRecordAction
import com.groovebox.ui.model.TapeRecordStatus
import com.groovebox.ui.model.TapeView
import com.groovebox.ui.model.SynthLayout
import com.groovebox.ui.model.SoundTarget
import com.groovebox.ui.model.SoundHistory
import com.groovebox.ui.model.SoundEdit
import com.groovebox.ui.model.KeyOctave
import kotlin.math.roundToInt

/** The five screens of the top bar's tabs. */
enum class Screen { Synth, Drum, Seq, Tape, Mixer }

/** Sends the pattern store's edits to the engine. */
private object NativePatternSink : PatternSink {
    override fun setPatternSettings(slot: Int, pattern: Int, length: Int, rate: Int, shuffle: Float, gate: Float) =
        NativeEngine.setPatternSettings(slot, pattern, length, rate, shuffle, gate)

    override fun setStep(slot: Int, pattern: Int, step: Int, hits: FloatArray, hitCount: Int) {
        NativeEngine.setStep(slot, pattern, step, hits, hitCount)
    }

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
 *
 * Persistence (M4): [launch] puts a saved session (or the demo) into the engine before the
 * stream starts; [snapshotSession] copies what is saved; [onSessionChange] is called after
 * every change worth saving (the autosave hook).
 * Main thread only (NativeEngine's rule).
 */
@Stable
class GrooveState : KeyListener {
    val shift = ShiftController()
    private val recordButton = RecordButton()
    private val stripEdited: () -> Unit = { stripMoved() }
    private val edited: () -> Unit = { sessionChanged() }

    /** Called after every change worth saving (F7.4); set by the app's autosave. */
    var onSessionChange: (() -> Unit)? = null

    // --- sounds (build-22: one undo history, user presets, the Play rail) ---

    /** Undo / Redo of synth and kit sound edits (build-22), shared by the Synth and Drum screens; per app run. */
    val soundHistory = SoundHistory()

    /** Bumped on every [soundHistory] change; read it to follow [soundCanUndo] / [soundCanRedo]. */
    var soundRevision by mutableLongStateOf(0L)
        private set
    private val soundHistoryChanged: () -> Unit = { soundRevision = soundHistory.revision }

    val soundCanUndo: Boolean
        get() = soundRevision.let { soundHistory.canUndo }
    val soundCanRedo: Boolean
        get() = soundRevision.let { soundHistory.canRedo }

    /** The user presets (build-22), shared by every synth slot and saved with the session. */
    val userPresets = UserPresetLibrary(edited)

    /** The Synth and Tape keys' octave (build-22), −2..+2: only what the keys play; the pattern is untouched. */
    val keyOctave: Int
        get() = keyOctaveState
    private var keyOctaveState by mutableIntStateOf(0)

    fun setKeyOctave(octave: Int) {
        val o = KeyOctave.clamp(octave)
        if (o == keyOctaveState) return
        keyOctaveState = o
        sessionChanged()
    }

    private val playControls = object : PlayControls {
        override val keyScale: KeyScale get() = this@GrooveState.keyScale
        override val keyOctave: Int get() = this@GrooveState.keyOctave
        override fun setKey(root: Int) = this@GrooveState.setKey(root)
        override fun setScale(scale: Scale) = this@GrooveState.setScale(scale)
        override fun setKeyOctave(octave: Int) = this@GrooveState.setKeyOctave(octave)
    }

    /** Engine slots 1..7. */
    val synths: List<SynthController> =
        List(SeqLimits.SLOTS - 1) {
            SynthController(it + 1, shift, stripEdited, edited, { tempoBpm }, soundHistory, soundHistoryChanged, userPresets, playControls)
        }
    val drum = DrumController(
        shift, stripEdited, edited, soundHistory, soundHistoryChanged,
        playing = { playing },
        inPattern = { key -> soundInPlayingPattern(key) },
    )

    /** The kit's current pattern plays kit key [key] somewhere (then the beat replays an edit by itself). */
    private fun soundInPlayingPattern(key: Int): Boolean {
        val slot = NativeEngine.DRUM_SLOT
        val note = DrumLayout.noteOf(key)
        val pattern = patternOf(slot)
        val length = store.settings(slot, pattern).length
        return (0 until length).any { i -> store.step(slot, pattern, i).hits.any { it.note == note } }
    }

    /**
     * The Undo chip of the Synth and Drum displays (build-22): a tap undoes the last sound edit,
     * a hold redoes ([com.groovebox.ui.model.UndoHold]). The slot (or kit sound) it changes is
     * shown: its slot is selected, and on a synth the parameter's section.
     */
    fun soundUndoPress(redo: Boolean) {
        val edit = (if (redo) soundHistory.redo() else soundHistory.undo()) ?: return
        soundHistoryChanged()
        when (val target = edit.target) {
            is SoundTarget.Synth -> {
                if (screen == Screen.Synth || screen == Screen.Drum) selectSlot(target.slot)
                val synth = synth(target.slot)
                synth.applyEdit(edit, undo = !redo)
                (edit as? SoundEdit.Params)?.changes?.firstOrNull()?.let { c ->
                    SynthLayout.sectionOf(c.key)?.let { synth.section = it }
                }
            }
            is SoundTarget.Drum -> {
                if (screen == Screen.Synth) selectSlot(NativeEngine.DRUM_SLOT)
                drum.applyEdit(edit, undo = !redo)
            }
        }
    }

    /** Shift + long-press the Synth display (build-22): saves the selected slot as a user preset. Its name, or null when full. */
    fun saveUserPreset(): String? = if (isDrum(selectedSlot)) null else synth(selectedSlot).saveUserPreset()

    /** Hold slot [from] and tap slot [to] (build-22): copies the sound; synth slots only; one undo step. */
    fun copySound(from: Int, to: Int) {
        if (from == to || isDrum(from) || isDrum(to)) return
        synth(to).copyFrom(synth(from).sound())
    }
    val store = PatternStore(NativePatternSink)

    /** Undo / Redo of the sequencer's edits (build-20), per app run. */
    val history = PatternHistory(store)
    // Before any key is played a synth pad tap enters C4, or the in-key note nearest it (F8).
    private val noteMemory = NoteMemory { slot ->
        if (slot == NativeEngine.DRUM_SLOT) DrumLayout.LOWEST_NOTE else keyScale.snap(DEFAULT_SYNTH_NOTE)
    }
    private val pads = StepPads()
    val seq = SeqController(this)
    val mix = MixValues(edited)
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

    private var pageState by mutableIntStateOf(0)

    /** The step page (0..3) the pads show. */
    var page: Int
        get() = pageState
        set(value) {
            val p = value.coerceIn(0, PAGES - 1)
            if (p == pageState) return
            pageState = p
            sessionChanged()
        }

    /**
     * The session's key and scale (F8), one for every synth slot; the drum slot ignores it.
     * Applied in the UI before notes reach the engine (F8.8): the keyboard's gate, the Note
     * strip and the pads' marks read it. Changing it never rewrites steps or cuts held notes.
     */
    var keyScale by mutableStateOf(KeyScale.OFF)
        private set

    fun setKey(root: Int) = changeKeyScale(KeyScale.of(root.coerceIn(0, 11), keyScale.scale))

    fun setScale(scale: Scale) = changeKeyScale(keyScale.copy(scale = scale))

    private fun changeKeyScale(k: KeyScale) {
        if (k == keyScale) return
        keyScale = k
        sessionChanged()
    }

    /** The keyboard plays [note] on the selected slot: always on the drum slot, else only in key (F8.2, F8.7). */
    fun keyPlayable(note: Int): Boolean = keyScale.playable(note, isDrum(selectedSlot))

    /** The pad of step [i] gets the out-of-key mark (F8.6). */
    fun stepOutOfKey(i: Int): Boolean = keyScale.marksStep(step(i), isDrum(selectedSlot))

    /** The steps selected (long-press a pad, build-20), in the order they were selected. */
    var selectedSteps by mutableStateOf<List<Int>>(emptyList())
        private set

    /** The step selected last: the one the strips and the footer show. */
    val focusStep: Int?
        get() = selectedSteps.lastOrNull()

    /** The drum lane (build-20): the kit sound the Seq pads show and toggle, the Drum screen's selected key. */
    val laneKey: Int
        get() = drum.selectedKey

    val laneNote: Int
        get() = DrumLayout.noteOf(drum.selectedKey)

    /** The Seq screen's Undo / Redo can do something; follows [history]. */
    var seqCanUndo by mutableStateOf(false)
        private set
    var seqCanRedo by mutableStateOf(false)
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

    /** A saved tape is still loading in the background (F7.6). */
    var tapeLoading by mutableStateOf(false)
        private set

    /** The tape's tempo (0 when empty) and whether the sequencer is at it (build-20: record needs it). */
    var tapeTempoBpm by mutableStateOf(0f)
        private set
    var tapeTempoMatches by mutableStateOf(true)
        private set

    // --- export (F7.1, F7.2) ---

    /** Where exports go and the share sheet; set by the activity. */
    var exportHost: ExportHost? = null
    private val exportTracker = ExportTracker()
    var exportStatus by mutableStateOf<ExportStatus>(ExportStatus.Idle)
        private set

    // --- clear pattern (Seq screen) ---

    private val clearConfirm = ClearConfirm()

    /** The Clear button was tapped once and waits for the confirming tap. */
    var clearArmed by mutableStateOf(false)
        private set

    /** The armed clear is Shift + Clear's: only the drum lane's sound. */
    var clearArmedLane by mutableStateOf(false)
        private set

    // Open undo groups: the token of each begin(), for the matching end().
    private val take = RecordTake()
    private var takeToken = 0
    private var motionToken: Int? = null
    private var keyToken: Int? = null

    /** Monotonic time of the last display frame, ms (the clock for export and Clear). */
    private var nowMs = 0L

    private val heldNoteSlot = HashMap<Int, Int>()
    private var launched = false
    private var startedOnce = false

    init {
        store.onChange = {
            revision = store.revision
            sessionChanged()
        }
        history.onChange = {
            seqCanUndo = history.canUndo
            seqCanRedo = history.canRedo
        }
    }

    /** Something worth saving changed (autosave, F7.4). */
    fun sessionChanged() {
        onSessionChange?.invoke()
    }

    /** Runs a selection change and marks the session dirty if the screen or selection moved. */
    private inline fun uiEdit(action: () -> Unit) {
        val before = UiKey(screen, selectedSlot, lastSynthSlot, tapeTrack)
        action()
        if (UiKey(screen, selectedSlot, lastSynthSlot, tapeTrack) != before) sessionChanged()
    }

    private data class UiKey(val screen: Screen, val slot: Int, val lastSynth: Int, val track: Int)

    fun synth(slot: Int): SynthController = synths[(slot - 1).coerceIn(0, synths.lastIndex)]

    val selectedSynth: SynthController
        get() = synth(lastSynthSlot)

    fun isDrum(slot: Int) = slot == NativeEngine.DRUM_SLOT

    fun stepOf(slot: Int): Int = currentStep[slot].intValue
    fun patternOf(slot: Int): Int = currentPattern[slot].intValue
    fun queuedOf(slot: Int): Int = queuedPattern[slot].intValue

    // --- build-22: the pattern edited, chains, page follow, fill, pen (Seq) ---

    /** Per slot: a pattern picked on the pattern row to edit while another plays, or -1 (edit the playing one). */
    private val editPin = List(SeqLimits.SLOTS) { mutableIntStateOf(-1) }

    /** Per slot: a chain A → B made on the pattern row (hold A, tap B), or null. */
    private val chains = List(SeqLimits.SLOTS) { mutableStateOf<PatternChain?>(null) }

    fun chainOf(slot: Int): PatternChain? = chains.getOrNull(slot)?.value

    /** The pattern the Sequencer screen edits on [slot]: the one picked on the pattern row, or the playing one. */
    fun editPatternOf(slot: Int): Int = editPin.getOrNull(slot)?.intValue?.takeIf { it >= 0 } ?: patternOf(slot)

    /** The pattern the Sequencer screen edits: the selected slot's [editPatternOf]. */
    val editPattern: Int
        get() = editPatternOf(selectedSlot)

    /** The edited pattern is not the one playing (it is outlined on the pattern row). */
    val editingOther: Boolean
        get() = editPin[selectedSlot].intValue >= 0 && editPin[selectedSlot].intValue != patternOf(selectedSlot)

    /** [slot]'s [pattern] holds a step or motion (the pattern row's dot). */
    fun patternHasContent(slot: Int, pattern: Int): Boolean {
        revision
        return store.hasContent(slot, pattern)
    }

    private val pageFollow = PageFollow()

    /** Page follow is on (Shift + tap the page shown). */
    var followPage by mutableStateOf(false)
        private set

    /** Fill armed by Shift + tap on this step (absolute), or -1. */
    var fillFrom by mutableIntStateOf(-1)
        private set

    /** Per slot: the lowest note of the Seq pen keys (‹ › move it by octaves). */
    private val penLow = List(SeqLimits.SLOTS) { mutableIntStateOf(PEN_DEFAULT_LOW) }

    fun penLowOf(slot: Int): Int = penLow.getOrNull(slot)?.intValue ?: PEN_DEFAULT_LOW

    /** ‹ › beside the pen keys: the keys move [octaves] octaves, within C1..C6. */
    fun penOctave(octaves: Int) {
        val v = penLow[selectedSlot]
        v.intValue = (v.intValue + 12 * octaves).coerceIn(PEN_MIN_LOW, PEN_MAX_LOW)
    }

    /** The notes a pad tap writes on [slot] (the pen readout). */
    fun penNotes(slot: Int): List<Int> {
        penRevision
        return noteMemory.lastNotes(slot)
    }

    private var penRevision by mutableIntStateOf(0)

    /** The next key starts the selected steps' chord afresh (the first key replaces). */
    private var chordFresh = true

    /** A short note the Seq footer shows for a moment ("×2 · 32 steps"), with when it goes. */
    var seqFlash by mutableStateOf<String?>(null)
        private set
    private var seqFlashUntil = 0L

    private fun flash(text: String) {
        seqFlash = text
        seqFlashUntil = nowMs + FLASH_MS
    }

    private var swipeToken: Int? = null

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
    fun selectSlot(slot: Int) = uiEdit {
        if (slot !in 0 until SeqLimits.SLOTS) return@uiEdit
        if (slot != selectedSlot) leaveStepEditing()
        selectedSlot = slot
        if (!isDrum(slot)) lastSynthSlot = slot
        when (screen) {
            Screen.Seq -> Unit
            // Build-22: the Tape screen's slot chip picks what the keys play and Commit takes.
            Screen.Tape -> Unit
            Screen.Mixer -> mixer.select(MixerPad.Slot(slot))
            else -> screen = if (isDrum(slot)) Screen.Drum else Screen.Synth
        }
    }

    /** A swipe on the display (ADR 0003): [steps] slots on, clamped; see [SlotSwipe.target]. */
    fun swipeSlot(steps: Int, includeDrum: Boolean) {
        if (steps != 0) selectSlot(SlotSwipe.target(selectedSlot, steps, includeDrum))
    }

    fun showSynth() = uiEdit {
        selectSlot(lastSynthSlot)
        screen = Screen.Synth
    }

    fun showDrum() = uiEdit {
        selectSlot(NativeEngine.DRUM_SLOT)
        screen = Screen.Drum
    }

    fun showSeq() = uiEdit {
        screen = Screen.Seq
    }

    fun showTape() = uiEdit {
        if (screen != Screen.Tape) framesSinceOverview = OVERVIEW_IDLE_FRAMES // fresh lanes at once
        screen = Screen.Tape
    }

    fun showMixer() = uiEdit {
        // Build-22: a slot or tape pad follows the top bar's slot and the Tape screen's track.
        mixer.restoreSelection(MixerSync.padOnShow(mixer.selected, selectedSlot, tapeTrack))
        screen = Screen.Mixer
    }

    /** The Tape slot chip (build-22): a tap steps to the next slot. */
    fun slotChipTap() = selectSlot(SlotChip.next(selectedSlot))

    private val tapTempo = TapTempo()

    /** Taps in the current tap-tempo run; above 0 the BPM readout is lit and reads "TAP". */
    var tapCount by mutableIntStateOf(0)
        private set

    /** A tap on the BPM readout (build-22): the third and later taps set the tempo. */
    fun tapTempo(nowMs: Long) {
        tapTempo.tap(nowMs, tempoBpm)?.let(::setTempo)
        tapCount = tapTempo.count
    }

    /** A long-press on the BPM readout: back to 120. */
    fun tapTempoReset() {
        tapTempo.reset()
        tapCount = 0
        setTempo(TapTempo.LONG_PRESS_BPM)
    }

    /** The readout's tap run timed out (no tap for [TapTempo.TIMEOUT_MS]). */
    fun tapTempoIdle() {
        tapTempo.reset()
        tapCount = 0
    }

    /** The name a slot shows: "Kit", or the synth's preset. */
    fun instrumentName(slot: Int): String = if (isDrum(slot)) "Kit" else synth(slot).presetName

    private fun leaveStepEditing() {
        deselectSteps()
        clearLoop()
    }

    /** Deselects every step (a tap on the display, a slot or pattern change). */
    fun deselectSteps() {
        seq.endNoteDrag()
        seq.endHitDrags()
        pads.clearSelection()
        selectedSteps = emptyList()
        chordFresh = true
        fillFrom = -1
    }

    // --- keys ---

    override fun keyDown(note: Int, velocity: Float, tapTimeNs: Long) {
        val slot = selectedSlot
        heldNoteSlot[note] = slot
        // Build-22: on Seq with steps selected the keys edit them and are only auditioned, so
        // nothing records into the pattern while Record is armed (selecting stops recording).
        val selecting = screen == Screen.Seq && selectedSteps.isNotEmpty()
        if (selecting) NativeEngine.auditionOn(slot, note, velocity) else NativeEngine.noteOn(slot, note, velocity, tapTimeNs)
        noteMemory.keyDown(slot, note, velocity)
        penRevision++
        // The drum keys choose the lane too (build-20).
        if (isDrum(slot)) DrumLayout.keyOf(note)?.let(drum::select)
        // Build-22 chords: while synth steps are selected the first key replaces their notes and
        // each further key toggles one (keys held together are one undo step).
        val steps = editingSteps
        if (steps.isNotEmpty()) {
            seq.endNoteDrag() // build-21: a Note drag would write its captured notes back over these
            seq.endHitDrags()
            if (keyToken == null) keyToken = history.begin()
            val pattern = editPattern
            val focus = focusStep ?: steps.last()
            val first = chordFresh
            chordFresh = false
            val target = ChordEdit.press(store.step(slot, pattern, focus).notes, note, first)
            val adding = note in target
            for (step in steps) {
                history.edit(slot, pattern) {
                    store.updateStep(slot, pattern, step) { old ->
                        val notes = when {
                            first -> listOf(note)
                            adding -> if (note in old.notes || old.notes.size >= SeqLimits.CHORD) old.notes else old.notes + note
                            else -> old.notes - note
                        }
                        ChordEdit.withNotes(old, notes)
                    }
                }
            }
            // The pen follows the edited chord, so a pad tap writes it again.
            noteMemory.setChord(slot, store.step(slot, pattern, focus).notes)
        }
    }

    override fun keyUp(note: Int) {
        val slot = heldNoteSlot.remove(note) ?: selectedSlot
        NativeEngine.noteOff(slot, note)
        noteMemory.keyUp(slot, note)
        if (heldNoteSlot.isEmpty()) endKeyGroup()
    }

    private fun endKeyGroup() {
        keyToken?.let(history::end)
        keyToken = null
    }

    /** The steps keys write into: synth steps selected on the Sequencer screen (build-22: chords, see [keyDown]). */
    val editingSteps: List<Int>
        get() = if (screen == Screen.Seq && !isDrum(selectedSlot)) selectedSteps else emptyList()

    // --- drum lanes (build-20) ---

    /**
     * A sound of the Seq screen's 4 x 4 grid touched: it becomes the lane and is auditioned,
     * never recorded, even with Record armed (picking a lane is not playing).
     */
    fun laneDown(key: Int, velocity: Float = SeqLimits.DEFAULT_VELOCITY) {
        if (key !in 0 until DrumLayout.KEY_COUNT || !isDrum(selectedSlot)) return
        drum.select(key)
        NativeEngine.auditionOn(NativeEngine.DRUM_SLOT, DrumLayout.noteOf(key), velocity)
    }

    fun laneUp(key: Int) {
        if (key in 0 until DrumLayout.KEY_COUNT) NativeEngine.noteOff(NativeEngine.DRUM_SLOT, DrumLayout.noteOf(key))
    }

    /** The notes some step of the shown pattern plays (the sound grid marks them). */
    fun notesInPattern(): Set<Int> {
        revision
        val out = HashSet<Int>()
        for (i in 0 until SeqLimits.STEPS) store.step(selectedSlot, editPattern, i).hits.forEach { out += it.note }
        return out
    }

    /** The drum lane's or (synth) the whole step's scope of pad edits (build-22). */
    fun editScope(slot: Int = selectedSlot): EditScope =
        if (isDrum(slot)) EditScope.drumLane(laneNote, laneKey, drum.params.size) else EditScope.WHOLE

    /** Writes [steps] (step → new value) into the edited pattern, each through the undo history. */
    fun writeSteps(steps: Map<Int, Step>) {
        val slot = selectedSlot
        val pattern = editPattern
        for ((i, st) in steps) history.edit(slot, pattern) { store.updateStep(slot, pattern, i) { st } }
    }

    /** Runs a whole-pattern edit of the edited pattern as one undo step; false when it changed nothing. */
    private fun patternEdit(slot: Int = selectedSlot, pattern: Int = editPatternOf(slot), edit: (PatternSnapshot) -> PatternSnapshot?): Boolean {
        val before = store.patternSnapshot(slot, pattern)
        val after = edit(before) ?: return false
        if (after == before) return false
        history.edit(slot, pattern) { store.restorePattern(after) }
        return true
    }

    // --- undo (build-20) ---

    /**
     * The Seq footer's Undo chip (build-21): a tap undoes, a long-press redoes ([redo]); Shift
     * plays no part. Recorded writes the engine has already reported are applied first, so the
     * restore sees them. A tiny window remains: a note the engine records after this poll but
     * before the restore's commands reach the audio thread arrives in a later frame, after the
     * restore (it then lands on the restored mirror; if the restore also rewrote that step, the
     * engine's copy lacks the note).
     */
    fun seqUndoPress(redo: Boolean) {
        applyRecorded(NativeEngine.pollRecorded())
        seq.endNoteDrag() // build-21: a Note drag must not write back over the restored notes
        seq.endHitDrags()
        if (redo) history.redo() else history.undo()
    }

    private fun applyRecorded(recorded: List<RecordedEvent>) {
        for (e in recorded) {
            // Build-21: a note recorded into a step a Note drag holds ends the drag (it would write over it).
            if (seq.noteDragHolds(e.slot, e.pattern, e.step)) seq.endNoteDrag()
            if (seq.hitDragHolds(e.slot, e.pattern, e.step)) seq.endHitDrags()
            history.edit(e.slot, e.pattern) { store.applyRecorded(e) }
        }
    }

    // --- step pads ---

    fun padDown(pointerId: Int, pad: Int) = handle(pads.down(pointerId, page * PADS + pad, shift.shifted))

    /**
     * A tap on the Seq display's pitch roll (build-21): on a note, selects or deselects its
     * [step] as a long-press on its pad would; on no note (null), deselects every step.
     */
    fun rollTap(step: Int?) {
        if (step == null) deselectSteps() else handle(pads.toggleSelection(step))
    }

    /** The pad under [pointerId] was held still for the long-press time. */
    fun padLongPress(pointerId: Int) = handle(pads.longPress(pointerId))

    /**
     * The finger [pointerId] is over pad [pad] of the page shown (null: off the pads); [drifted]
     * once it has moved past the touch slop (build-22: drags, copies, swipes).
     */
    fun padMove(pointerId: Int, pad: Int?, drifted: Boolean) =
        handle(pads.move(pointerId, pad?.let { page * PADS + it }, drifted))

    fun padUp(pointerId: Int) = handle(pads.up(pointerId))

    /** The pad's touch ended without a release (e.g. the pads left the screen): no toggle. */
    fun padAbandon() {
        handle(pads.cancel())
        endSwipe()
    }

    private fun handle(action: PadAction?) {
        val slot = selectedSlot
        val pattern = editPattern
        when (action) {
            is PadAction.Toggle -> {
                fillFrom = -1
                val (notes, entryVelocity) = noteMemory.entryFor(slot, store.step(slot, pattern, action.step))
                history.edit(slot, pattern) {
                    if (isDrum(slot)) {
                        store.toggleHit(slot, pattern, action.step, laneNote, entryVelocity)
                    } else {
                        // Build-22: the pen writes at the default velocity (the fill height shows it; Accent boosts it).
                        store.toggleStep(slot, pattern, action.step, notes, SeqLimits.DEFAULT_VELOCITY)
                    }
                }
            }
            is PadAction.Selection -> selectSteps(action.steps)
            is PadAction.Loop -> {
                fillFrom = -1
                val last = store.settings(slot, pattern).length - 1
                val start = action.start.coerceIn(0, last)
                val end = action.end.coerceIn(start, last)
                NativeEngine.setStepLoop(slot, start, end)
                loopSlot = slot
                stepLoop = start to end
            }
            PadAction.ClearLoop -> clearLoop()
            is PadAction.ShiftTap -> shiftTap(action.step)
            is PadAction.Move -> {
                fillFrom = -1
                val moved = patternEdit { PatternEdits.moved(it, action.from, action.to, action.copy, editScope(slot)) }
                if (moved && !action.copy && action.from in selectedSteps) {
                    selectSteps(selectedSteps.map { if (it == action.from) action.to else it }.distinct(), loadPen = false)
                }
                if (moved) flash(if (action.copy) "copied ${action.from + 1} → ${action.to + 1}" else "moved ${action.from + 1} → ${action.to + 1}")
            }
            is PadAction.Swipe -> swipe(action.by)
            PadAction.SwipeEnd -> endSwipe()
            null -> Unit
        }
    }

    private fun selectSteps(steps: List<Int>, loadPen: Boolean = true) {
        if (steps != selectedSteps) chordFresh = true
        selectedSteps = steps
        fillFrom = -1
        seq.endHitDrags()
        // Build-22: selecting a synth step loads its notes into the pen (a pad tap then copies them).
        val focus = steps.lastOrNull() ?: return
        if (loadPen && !isDrum(selectedSlot)) {
            noteMemory.setChord(selectedSlot, store.step(selectedSlot, editPattern, focus).notes)
            penRevision++
        }
    }

    /**
     * Shift + tap a pad (build-22): with steps selected, paste them there; else a fill armed on
     * another pad fills every (this − that) steps; else a lit synth pad toggles its accent; else
     * the fill is armed here (the same pad again disarms it).
     */
    private fun shiftTap(step: Int) {
        val slot = selectedSlot
        val pattern = editPattern
        val scope = editScope(slot)
        val from = fillFrom
        when {
            selectedSteps.isNotEmpty() -> {
                if (patternEdit { PatternEdits.pasted(it, selectedSteps, step, scope) }) flash("pasted ${selectedSteps.size} at ${step + 1}")
            }
            from == step -> fillFrom = -1
            from >= 0 -> {
                fillFrom = -1
                val part = if (isDrum(slot)) {
                    Step(listOf(Hit(laneNote, noteMemory.lastVelocity(slot))))
                } else {
                    Step.of(noteMemory.lastNotes(slot), SeqLimits.DEFAULT_VELOCITY)
                }
                if (patternEdit { PatternEdits.filled(it, from, step, part, scope) }) flash("fill every ${kotlin.math.abs(step - from)} from ${minOf(step, from) + 1}")
            }
            !isDrum(slot) && store.step(slot, pattern, step).on -> {
                history.edit(slot, pattern) { store.updateStep(slot, pattern, step, Accent::toggled) }
                flash(if (Accent.isOn(store.step(slot, pattern, step))) "accent on ${step + 1}" else "accent off ${step + 1}")
            }
            else -> fillFrom = step
        }
    }

    /** Shift + swipe (build-22): no selection rotates the lane / part; with one, inserts (right) or deletes (left) from the step selected last. */
    private fun swipe(by: Int) {
        if (by == 0) return
        fillFrom = -1
        if (swipeToken == null) swipeToken = history.begin()
        val scope = editScope()
        val at = focusStep
        if (at == null) {
            patternEdit { PatternEdits.rotated(it, by, scope) }
            return
        }
        repeat(kotlin.math.abs(by)) {
            patternEdit { if (by > 0) PatternEdits.inserted(it, at, scope) else PatternEdits.deleted(it, at, scope) }
        }
    }

    private fun endSwipe() {
        swipeToken?.let(history::end)
        swipeToken = null
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
        // Motion recorded while Record is held is one undo step.
        if (motionToken == null) motionToken = history.begin()
    }

    fun recordRelease(timeMs: Long) {
        val armChanged = recordButton.release(timeMs)
        NativeEngine.setMotionRecording(false)
        motionHeld = false
        if (armChanged) {
            armed = recordButton.armed
            NativeEngine.setRecordArmed(armed)
            // A live-record take, Record on to Record off, is one undo step. It closes in
            // [frame], once the engine reports itself disarmed and its last notes are in.
            when (take.armed(armed)) {
                RecordTake.Action.Open -> takeToken = history.begin()
                RecordTake.Action.CloseAndOpen -> {
                    history.end(takeToken)
                    takeToken = history.begin()
                }
                RecordTake.Action.None -> Unit
            }
        }
        endMotionGroup()
    }

    fun recordCancel() {
        recordButton.cancel()
        NativeEngine.setMotionRecording(false)
        motionHeld = false
        endMotionGroup()
    }

    private fun endMotionGroup() {
        motionToken?.let(history::end)
        motionToken = null
    }

    fun setTempo(bpm: Double) {
        store.setTempo(bpm)
        tempoBpm = store.tempo
    }

    /**
     * Queued while playing (F3.9), at once when stopped; the transport readout shows which.
     * The Shift-layer Pattern strip; build-22: it also ends the slot's chain and goes back to
     * editing the playing pattern, and an empty pattern takes the slot's last settings.
     */
    fun selectPattern(pattern: Int) {
        val p = pattern.coerceIn(0, SeqLimits.PATTERNS - 1)
        val slot = selectedSlot
        chains[slot].value = null
        if (p == patternOf(slot) && queuedOf(slot) < 0) {
            setEditPin(slot, -1)
            return
        }
        inheritSettings(slot, editPattern, p)
        setEditPin(slot, -1)
        clearLoop()
        deselectSteps()
        clearConfirm.reset()
        clearArmed = false
        NativeEngine.selectPattern(slot, p)
    }

    /** Edits [pattern] of [slot] (-1: the playing one); leaving the steps edited deselects them. */
    private fun setEditPin(slot: Int, pattern: Int) {
        val before = editPatternOf(slot)
        editPin[slot].intValue = if (pattern == patternOf(slot)) -1 else pattern
        if (editPatternOf(slot) != before && slot == selectedSlot) {
            clearLoop()
            deselectSteps()
            clearConfirm.reset()
            clearArmed = false
        }
    }

    /** Build-22: an empty pattern with the default settings takes [from]'s settings when it is first picked. */
    private fun inheritSettings(slot: Int, from: Int, to: Int) {
        if (from == to) return
        val target = store.patternSnapshot(slot, to)
        if (!PatternEdits.isEmpty(target) || target.settings != PatternSettings()) return
        store.setSettings(slot, to, store.settings(slot, from))
    }

    /**
     * The pattern row (build-22). A tap edits the cell's pattern; while playing that is without
     * switching (it is outlined), and a tap on the cell already edited queues the switch. When
     * stopped a tap switches at once. A tap on the playing pattern cancels a queued switch.
     */
    fun patternRow(action: PatternRowAction) {
        val slot = selectedSlot
        when (action) {
            is PatternRowAction.Tap -> {
                val p = action.pattern
                when {
                    !playing -> selectPattern(p)
                    p == patternOf(slot) -> selectPattern(p) // cancels a queued switch, edits the playing one
                    p == editPattern -> {
                        chains[slot].value = null
                        NativeEngine.selectPattern(slot, p)
                    }
                    else -> {
                        inheritSettings(slot, editPattern, p)
                        setEditPin(slot, p)
                    }
                }
            }
            is PatternRowAction.CopyHere -> {
                val from = editPattern
                if (action.pattern == from) return
                val snap = PatternEdits.retargeted(store.patternSnapshot(slot, from), slot, action.pattern)
                if (patternEdit(slot, action.pattern) { snap }) flash("copied pattern ${from + 1} → ${action.pattern + 1}")
            }
            is PatternRowAction.SwitchAll -> {
                // Each slot switches at the end of its own playing pattern (the engine's queued switch).
                for (s in 0 until SeqLimits.SLOTS) {
                    chains[s].value = null
                    inheritSettings(s, editPatternOf(s), action.pattern)
                    editPin[s].intValue = -1
                    if (action.pattern != patternOf(s) || queuedOf(s) >= 0) NativeEngine.selectPattern(s, action.pattern)
                }
                leaveStepEditing()
                flash("all slots → pattern ${action.pattern + 1}")
            }
            is PatternRowAction.Chain -> {
                val chain = PatternChain(action.first, action.then)
                chains[slot].value = chain
                setEditPin(slot, -1)
                if (!playing) NativeEngine.selectPattern(slot, action.first)
                else chain.queueFor(patternOf(slot), queuedOf(slot))?.let { NativeEngine.selectPattern(slot, it) }
                flash("chain ${action.first + 1} → ${action.then + 1}")
            }
        }
    }

    /**
     * The ×2 chip (build-22): the edited pattern doubles with its content; Shift + ×2 halves it.
     * The pads move to the copy's first page.
     */
    fun seqDouble() {
        val shifted = shift.shifted
        val before = settings().length
        val ok = patternEdit { if (shifted) PatternEdits.halved(it) else PatternEdits.doubled(it) }
        if (!ok) return
        val after = settings().length
        if (!shifted) page = (before / PADS).coerceIn(0, PAGES - 1) else if (page * PADS >= after) page = (after - 1) / PADS
        flash("${if (shifted) "½" else "×2"} · $after steps")
    }

    /** ×2 can double the edited pattern (32 steps or fewer); Shift: halve it (more than 1). */
    val canDouble: Boolean
        get() = if (shift.shifted) settings().length > 1 else settings().length <= PatternEdits.MAX_DOUBLE

    /**
     * The Dup chip (build-22): the edited pattern is copied into the next empty one, which is
     * queued (and edited); Shift + Dup copies without switching.
     */
    fun seqDup() {
        val slot = selectedSlot
        val from = editPattern
        val shifted = shift.shifted
        val to = PatternEdits.nextEmpty(from) { PatternEdits.isEmpty(store.patternSnapshot(slot, it)) }
        if (to == null) {
            flash("no empty pattern")
            return
        }
        val snap = PatternEdits.retargeted(store.patternSnapshot(slot, from), slot, to)
        history.edit(slot, to) { store.restorePattern(snap) }
        if (!shifted) {
            chains[slot].value = null
            setEditPin(slot, to)
            NativeEngine.selectPattern(slot, to)
        }
        flash("pattern ${from + 1} → ${to + 1}${if (shifted) " (copy)" else ""}")
    }

    /**
     * A page on the rail (build-22): a tap shows it (and ends page follow); Shift + tap the page
     * shown toggles follow, Shift + tap another copies the page shown there.
     */
    fun pageTap(target: Int) {
        if (!shift.shifted) {
            pageFollow.stop()
            followPage = false
            page = target
            return
        }
        if (target == page) {
            pageFollow.toggle()
            followPage = pageFollow.on
            flash(if (followPage) "page follows the playhead" else "page follow off")
            return
        }
        val from = page
        if (patternEdit { PatternEdits.pageCopied(it, from, target) }) flash("page ${from + 1} → ${target + 1}")
    }

    /** Pages of the edited pattern holding a step (the rail's dots). */
    fun pageHasNotes(p: Int): Boolean {
        revision
        return (p * PADS until (p + 1) * PADS).any { store.step(selectedSlot, editPattern, it).on }
    }

    /** Steps the rate's beat spans (pad and cell edges). */
    val beatSteps: Int
        get() = StepGrid.beatSteps(settings().rate)

    /** At 1/16T: the Steps the footer offers (12 per bar), or null. */
    val tripletOffer: Int?
        get() = settings().let { StepGrid.tripletOffer(it.rate, it.length) }

    fun acceptTripletOffer() {
        val steps = tripletOffer ?: return
        val slot = selectedSlot
        val pattern = editPattern
        history.edit(slot, pattern) { store.updateSettings(slot, pattern) { it.copy(length = steps) } }
    }

    /**
     * Session swing (build-22, the Swing chip on the Shift layer): the edited pattern's Shuffle
     * goes to every slot's playing and edited pattern and every pattern holding content; one
     * undo step.
     */
    fun swingAll() {
        val shuffle = settings().shuffle
        val token = history.begin()
        for (s in 0 until SeqLimits.SLOTS) {
            for (p in 0 until SeqLimits.PATTERNS) {
                if (p != patternOf(s) && p != editPatternOf(s) && !store.hasContent(s, p)) continue
                history.edit(s, p) { store.updateSettings(s, p) { it.copy(shuffle = shuffle) } }
            }
        }
        history.end(token)
        flash("shuffle ${(shuffle * 100).roundToInt()}% on every slot")
    }

    /**
     * The Seq screen's Clear: the first tap arms it, a second tap within 2 s clears the edited
     * pattern's notes (build-22: Steps, Rate, Gate, Shuffle and motion stay; no dialog,
     * undoable). Shift + Clear on the drum slot clears only the lane's sound; on a synth slot
     * it resets the whole pattern (notes, motion and settings).
     */
    fun clearPress() {
        val slot = selectedSlot
        val pattern = editPattern
        val mode = when {
            !shift.shifted -> ClearMode.Notes
            isDrum(slot) -> ClearMode.Lane
            else -> ClearMode.Reset
        }
        if (!clearConfirm.tap(clearTarget(mode), nowMs)) {
            clearArmed = true
            clearArmedMode = mode
            clearArmedLane = mode == ClearMode.Lane
            return
        }
        clearArmed = false
        when (mode) {
            ClearMode.Lane -> {
                val note = laneNote
                history.edit(slot, pattern) { store.clearLane(slot, pattern, note) }
            }
            ClearMode.Notes -> {
                leaveStepEditing()
                patternEdit { PatternEdits.notesCleared(it) }
            }
            ClearMode.Reset -> {
                leaveStepEditing()
                history.edit(slot, pattern) { store.clearPattern(slot, pattern) }
            }
        }
    }

    /** What Clear clears (build-22). */
    enum class ClearMode { Notes, Lane, Reset }

    /** What the armed Clear will clear. */
    var clearArmedMode by mutableStateOf(ClearMode.Notes)
        private set

    /** What an armed Clear would clear: the pattern's notes, its lane's sound, or all of it. */
    private fun clearTarget(mode: ClearMode): Int =
        (selectedSlot * SeqLimits.PATTERNS + editPattern) * 256 + when (mode) {
            ClearMode.Notes -> 0
            ClearMode.Lane -> laneKey + 1
            ClearMode.Reset -> 255
        }

    /** Once per display frame: the engine's transport and recorded writes. */
    fun frame(transport: TransportState, recorded: List<RecordedEvent>, frameTimeNanos: Long) {
        nowMs = frameTimeNanos / 1_000_000
        playing = transport.playing
        if (transport.tempoBpm >= SeqLimits.MIN_TEMPO) tempoBpm = transport.tempoBpm
        var patternsMoved = false
        for (s in 0 until SeqLimits.SLOTS) {
            currentStep[s].intValue = transport.currentStep.getOrElse(s) { -1 }
            val pattern = transport.currentPattern.getOrElse(s) { 0 }.coerceIn(0, SeqLimits.PATTERNS - 1)
            val queued = transport.queuedPattern.getOrElse(s) { -1 }
            if (pattern != currentPattern[s].intValue || queued != queuedPattern[s].intValue) patternsMoved = true
            val editedBefore = editPatternOf(s)
            currentPattern[s].intValue = pattern
            queuedPattern[s].intValue = queued
            // Build-22: the pattern picked to edit started playing: edit it as the playing one.
            if (editPin[s].intValue == pattern) editPin[s].intValue = -1
            // A switch changed the pattern the shown slot edits: the selection was another pattern's
            // (one picked on the row and now playing keeps it).
            if (s == selectedSlot && editPatternOf(s) != editedBefore) deselectSteps()
            // A chain keeps its other pattern queued (the engine switches at the pattern's end).
            if (transport.playing) {
                chains[s].value?.queueFor(pattern, queued)?.let { NativeEngine.selectPattern(s, it) }
            }
        }
        if (patternsMoved) sessionChanged()
        applyRecorded(recorded)
        if (take.frameApplied(transport.recordArmed)) history.end(takeToken)
        blinkOn = (frameTimeNanos / BLINK_NS) % 2 == 0L
        if (clearArmed && !clearConfirm.isArmed(clearTarget(clearArmedMode), nowMs)) clearArmed = false
        // Build-22: page follow, while the pattern shown is the one playing.
        if (screen == Screen.Seq) {
            pageFollow.pageFor(stepOf(selectedSlot), !editingOther)?.let { if (it != pageState) pageState = it }
        }
        if (seqFlash != null && nowMs >= seqFlashUntil) seqFlash = null
    }

    // --- tape (F5) ---

    fun selectTrack(track: Int) = uiEdit {
        if (track in 0 until MixLimits.TRACKS) tapeTrack = track
    }

    fun trackHasAudio(track: Int): Boolean = trackHasAudio.getOrNull(track)?.value == true

    val tapeStatus: TapeRecordStatus
        get() = TapeView.status(tapeArmedTrack, tapeRecordingTrack)

    /** The engine ignores an arm unless the tape runs at 1x forward, at the tape's tempo. */
    val tapeCanRecord: Boolean
        get() = TapeView.canRecord(mix.mix(MixKeys.SPEED).roundToInt(), mix.mix(MixKeys.REVERSE) >= 0.5, tapeTempoMatches)

    /** Why Record can't record (the Tape footer), or null. */
    val tapeRecordNeeds: String?
        get() = TapeView.recordNeeds(
            mix.mix(MixKeys.SPEED).roundToInt(),
            mix.mix(MixKeys.REVERSE) >= 0.5,
            tapeTempoMatches,
            tapeTempoBpm,
        )

    /** Record on the Tape screen: arms the selected track, or stops (or cancels an arm). */
    fun tapeRecordPress() {
        when (val action = TapeView.recordPress(tapeStatus, tapeTrack, tapeCanRecord)) {
            is TapeRecordAction.Arm -> NativeEngine.tapeArmRecord(action.track)
            TapeRecordAction.Stop -> NativeEngine.tapeStopRecord()
            TapeRecordAction.None -> Unit
        }
    }

    /** Double-tap a tape lane (ADR 0003): arm it, or stop its take; see [TapeView.laneDoubleTap]. */
    fun tapeLaneDoubleTap(lane: Int) {
        // Build-21: a double-tap on a lane asking "Clear T3?" only cancels the ask (no clear, no
        // record); only a single tap confirms. A double-tap on another lane was already a cancel
        // (its touch-down) and then records as usual.
        val swallowed = tapeClear.doubleTap(lane)
        syncTapeClear()
        if (swallowed) return
        val status = tapeStatus
        val busy = if (status == TapeRecordStatus.Recording) tapeRecordingTrack else tapeArmedTrack
        when (val action = TapeView.laneDoubleTap(lane, status, busy, tapeCanRecord)) {
            is TapeRecordAction.Arm -> {
                selectTrack(action.track)
                NativeEngine.tapeArmRecord(action.track)
            }
            TapeRecordAction.Stop -> NativeEngine.tapeStopRecord()
            TapeRecordAction.None -> Unit
        }
    }

    /** The top bar's Undo button (build-21): a tap undoes, a long-press redoes; Shift plays no part. */
    fun tapeUndo() = NativeEngine.tapeUndo()

    fun tapeRedo() = NativeEngine.tapeRedo()

    /**
     * Commit loop (F5.6) onto the selected track: the selected slot's current pattern, or
     * with [allSlots] the full mix. A commit records while playing, so it starts the
     * transport when stopped; it begins at the slot's next pattern boundary.
     */
    fun commit(allSlots: Boolean) {
        if (!playing) NativeEngine.play()
        NativeEngine.tapeCommit(tapeTrack, if (allSlots) -1 else selectedSlot)
    }

    // --- tape lane clear asks first (build-21) ---

    private val tapeClear = TapeClearConfirm()

    /** The lane showing "Clear T3?" (held, waiting for the confirming tap), or -1. */
    var tapeClearLane by mutableIntStateOf(-1)
        private set

    private fun syncTapeClear() {
        tapeClearLane = tapeClear.armedLane(nowMs) ?: -1
    }

    /** Touch-down on a lane: selects its track; a touch on another lane cancels an armed clear. */
    fun tapeLaneDown(lane: Int) {
        selectTrack(lane)
        tapeClear.down(lane, nowMs)
        syncTapeClear()
    }

    /** A hold on a lane: asks "Clear T3?" when it has audio. Returns true when it asked. */
    fun tapeLaneHold(lane: Int): Boolean {
        val asked = tapeClear.hold(lane, trackHasAudio(lane), nowMs)
        syncTapeClear()
        return asked
    }

    /**
     * A single tap on a lane: clears it when it was asking at the tap's touch-down (within 3 s of
     * the hold; the tap itself arrives after the double-tap wait). Any other tap cancels.
     */
    fun tapeLaneTap(lane: Int) {
        val confirmed = tapeClear.tap(lane)
        syncTapeClear()
        if (confirmed) clearTrack(lane)
    }

    /** A touch anywhere outside the lanes cancels an armed clear. */
    fun tapeTouchOutsideLanes() {
        tapeClear.cancel()
        syncTapeClear()
    }

    /** Clears one track (undoable; the lanes ask first, see [tapeLaneHold]). */
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
        tapeLoading = t.loading
        tapeTempoBpm = t.tempoBpm
        tapeTempoMatches = t.tempoMatches
        for (i in 0 until MixLimits.TRACKS) trackHasAudio[i].value = t.trackHasAudio.getOrElse(i) { false }
        if (exportStatus != ExportStatus.Idle) pollExport()
        if (tapeClearLane >= 0) syncTapeClear() // the 3 s ran out

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

    // --- export (F7.1, F7.2) ---

    /** Export WAV can start: the tape has audio, it is not still loading, and no export runs. */
    val canExport: Boolean
        get() = tapeBars > 0 && (0 until MixLimits.TRACKS).any(::trackHasAudio) && !tapeLoading &&
            exportStatus !is ExportStatus.Rendering

    /**
     * Tap 1 of 2 (F7.1): renders the loop region (or the whole tape when the loop covers it,
     * see [ExportFiles.wholeTape]) to a WAV in the cache; the share sheet opens when it is
     * done, and picking an app there is tap 2.
     */
    fun exportPress() {
        if (!canExport) return
        val host = exportHost ?: return
        val region = TapeView.loopRegion(
            mix.mix(MixKeys.LOOP_START).roundToInt(),
            mix.mix(MixKeys.LOOP_LEN).roundToInt(),
            tapeBars,
        )
        val whole = ExportFiles.wholeTape(region)
        val path = host.newExportPath()
        if (path == null) {
            exportTracker.fail("export failed · no room for the file", nowMs)
        } else {
            exportTracker.start(NativeEngine.startExport(path, whole), path, whole, nowMs)
        }
        exportStatus = exportTracker.status
    }

    private fun pollExport() {
        val done = exportTracker.poll(NativeEngine.exportProgress(), nowMs)
        if (done != null && exportHost?.share(done) != true) exportTracker.shareFailed(nowMs)
        exportStatus = exportTracker.status
    }

    // --- persistence (F7.4, F7.6) ---

    /** Everything worth saving, as an immutable copy (taken on the main thread). */
    fun snapshotSession(): Session = Session(
        tempo = store.tempo,
        ui = UiSnapshot(
            screen = screen.name,
            selectedSlot = selectedSlot,
            lastSynthSlot = lastSynthSlot,
            page = page,
            tapeTrack = tapeTrack,
            mixerPad = MixerLayout.shortName(mixer.selected),
            keyOctave = keyOctave,
        ),
        synths = synths.map { it.snapshot() },
        drum = drum.snapshot(),
        mix = mix.mixSnapshot(),
        channels = mix.channelSnapshot(),
        patterns = store.snapshot(),
        // A queued switch counts as made: it is what the player chose.
        currentPatterns = List(SeqLimits.SLOTS) { s -> queuedOf(s).takeIf { it >= 0 } ?: patternOf(s) },
        locks = mapOf(LOCKS_SEQ to seq.locks.keys, LOCKS_TAPE to tape.locks.keys, LOCKS_MIXER to mixer.locks.keys),
        keyScale = keyScale,
        userPresets = userPresets.snapshot,
    )

    /**
     * Once per process, before the stream starts (F7.6, F0.1): the saved [session] goes to the
     * engine (sounds, kit, mixer, patterns, tempo, current patterns) and the screens show it;
     * with no session (first launch, or no intact save) the slots get the launch presets and
     * the demo, exactly as before M4. Changes made here are not reported to [onSessionChange].
     */
    fun launch(session: Session?) {
        if (launched) return
        launched = true
        if (session == null) seedDemo() else restore(session)
        soundHistory.clear() // the launch's preset loads are not undo steps
        soundHistoryChanged()
    }

    private fun seedDemo() {
        val presets = DemoContent.presetIndexes(synths.first().presetNames)
        synths.forEachIndexed { i, s -> presets.getOrNull(i)?.let { s.loadPreset(it, record = false) } ?: s.refreshFromEngine() }
        drum.refreshFromEngine()
        mix.refreshFromEngine()
        mix.seedSends(::instrumentName)
        DemoContent.write(store)
        tempoBpm = store.tempo
    }

    private fun restore(s: Session) {
        val hook = onSessionChange
        onSessionChange = null
        try {
            store.setTempo(s.tempo, force = true)
            tempoBpm = store.tempo
            keyScale = s.keyScale
            keyOctaveState = KeyOctave.clamp(s.ui.keyOctave)
            userPresets.restore(s.userPresets) // before the synths: a slot may be on a user preset
            synths.forEachIndexed { i, synth -> s.synths.getOrNull(i)?.let(synth::restore) ?: synth.refreshFromEngine() }
            drum.restore(s.drum)
            mix.restore(s.mix, s.channels)
            store.restore(s.patterns)
            for (slot in 0 until SeqLimits.SLOTS) {
                val p = s.currentPatterns.getOrElse(slot) { 0 }.coerceIn(0, SeqLimits.PATTERNS - 1)
                if (p != 0) NativeEngine.selectPattern(slot, p) // stopped, so it applies at once
                currentPattern[slot].intValue = p
            }

            val ui = s.ui
            lastSynthSlot = ui.lastSynthSlot.coerceIn(1, SeqLimits.SLOTS - 1)
            selectedSlot = ui.selectedSlot.coerceIn(0, SeqLimits.SLOTS - 1)
            screen = Screen.entries.firstOrNull { it.name == ui.screen } ?: Screen.Synth
            when {
                screen == Screen.Drum -> selectedSlot = NativeEngine.DRUM_SLOT
                screen == Screen.Synth && isDrum(selectedSlot) -> selectedSlot = lastSynthSlot
            }
            if (!isDrum(selectedSlot)) lastSynthSlot = selectedSlot
            pageState = ui.page.coerceIn(0, PAGES - 1)
            tapeTrack = ui.tapeTrack.coerceIn(0, MixLimits.TRACKS - 1)
            mixer.restoreSelection(MixerLayout.padNamed(ui.mixerPad) ?: MixerPad.Slot(1))
            seq.locks.restore(s.locks[LOCKS_SEQ].orEmpty())
            tape.locks.restore(s.locks[LOCKS_TAPE].orEmpty())
            mixer.locks.restore(s.locks[LOCKS_MIXER].orEmpty())
            history.clear()
        } finally {
            onSessionChange = hook
        }
    }

    // --- lifecycle ---

    /**
     * After the stream starts. The first time in the process (if the activity did not call
     * [launch]) it seeds the demo; later starts re-read the sounds from the engine.
     */
    fun onStart() {
        if (!launched) launch(null)
        if (!startedOnce) {
            startedOnce = true
            return
        }
        synths.forEach { it.refreshFromEngine() }
        drum.refreshFromEngine()
        mix.refreshFromEngine()
    }

    /** Before the stream stops: let go of everything held. */
    fun onStop() {
        if (motionHeld) recordCancel()
        clearLoop()
        NativeEngine.allNotesOff()
        heldNoteSlot.clear()
        noteMemory.releaseAll()
        endKeyGroup()
        if (take.forceClose()) history.end(takeToken)
    }

    private companion object {
        const val PADS = 16
        const val PAGES = 4
        const val LOCKS_SEQ = "seq"
        const val LOCKS_TAPE = "tape"
        const val LOCKS_MIXER = "mixer"
        const val DEFAULT_SYNTH_NOTE = 60
        const val PEN_DEFAULT_LOW = 48
        const val PEN_MIN_LOW = 24
        const val PEN_MAX_LOW = 72
        const val FLASH_MS = 1500L
        const val BLINK_NS = 300_000_000L

        /** Overview refresh when nothing is recording: about 6 times a second at 60 Hz. */
        const val OVERVIEW_IDLE_FRAMES = 10
    }
}
