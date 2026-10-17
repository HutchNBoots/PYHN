package com.groovebox.ui.model

/** What a touch on the Seq display's pattern row asks for (build-22). */
sealed interface PatternRowAction {
    /** A tap: edit [pattern] (while playing, without switching), or queue it when it is already the one edited. */
    data class Tap(val pattern: Int) : PatternRowAction

    /** Shift + tap: copy the edited pattern into [pattern]. */
    data class CopyHere(val pattern: Int) : PatternRowAction

    /** A long-press: every slot switches to [pattern] (queued while playing). */
    data class SwitchAll(val pattern: Int) : PatternRowAction

    /** Hold [first] and tap [then]: the slot plays first, then, then first again, and so on. */
    data class Chain(val first: Int, val then: Int) : PatternRowAction
}

/**
 * Touch logic of the pattern row (16 cells), a pure state machine. Actions come on release so a
 * held cell can still become a chain:
 *  - released before [longPressMs]: [PatternRowAction.Tap], or with Shift [PatternRowAction.CopyHere];
 *  - held at least [longPressMs] and released: [PatternRowAction.SwitchAll];
 *  - another cell touched while one is held: [PatternRowAction.Chain] at once, and neither
 *    release then does anything. A third cell during a chain does nothing.
 * Times are milliseconds on one clock. Main thread only.
 */
class PatternRowGesture(private val longPressMs: Long = 500) {
    private class Held(val cell: Int, val downMs: Long, val shifted: Boolean, var spent: Boolean = false)

    private val held = LinkedHashMap<Int, Held>()

    fun down(pointer: Int, cell: Int, shifted: Boolean, timeMs: Long): PatternRowAction? {
        if (cell !in 0 until SeqLimits.PATTERNS) return null
        val first = held.values.firstOrNull()
        val h = Held(cell, timeMs, shifted)
        held[pointer] = h
        if (first == null) return null
        h.spent = true
        if (first.spent) return null // a third cell during a chain
        first.spent = true
        return if (first.cell == cell) null else PatternRowAction.Chain(first.cell, cell)
    }

    fun up(pointer: Int, timeMs: Long): PatternRowAction? {
        val h = held.remove(pointer) ?: return null
        if (h.spent) return null
        return when {
            timeMs - h.downMs >= longPressMs -> PatternRowAction.SwitchAll(h.cell)
            h.shifted -> PatternRowAction.CopyHere(h.cell)
            else -> PatternRowAction.Tap(h.cell)
        }
    }

    /** The cell is still held past [longPressMs] (for the screen's feedback). */
    fun isLongHeld(pointer: Int, timeMs: Long): Boolean = held[pointer]?.let { !it.spent && timeMs - it.downMs >= longPressMs } == true

    fun cancel() = held.clear()
}

/**
 * A chain A → B on one slot (build-22), kept in the UI with the engine's ordinary queued switch
 * (it takes effect at the end of the playing pattern): whenever A plays the slot queues B, and
 * whenever B plays it queues A. [queueFor] says what to queue now, or null.
 */
data class PatternChain(val first: Int, val then: Int) {
    fun queueFor(current: Int, queued: Int): Int? = when (current) {
        first -> if (queued == then) null else then
        then -> if (queued == first) null else first
        else -> if (queued == first) null else first // started elsewhere: go to A at the next boundary
    }
}

/** The step grid's beats and the triplet offer (build-22). Pure Kotlin. */
object StepGrid {
    /** Steps per beat at rate index 0..3 (1/8, 1/16, 1/32, 1/16T): pad and cell edges are drawn every this many steps. */
    fun beatSteps(rate: Int): Int = when (rate) {
        0 -> 2
        2 -> 8
        3 -> 3
        else -> 4
    }

    /** The step [i] starts a beat. */
    fun isBeat(i: Int, rate: Int): Boolean = i % beatSteps(rate) == 0

    /**
     * At 1/16T a length that is not whole bars of triplets (12 steps) gets an offer: the same
     * number of bars (of 16 sixteenths, rounded up) in triplets, 12 per bar (16 → 12, 32 → 24).
     * Null when none is needed.
     */
    fun tripletOffer(rate: Int, length: Int): Int? {
        if (rate != 3 || length % 12 == 0) return null
        return ((length + 15) / 16 * 12).coerceIn(12, 48)
    }
}

/**
 * Page follow (build-22, Shift + tap the page shown): while on, the pads show the page the
 * playhead is on, as long as the pattern shown is the one playing. A page tapped by hand turns
 * it off. Pure Kotlin.
 */
class PageFollow {
    var on: Boolean = false
        private set

    fun toggle() {
        on = !on
    }

    fun stop() {
        on = false
    }

    /** The page to show for the playhead at [step] (−1 = stopped), or null to leave the page alone. */
    fun pageFor(step: Int, showingPlaying: Boolean): Int? =
        if (on && showingPlaying && step >= 0) (step / PatternEdits.PAGE).coerceIn(0, SeqLimits.STEPS / PatternEdits.PAGE - 1) else null
}
