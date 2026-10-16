package com.groovebox.ui.model

/** What a touch on the step pads asks for. Steps are absolute (0..63), not pad positions. */
sealed interface PadAction {
    /** A tap: turn the step on or off (F3.5). */
    data class Toggle(val step: Int) : PadAction

    /** Shift + pad: select this step for editing, or deselect it ([step] null). */
    data class Select(val step: Int?) : PadAction

    /** Two pads held: loop start..end inclusive (F3.8). */
    data class Loop(val start: Int, val end: Int) : PadAction

    /** The loop's pads were let go. */
    data object ClearLoop : PadAction
}

/**
 * Touch logic of the 16 step pads, as a pure state machine (F3.5, F3.8).
 *
 * - Without Shift a pad toggles its step when it is released, unless it took part in a step
 *   loop. Holding a second pad while one is held loops the range between the two (in either
 *   order); releasing either of them clears the loop. Other pads touched while a loop is held do
 *   nothing (their release keeps the loop), and nothing toggles until every pad of that gesture is up.
 * - With Shift a pad selects its step at once, on touch-down; touching the selected step
 *   again deselects it. Each new touch changes the selection.
 *
 * Main thread only.
 */
class StepPads {
    /** The pads held, in touch order: pointer id → step. */
    private val held = LinkedHashMap<Int, Int>()
    private var loopActive = false

    /** The pointers of the two pads that made the loop, and their steps. */
    private var loopPointers: Pair<Int, Int>? = null
    private var loopSteps: Pair<Int, Int>? = null

    /** True from the moment a loop forms until every pad of that gesture is up. */
    private var gestureUsed = false

    /** The selected step (Shift layer), or null. */
    var selected: Int? = null
        private set

    val loop: Pair<Int, Int>?
        get() = if (loopActive) loopSteps else null

    fun down(pointerId: Int, step: Int, shifted: Boolean): PadAction? {
        if (shifted) {
            selected = if (selected == step) null else step
            return PadAction.Select(selected)
        }
        held[pointerId] = step
        if (gestureUsed) return null
        if (held.size == 2) {
            loopActive = true
            gestureUsed = true
            val (pa, pb) = held.keys.toList()
            val (a, b) = held.values.toList()
            loopPointers = pa to pb
            loopSteps = minOf(a, b) to maxOf(a, b)
            return PadAction.Loop(minOf(a, b), maxOf(a, b))
        }
        return null
    }

    fun up(pointerId: Int): PadAction? {
        val step = held.remove(pointerId) ?: return null
        val lp = loopPointers
        if (loopActive && lp != null && (pointerId == lp.first || pointerId == lp.second)) {
            loopActive = false
            loopPointers = null
            if (held.isEmpty()) gestureUsed = false
            return PadAction.ClearLoop
        }
        if (gestureUsed) {
            if (held.isEmpty()) gestureUsed = false
            return null
        }
        return PadAction.Toggle(step)
    }

    /** The touch stream was taken away: forget the held pads (and clear any loop). */
    fun cancel(): PadAction? {
        val hadLoop = loopActive
        held.clear()
        loopActive = false
        loopPointers = null
        gestureUsed = false
        return if (hadLoop) PadAction.ClearLoop else null
    }

    fun select(step: Int?) {
        selected = step
    }

    fun clearSelection() {
        selected = null
    }
}
