package com.groovebox.ui.model

/**
 * One drag of the Seq screen's Shift-layer Note strip over the selected synth steps
 * (build-21 item 1). The drag is relative: [start] takes the steps as they are, and
 * [moveTo] moves every note of every one of them by the same offset from there, in
 * semitones, or in scale degrees while a scale is on ([KeyScale.moveByDegrees]), so a
 * bassline keeps its line and a chord its shape.
 *
 * - Empty steps take no part; with none left the drag does nothing ([canMove]).
 * - Notes stay inside [low]..[high]: an offset that would push any note past them stops the
 *   whole move at the furthest offset that fits ([offset] is the one applied). A note already
 *   outside the range is only kept from moving further out.
 * - With a scale on, an out-of-key note that would land on a pitch another note of its step
 *   takes is moved to the nearest free pitch (above on a tie), so a step never loses a note.
 * - Each hit keeps its velocity, length, chance and nudge.
 *
 * Pure Kotlin, main thread only.
 */
class NoteDrag(private val low: Int = SeqStrips.NOTE_LOW, private val high: Int = SeqStrips.NOTE_HIGH) {
    private var steps: Map<Int, Step>? = null

    /** The offset the last [moveTo] applied; 0 between drags and where a drag started. */
    var offset: Int = 0
        private set

    /** A drag is open ([start] without [end]). */
    val active: Boolean
        get() = steps != null

    /** The open drag has notes to move. */
    val canMove: Boolean
        get() = !steps.isNullOrEmpty()

    /** Opens a drag over [selected] (step index → step as it is now). */
    fun start(selected: Map<Int, Step>) {
        steps = selected.filterValues { it.on }
        offset = 0
    }

    /**
     * The steps of the open drag moved by [requested] from where it started, as far as the
     * range lets them; empty when no drag is open or there is nothing to move.
     */
    fun moveTo(requested: Int, key: KeyScale): Map<Int, Step> {
        val from = steps ?: return emptyMap()
        if (from.isEmpty()) return emptyMap()
        var o = requested
        while (true) {
            val moved = from.mapValues { (_, s) -> move(s, o, key) }
            if (o == 0 || moved.all { (i, s) -> fits(from.getValue(i), s) }) {
                offset = o
                return moved
            }
            o -= Integer.signum(o)
        }
    }

    /** Double-tap on the strip: back to where the drag started (no change). */
    fun reset(key: KeyScale): Map<Int, Step> = moveTo(0, key)

    fun end() {
        steps = null
        offset = 0
    }

    private fun fits(before: Step, after: Step): Boolean =
        before.hits.zip(after.hits).all { (b, a) -> a.note in minOf(low, b.note)..maxOf(high, b.note) }

    private fun move(step: Step, by: Int, key: KeyScale): Step {
        if (by == 0) return step
        val notes = step.notes
        val targets = IntArray(notes.size)
        val taken = HashSet<Int>()
        // In-key notes first: their degree moves never collide with each other.
        for ((i, n) in notes.withIndex()) {
            if (!key.isOn || key.inKey(n)) {
                targets[i] = key.moveByDegrees(n, by)
                taken += targets[i]
            }
        }
        for ((i, n) in notes.withIndex()) {
            if (key.isOn && !key.inKey(n)) {
                targets[i] = free(key.moveByDegrees(n, by), taken)
                taken += targets[i]
            }
        }
        return Step(step.hits.mapIndexed { i, h -> h.copy(note = targets[i]) })
    }

    /** [note], or the nearest pitch to it inside the range and not in [taken] (above on a tie). */
    private fun free(note: Int, taken: Set<Int>): Int {
        if (note !in taken) return note
        for (d in 1..high - low) {
            val up = note + d
            if (up !in taken && up in low..high) return up
            val down = note - d
            if (down !in taken && down in low..high) return down
        }
        return note // a range narrower than the chord: [fits] rejects the move
    }
}
