package com.groovebox.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.groovebox.engine.NativeEngine
import com.groovebox.engine.ParamSpec
import com.groovebox.storage.DrumSnapshot
import com.groovebox.ui.model.DrumLayout
import com.groovebox.ui.model.DrumLockKeys
import com.groovebox.ui.model.DrumReplay
import com.groovebox.ui.model.SeqLimits
import com.groovebox.ui.model.SoundEdit
import com.groovebox.ui.model.SoundHistory
import com.groovebox.ui.model.SoundTarget

/**
 * UI state of the drum kit (slot 1, engine slot 0; F2.2). Every key has its own value of each
 * drum parameter; the strips edit the key played (or touched) last. Values are re-read from
 * the engine after start. [onChange] runs after anything worth saving changes (autosave).
 * Main thread only.
 *
 * Build-22: locks are per sound ([DrumLockKeys]); a strip gesture keeps editing the sound it
 * started on and is one step of the shared sound [history]; a double-tap returns to the kit's
 * own value; a Decay / Start / Rev change replays the sound when the gesture ends
 * ([DrumReplay]; [playing] and [inPattern] say whether the beat will replay it anyway).
 */
@Stable
class DrumController(
    private val shift: ShiftController,
    private val onEdit: () -> Unit = {},
    private val onChange: () -> Unit = {},
    private val history: SoundHistory = SoundHistory(),
    private val onHistory: () -> Unit = {},
    private val playing: () -> Boolean = { false },
    private val inPattern: (key: Int) -> Boolean = { false },
) : StripHost {
    val params: List<ParamSpec> = NativeEngine.drumParams
    val soundNames: List<String> = NativeEngine.drumSoundNames

    private val byKey = params.associateBy { it.key }
    private val values = List(DrumLayout.KEY_COUNT) { params.map { mutableDoubleStateOf(it.default) } }
    private val locks = StripLocks(onChange)

    /** The kit as the engine made it (the first read): what a double-tap returns to. */
    private var kitValues: List<DoubleArray>? = null

    /** The sound each open strip gesture edits, by parameter key (pinned at its first finger). */
    private val gestureKey = HashMap<String, Int>()

    /** The kit key (0..15) the strips edit. */
    var selectedKey by mutableIntStateOf(0)
        private set

    val shifted: Boolean
        get() = shift.shifted

    val strips: List<ParamSpec?>
        get() = DrumLayout.keys(shifted).map { byKey[it] }

    /** Short key labels for the keyboard, by MIDI note. */
    val keyLabels: Map<Int, String> by lazy {
        (0 until DrumLayout.KEY_COUNT).associate { key -> DrumLayout.noteOf(key) to DrumLayout.shortName(key) }
    }

    private val heldPads = mutableStateMapOf<Int, Unit>()

    /** A Drum pad (build-21) is held: it lights while held. Set by the pads only. */
    fun padHeld(key: Int, held: Boolean) {
        if (held) heldPads[key] = Unit else heldPads.remove(key)
    }

    fun isPadHeld(key: Int): Boolean = key in heldPads

    fun soundName(key: Int): String = soundNames.getOrNull(key)?.takeIf { it.isNotBlank() } ?: "Sound ${key + 1}"

    fun spec(key: String): ParamSpec? = byKey[key]

    fun select(key: Int) {
        if (key !in 0 until DrumLayout.KEY_COUNT || key == selectedKey) return
        selectedKey = key
        onChange()
    }

    fun value(key: Int, spec: ParamSpec): Double = values[key][spec.id].doubleValue

    fun value(name: String): Double = byKey[name]?.let(::value) ?: 0.0

    override fun value(spec: ParamSpec): Double = value(selectedKey, spec)

    override fun setValue(spec: ParamSpec, value: Double) = set(gestureKey[spec.key] ?: selectedKey, spec, value)

    private fun set(key: Int, spec: ParamSpec, value: Double) {
        val v = value.coerceIn(spec.min, spec.max)
        onEdit()
        val state = values[key][spec.id]
        if (state.doubleValue == v) return
        state.doubleValue = v
        NativeEngine.setDrumParam(key, spec.id, v)
        onChange()
    }

    /** A double-tap: the kit's own value for this sound (build-22). */
    override fun reset(spec: ParamSpec) {
        val key = gestureKey[spec.key] ?: selectedKey
        set(key, spec, kitValues?.getOrNull(key)?.getOrNull(spec.id) ?: spec.default)
    }

    override fun isLocked(spec: ParamSpec): Boolean = locks.isLocked(DrumLockKeys.of(selectedKey, spec.key))

    override fun setLocked(spec: ParamSpec, locked: Boolean) =
        locks.set(DrumLockKeys.of(gestureKey[spec.key] ?: selectedKey, spec.key), locked)

    // --- sound undo and replay (build-22) ---

    override fun gestureStart(spec: ParamSpec) {
        val key = selectedKey
        gestureKey[spec.key] = key
        history.begin(gestureId(spec), SoundTarget.Drum(key), mapOf(spec.key to value(key, spec)))
    }

    override fun gestureEnd(spec: ParamSpec) {
        val key = gestureKey.remove(spec.key) ?: return
        val changed = history.end(gestureId(spec), mapOf(spec.key to value(key, spec)))
        if (changed) onHistory()
        if (DrumReplay.onRelease(spec.key, changed, playing(), inPattern(key))) replay(key)
    }

    private fun gestureId(spec: ParamSpec) = "drum:${spec.key}"

    /** A Rev / Choke tap cell (build-22): one undo step, and Rev replays the sound. */
    fun tapCell(spec: ParamSpec, option: Int) {
        if (isLocked(spec)) return
        gestureStart(spec)
        setValue(spec, option.toDouble())
        gestureEnd(spec)
    }

    /** Plays [key] for the ear, never recorded (auditionOn); the kit ignores its note-off. */
    private fun replay(key: Int) {
        val note = DrumLayout.noteOf(key)
        NativeEngine.auditionOn(NativeEngine.DRUM_SLOT, note, SeqLimits.DEFAULT_VELOCITY)
        NativeEngine.noteOff(NativeEngine.DRUM_SLOT, note)
    }

    /** An undo ([undo]) or redo of [edit] on its sound, which becomes the selected one. */
    fun applyEdit(edit: SoundEdit, undo: Boolean) {
        val key = (edit.target as? SoundTarget.Drum)?.key ?: return
        if (edit !is SoundEdit.Params) return
        select(key)
        for (c in edit.changes) byKey[c.key]?.let { set(key, it, if (undo) c.before else c.after) }
    }

    fun refreshFromEngine() {
        for (key in 0 until DrumLayout.KEY_COUNT) {
            for (p in params) values[key][p.id].doubleValue = NativeEngine.getDrumParam(key, p.id)
        }
        if (kitValues == null) {
            kitValues = List(DrumLayout.KEY_COUNT) { key -> DoubleArray(params.size) { values[key][it].doubleValue } }
        }
    }

    fun snapshot() = DrumSnapshot(
        selectedKey = selectedKey,
        values = List(DrumLayout.KEY_COUNT) { key -> params.associate { it.key to values[key][it.id].doubleValue } },
        locks = locks.keys,
    )

    /** A saved kit (F7.6): saved values go to the engine; missing ones keep the engine's. No [onChange]. */
    fun restore(saved: DrumSnapshot) {
        refreshFromEngine()
        for (key in 0 until minOf(DrumLayout.KEY_COUNT, saved.values.size)) {
            for (p in params) {
                val v = saved.values[key][p.key] ?: continue
                val clamped = v.coerceIn(p.min, p.max)
                values[key][p.id].doubleValue = clamped
                NativeEngine.setDrumParam(key, p.id, clamped)
            }
        }
        selectedKey = saved.selectedKey.coerceIn(0, DrumLayout.KEY_COUNT - 1)
        // Per sound since session version 4 (the codec migrates older kit-wide locks).
        locks.restore(saved.locks)
    }
}
