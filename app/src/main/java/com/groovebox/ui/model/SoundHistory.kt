package com.groovebox.ui.model

/** Whose sound an edit changed: a synth slot (engine slot 1..7) or one kit sound (0..15). */
sealed interface SoundTarget {
    data class Synth(val slot: Int) : SoundTarget
    data class Drum(val key: Int) : SoundTarget
}

/** One parameter's value before and after an edit, by parameter key. */
data class ParamChange(val key: String, val before: Double, val after: Double)

/**
 * Everything a synth slot's sound is: where it came from ([preset]), its values, the preset's
 * own values (what a double-tap returns to) and the values right after the last load (what the
 * "edited" mark compares with). Immutable.
 */
data class SynthSound(
    val preset: PresetRef,
    val values: Map<String, Double>,
    val presetValues: Map<String, Double>,
    val loadValues: Map<String, Double>,
)

/** A slot's preset: a factory one ([factoryIndex] >= 0), a user one ([user]), or none ("Init"). */
data class PresetRef(val name: String, val factoryIndex: Int = -1, val user: Boolean = false) {
    companion object {
        val INIT = PresetRef("Init")
    }
}

/** One undo step of the sound history. */
sealed interface SoundEdit {
    val target: SoundTarget

    /** Strip edits: one drag, double-tap or tap cell. */
    data class Params(override val target: SoundTarget, val changes: List<ParamChange>) : SoundEdit

    /** A whole-sound change of a synth slot: a preset load, a copy from another slot, a save. */
    data class Whole(override val target: SoundTarget.Synth, val before: SynthSound, val after: SynthSound) : SoundEdit
}

/**
 * The sound undo history (build-22), one for the synth slots and the kit together: one strip
 * drag is one step ([begin] at the first finger down, [end] at the last finger up, keyed by the
 * strip so two strips dragged at once are two steps). [undo] and [redo] hand back the edit to
 * revert or re-apply; the caller applies it. At most [depth] steps; a new edit drops the redo.
 * Per app run. Pure Kotlin, main thread only.
 */
class SoundHistory(val depth: Int = 64) {
    private val done = ArrayDeque<SoundEdit>()
    private val undone = ArrayDeque<SoundEdit>()
    private val open = HashMap<Any, Pair<SoundTarget, Map<String, Double>>>()

    /** Bumped on every change, so a UI can follow [canUndo] / [canRedo]. */
    var revision = 0L
        private set

    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    fun record(edit: SoundEdit) {
        done.addLast(edit)
        while (done.size > depth) done.removeFirst()
        undone.clear()
        revision++
    }

    /** A gesture on strip [id] starts on [target]; [before] holds every value the strip can move. */
    fun begin(id: Any, target: SoundTarget, before: Map<String, Double>) {
        open[id] = target to before
    }

    /** The gesture on [id] ended with [after]: records the values that moved as one step. Returns true if it did. */
    fun end(id: Any, after: Map<String, Double>): Boolean {
        val (target, before) = open.remove(id) ?: return false
        val changes = before.mapNotNull { (key, b) ->
            val a = after[key] ?: return@mapNotNull null
            if (a == b) null else ParamChange(key, b, a)
        }
        if (changes.isEmpty()) return false
        record(SoundEdit.Params(target, changes))
        return true
    }

    /** The edit to revert (apply its "before"), or null. */
    fun undo(): SoundEdit? {
        val e = done.removeLastOrNull() ?: return null
        undone.addLast(e)
        revision++
        return e
    }

    /** The edit to apply again (its "after"), or null. */
    fun redo(): SoundEdit? {
        val e = undone.removeLastOrNull() ?: return null
        done.addLast(e)
        revision++
        return e
    }

    fun clear() {
        done.clear()
        undone.clear()
        open.clear()
        revision++
    }
}
