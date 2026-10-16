package com.groovebox.ui.model

import com.groovebox.engine.NativeEngine
import kotlin.math.abs

/**
 * A horizontal swipe on the display steps through the sound slots (ADR 0003), so the left
 * thumb can change slot without reaching the slot buttons at the top centre. Like turning
 * pages: the finger moving left goes to the next slot, right to the previous one. Every
 * [stepPx] of travel is one slot, so a longer swipe moves further. Main thread only.
 */
class SlotSwipe(private val stepPx: Float) {
    private var travel = 0f

    /** Call when a swipe starts. */
    fun reset() {
        travel = 0f
    }

    /** Adds a drag of [dx] px (positive = rightwards); returns how many slots to move (+ = next). */
    fun drag(dx: Float): Int {
        travel -= dx
        var steps = 0
        while (abs(travel) >= stepPx) {
            val dir = if (travel > 0) 1 else -1
            steps += dir
            travel -= dir * stepPx
        }
        return steps
    }

    companion object {
        /**
         * The slot [steps] away from [current], clamped (no wrap-around, so a swipe never jumps
         * from the last slot to the first). Without [includeDrum] it stays on the synth slots:
         * the Synth screen never swipes over to the Drum screen.
         */
        fun target(current: Int, steps: Int, includeDrum: Boolean): Int {
            val first = if (includeDrum) 0 else NativeEngine.DRUM_SLOT + 1
            return (current + steps).coerceIn(first, SeqLimits.SLOTS - 1)
        }
    }
}
