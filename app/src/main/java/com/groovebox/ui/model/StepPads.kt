package com.groovebox.ui.model

/** What a touch on the step pads asks for. Steps are absolute (0..63), not pad positions. */
sealed interface PadAction {
    /** A tap: add or remove the lane's hit (drum slot), or turn the step on or off (synth slot). */
    data class Toggle(val step: Int) : PadAction

    /** The selection changed; [steps] is all of it, in the order the steps were selected. */
    data class Selection(val steps: List<Int>) : PadAction

    /** Shift + two pads held: loop start..end inclusive (F3.8). */
    data class Loop(val start: Int, val end: Int) : PadAction

    /** The loop's pads were let go. */
    data object ClearLoop : PadAction
}

/**
 * Touch logic of the 16 step pads (build-20, ADR 0003: the left thumb selects, the right
 * thumb edits), as a pure state machine. The screen reports [longPress] once a pad has been
 * held still for the platform's long-press time.
 *
 * - **Tap** (released before the long-press): [PadAction.Toggle].
 * - **Long-press**: selects the step, latched: it stays selected after the finger lifts, and
 *   the release toggles nothing. While that pad is still held, every other pad touched joins
 *   the selection at once. Long-pressing a selected step deselects it instead.
 * - **Shift + two pads held**: a step loop over the range between them, in either order;
 *   releasing either clears it. Other Shift pads touched during a loop do nothing, and no new
 *   loop starts until every pad of that gesture is up.
 * - **Shift + one pad tapped** (no loop formed): toggles that step in the selection on
 *   release (the old Shift + pad select).
 *
 * The selection survives [cancel]; [clearSelection] empties it. Main thread only.
 */
class StepPads {
    private enum class Kind { Pending, Selecting, Done }

    private class Held(val step: Int, val shifted: Boolean, var kind: Kind = Kind.Pending)

    /** The pads held, in touch order. */
    private val held = LinkedHashMap<Int, Held>()
    private val selection = LinkedHashSet<Int>()

    private var loopPointers: Pair<Int, Int>? = null
    private var loopSteps: Pair<Int, Int>? = null

    /** True from the moment a loop forms until every Shift pad of that gesture is up. */
    private var loopGesture = false

    /** The selected steps, in the order they were selected. */
    val selected: List<Int>
        get() = selection.toList()

    val loop: Pair<Int, Int>?
        get() = if (loopPointers != null) loopSteps else null

    fun down(pointerId: Int, step: Int, shifted: Boolean): PadAction? {
        if (shifted) {
            val h = Held(step, shifted = true)
            held[pointerId] = h
            if (loopGesture) {
                h.kind = Kind.Done
                return null
            }
            val others = held.entries.filter { it.key != pointerId && it.value.shifted && it.value.kind == Kind.Pending }
            val other = others.firstOrNull() ?: return null
            loopGesture = true
            other.value.kind = Kind.Done
            h.kind = Kind.Done
            loopPointers = other.key to pointerId
            val a = minOf(other.value.step, step)
            val b = maxOf(other.value.step, step)
            loopSteps = a to b
            return PadAction.Loop(a, b)
        }
        if (held.values.any { it.kind == Kind.Selecting }) {
            held[pointerId] = Held(step, shifted = false, kind = Kind.Done)
            if (!selection.add(step)) return null
            return PadAction.Selection(selected)
        }
        held[pointerId] = Held(step, shifted = false)
        return null
    }

    /** The pad under [pointerId] has been held still for the long-press time. */
    fun longPress(pointerId: Int): PadAction? {
        val h = held[pointerId] ?: return null
        if (h.shifted || h.kind != Kind.Pending) return null
        if (selection.remove(h.step)) {
            h.kind = Kind.Done
        } else {
            selection.add(h.step)
            h.kind = Kind.Selecting
        }
        return PadAction.Selection(selected)
    }

    fun up(pointerId: Int): PadAction? {
        val h = held.remove(pointerId) ?: return null
        val lp = loopPointers
        if (lp != null && (pointerId == lp.first || pointerId == lp.second)) {
            loopPointers = null
            endLoopGestureIfDone()
            return PadAction.ClearLoop
        }
        if (h.shifted && loopGesture) {
            endLoopGestureIfDone()
            return null
        }
        if (h.kind != Kind.Pending) return null
        if (h.shifted) {
            if (!selection.remove(h.step)) selection.add(h.step)
            return PadAction.Selection(selected)
        }
        return PadAction.Toggle(h.step)
    }

    /** The touch stream was taken away: forget the held pads (and clear any loop). The selection stays. */
    fun cancel(): PadAction? {
        val hadLoop = loopPointers != null
        held.clear()
        loopPointers = null
        loopGesture = false
        return if (hadLoop) PadAction.ClearLoop else null
    }

    fun clearSelection() {
        selection.clear()
        // A pad still held keeps its gesture, but no longer adds pads.
        for (h in held.values) if (h.kind == Kind.Selecting) h.kind = Kind.Done
    }

    private fun endLoopGestureIfDone() {
        if (held.values.none { it.shifted }) loopGesture = false
    }
}
