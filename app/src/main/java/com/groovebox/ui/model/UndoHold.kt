package com.groovebox.ui.model

/**
 * An Undo button that never turns into Redo by accident (build-21): Shift plays no part; a tap
 * (released before [longPressMs]) undoes, a long-press redoes once, at the moment it becomes a
 * long press, and its release does nothing more. The button reads "Redo" from then until
 * [flashMs] after the redo. Pure Kotlin; times are monotonic ms.
 */
class UndoHold(val longPressMs: Long = 500, val flashMs: Long = 700) {
    enum class Action { Undo, Redo }

    private var downAt = -1L
    private var redone = false
    private var redoAt = Long.MIN_VALUE

    fun down(now: Long) {
        downAt = now
        redone = false
    }

    /** While held: [Action.Redo] once, when the hold reaches [longPressMs]. */
    fun poll(now: Long): Action? {
        if (downAt < 0 || redone || now - downAt < longPressMs) return null
        redone = true
        redoAt = now
        return Action.Redo
    }

    /** The release: [Action.Undo] for a tap, nothing after a redo (a missed [poll] still redoes). */
    fun up(now: Long): Action? {
        if (downAt < 0) return null
        val action = if (redone) null else poll(now) ?: Action.Undo
        downAt = -1L
        return action
    }

    fun cancel() {
        downAt = -1L
        redone = false
    }

    /** The button's label reads "Redo" (just redone, or still held after redoing). */
    fun readsRedo(now: Long): Boolean = redoAt != Long.MIN_VALUE && (downAt >= 0 && redone || now - redoAt in 0..flashMs)
}
