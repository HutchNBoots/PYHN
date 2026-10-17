package com.groovebox.ui.model

/**
 * Undo / Redo for the sequencer's edits (build-20), per app run: each step holds copies of the
 * patterns one edit changed, as they were before it. [DEPTH] steps deep.
 *
 * Edits go through [edit], naming the pattern they touch. Between [begin] and [end] (one strip
 * drag, one live-record take from Record on to off, one chord played into selected steps)
 * every edit joins one step; groups nest. An edit or group that changes nothing leaves no step.
 * Undo and redo put whole patterns back through [PatternStore.restorePattern], which re-sends
 * them to the engine. Pure Kotlin, main thread only.
 */
class PatternHistory(private val store: PatternStore) {
    private class Entry(val before: List<PatternSnapshot>)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    /** Open group: the patterns it touched, as they were when first touched. */
    private var depth = 0
    private val captured = LinkedHashMap<Int, PatternSnapshot>()

    /** Called after anything that changes [canUndo] or [canRedo]. */
    var onChange: (() -> Unit)? = null

    val canUndo: Boolean
        get() = undoStack.isNotEmpty() || openGroupChanged()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    /** Runs [action], an edit of [slot]'s [pattern], as an undo step (or part of the open group). */
    fun <T> edit(slot: Int, pattern: Int, action: () -> T): T {
        if (depth > 0) {
            captured.getOrPut(key(slot, pattern)) { store.patternSnapshot(slot, pattern) }
            val result = action()
            onChange?.invoke()
            return result
        }
        val before = store.patternSnapshot(slot, pattern)
        val result = action()
        if (store.patternSnapshot(slot, pattern) != before) push(Entry(listOf(before)))
        return result
    }

    fun begin() {
        depth++
    }

    fun end() {
        if (depth == 0) return
        depth--
        if (depth == 0) closeGroup()
    }

    /** Undoes the last step (closing an open group first); false when there is none. */
    fun undo(): Boolean {
        if (depth > 0) closeGroup()
        val entry = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(apply(entry))
        onChange?.invoke()
        return true
    }

    fun redo(): Boolean {
        if (depth > 0) closeGroup()
        val entry = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(apply(entry))
        onChange?.invoke()
        return true
    }

    /** Forgets every step (e.g. a restored session). */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        captured.clear()
        onChange?.invoke()
    }

    /** Restores [entry]'s patterns; returns the entry that takes them back to now. */
    private fun apply(entry: Entry): Entry {
        val now = entry.before.map { store.patternSnapshot(it.slot, it.pattern) }
        for (snap in entry.before) store.restorePattern(snap)
        return Entry(now)
    }

    private fun closeGroup() {
        val changed = captured.values.filter { store.patternSnapshot(it.slot, it.pattern) != it }
        captured.clear()
        if (changed.isNotEmpty()) push(Entry(changed)) else onChange?.invoke()
    }

    private fun openGroupChanged(): Boolean =
        depth > 0 && captured.values.any { store.patternSnapshot(it.slot, it.pattern) != it }

    private fun push(entry: Entry) {
        undoStack.addLast(entry)
        while (undoStack.size > DEPTH) undoStack.removeFirst()
        redoStack.clear()
        onChange?.invoke()
    }

    private fun key(slot: Int, pattern: Int) = slot * SeqLimits.PATTERNS + pattern

    companion object {
        const val DEPTH = 32
    }
}
