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

    /** Shift + one pad tapped (build-22): paste, fill or accent, as the screen decides. */
    data class ShiftTap(val step: Int) : PadAction

    /** A pad dragged onto another (build-22): move [from]'s hit there, or with [copy] (Shift + hold, drag) copy it. */
    data class Move(val from: Int, val to: Int, val copy: Boolean) : PadAction

    /** Shift + a swipe across the pads (build-22) crossed [by] pads (negative = leftwards): rotate, or insert / delete. */
    data class Swipe(val by: Int) : PadAction

    /** The swipe's finger lifted. */
    data object SwipeEnd : PadAction
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
 * - **Shift + one pad tapped** (no loop formed): [PadAction.ShiftTap] on release (build-22:
 *   paste, fill or accent; it no longer toggles the selection).
 * - **Drag** (build-22): a finger that leaves its pad past the touch slop never toggles
 *   (a drifting long-press cancels); lifted on another pad it asks for [PadAction.Move]. With
 *   Shift, a finger held still for the long-press time picks the step up and its drop is a
 *   copy; one that moves first is a swipe, [PadAction.Swipe] for every pad it crosses, then
 *   [PadAction.SwipeEnd]. Pads touched to join a selection never drag.
 *
 * The selection survives [cancel]; [clearSelection] empties it. Main thread only.
 */
class StepPads {
    private enum class Kind { Pending, Selecting, Done, Dragging, Swiping, Carrying }

    private class Held(val step: Int, val shifted: Boolean, var kind: Kind = Kind.Pending) {
        /** The pad under the finger now, or null off the pads. */
        var current: Int? = step
        var lastSwipe = step
    }

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
        if (h.kind != Kind.Pending) return null
        if (h.shifted) {
            if (!loopGesture) h.kind = Kind.Carrying
            return null
        }
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
        val to = h.current
        return when (h.kind) {
            Kind.Pending -> if (h.shifted) PadAction.ShiftTap(h.step) else PadAction.Toggle(h.step)
            Kind.Swiping -> PadAction.SwipeEnd
            Kind.Carrying -> if (to != null && to != h.step) PadAction.Move(h.step, to, copy = true) else PadAction.ShiftTap(h.step)
            Kind.Dragging, Kind.Selecting -> if (to != null && to != h.step) PadAction.Move(h.step, to, copy = false) else null
            Kind.Done -> null
        }
    }

    /**
     * The finger [pointerId] is over [step] now (null: off the pads); [drifted] once it has
     * moved past the touch slop since it went down.
     */
    fun move(pointerId: Int, step: Int?, drifted: Boolean): PadAction? {
        val h = held[pointerId] ?: return null
        h.current = step
        if (drifted && h.kind == Kind.Pending) h.kind = if (h.shifted) Kind.Swiping else Kind.Dragging
        if (h.kind != Kind.Swiping || step == null || step == h.lastSwipe) return null
        val by = step - h.lastSwipe
        h.lastSwipe = step
        return PadAction.Swipe(by)
    }

    /** The touch stream was taken away: forget the held pads (and clear any loop). The selection stays. */
    fun cancel(): PadAction? {
        val hadLoop = loopPointers != null
        held.clear()
        loopPointers = null
        loopGesture = false
        return if (hadLoop) PadAction.ClearLoop else null
    }

    /** A tap on [step]'s note in the Seq display's pitch roll (build-21): selects or deselects it, as a long-press would. */
    fun toggleSelection(step: Int): PadAction.Selection {
        if (!selection.remove(step)) selection.add(step)
        return PadAction.Selection(selected)
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
