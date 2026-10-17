package com.groovebox.ui.model

/**
 * Undo / Redo for the sequencer's edits (build-20), per app run: each step holds copies of the
 * patterns one edit changed, as they were before it. [DEPTH] steps deep.
 *
 * Edits go through [edit], naming the pattern they touch. Between [begin] and [end] (one strip
 * drag, one live-record take from Record on to off, one chord played into selected steps)
 * every edit joins one step; groups nest. Any edit made while a group is open joins it, e.g.
 * a pad tap with the other thumb during a strip drag (accepted: both are one gesture's worth
 * of editing). An edit or group that changes nothing leaves no step.
 *
 * [undo] and [redo] end every open group (it becomes a step first, if it changed anything).
 * The [begin] tokens of those groups go stale: their [end] does nothing, and their later edits
 * are single steps, so a drag that keeps moving after an undo neither merges into nor wipes
 * the redo just made by its lift.
 *
 * Undo and redo put patterns back through [PatternStore.restorePattern], which sends the
 * engine only what differs. Pure Kotlin, main thread only.
 */
class PatternHistory(private val store: PatternStore) {
    private class Entry(val before: List<PatternSnapshot>)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    /** Open group: its nesting depth, its generation (the [begin] token), and the patterns it
     *  touched, as they were when first touched. */
    private var depth = 0
    private var generation = 0
    private val captured = LinkedHashMap<Int, PatternSnapshot>()

    /**
     * Runs before each edit of a pattern, after the history has its copy of it, so whatever
     * it writes (build-22: an empty pattern taking the slot's last settings) joins that edit's
     * undo step.
     */
    var beforeEdit: ((slot: Int, pattern: Int) -> Unit)? = null

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
            beforeEdit?.invoke(slot, pattern)
            val result = action()
            onChange?.invoke()
            return result
        }
        val before = store.patternSnapshot(slot, pattern)
        beforeEdit?.invoke(slot, pattern)
        val result = action()
        if (store.patternSnapshot(slot, pattern) != before) push(Entry(listOf(before)))
        return result
    }

    /** Opens (or nests into) a group; pass the token to [end]. */
    fun begin(): Int {
        depth++
        return generation
    }

    /** Ends the group [token] opened; a stale token (an undo or redo came between) does nothing. */
    fun end(token: Int) {
        if (token != generation || depth == 0) return
        depth--
        if (depth == 0) closeGroup()
    }

    /** Undoes the last step (ending any open group first); false when there is none. */
    fun undo(): Boolean {
        endAllGroups()
        val entry = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(apply(entry))
        onChange?.invoke()
        return true
    }

    fun redo(): Boolean {
        endAllGroups()
        val entry = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(apply(entry))
        onChange?.invoke()
        return true
    }

    private fun endAllGroups() {
        if (depth == 0) return
        depth = 0
        generation++
        closeGroup()
    }

    /** Forgets every step (e.g. a restored session). */
    fun clear() {
        endAllGroups()
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
