package com.groovebox.ui.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One note bar of the pitch roll, in the page's columns (0..[PitchRoll.COLUMNS], one per step).
 * [step] is the step whose hit it is (absolute, 0..63; for a tail, the step it started on).
 * [head] is set when the bar starts where the note starts (not a tail come in from the left);
 * [runsOff] when the note goes on past the bar's right end (the page edge or the pattern's end);
 * [outside] when the step lies past the pattern's length (not played).
 */
data class RollBar(
    val step: Int,
    val note: Int,
    val start: Float,
    val length: Float,
    val velocity: Float,
    val head: Boolean,
    val runsOff: Boolean,
    val outside: Boolean,
)

/**
 * The Seq display's pitch roll for a synth slot (build-21 item 2,
 * docs/mockups/SeqSynthProposed.dc.html): the current page's 16 steps across, pitch rows up
 * the side. Pure Kotlin.
 */
object PitchRoll {
    const val COLUMNS = 16
    const val PAGES = SeqLimits.STEPS / COLUMNS

    /** Rows when the pattern has no notes: C3..C4. */
    val EMPTY_ROWS = 48..60

    /**
     * The pitch rows (low..high, inclusive) for a pattern playing [notes]: their range padded
     * by [pad] semitones each way and widened, evenly, to at least [minSpan] semitones, kept
     * inside MIDI 0..127.
     */
    fun rows(notes: Collection<Int>, minSpan: Int = 12, pad: Int = 2): IntRange {
        if (notes.isEmpty()) return EMPTY_ROWS
        var lo = notes.min() - pad
        var hi = notes.max() + pad
        val extra = minSpan - (hi - lo)
        if (extra > 0) {
            lo -= extra / 2
            hi += extra - extra / 2
        }
        if (lo < 0) { hi -= lo; lo = 0 }
        if (hi > 127) { lo -= hi - 127; hi = 127 }
        return lo.coerceAtLeast(0)..hi
    }

    /** How long [hit] sounds, in steps: its own length, or the pattern's [gate]. */
    fun lengthOf(hit: Hit, gate: Float): Float = if (hit.length < 0f || hit.length.isNaN()) gate else hit.length

    /**
     * The bars on [page] of the pattern [step] reads. A note is a bar as long as it sounds,
     * moved by its nudge; a chord is a bar per note. A tail past the page edge goes on on the
     * next page; past the pattern's end it comes back in at its start, as it plays when the
     * pattern loops. Steps past the pattern's end are shown in place, [RollBar.outside].
     */
    fun bars(step: (Int) -> Step, page: Int, settings: PatternSettings): List<RollBar> {
        val length = settings.length
        val pageStart = page * COLUMNS
        val pageEnd = pageStart + COLUMNS
        val out = ArrayList<RollBar>()
        for (i in 0 until SeqLimits.STEPS) {
            val s = step(i)
            if (!s.on) continue
            val outside = i >= length
            for (hit in s.hits) {
                val start = i + hit.nudge
                val end = start + lengthOf(hit, settings.gate)
                if (outside) {
                    clip(out, i, hit, start, end, pageStart, pageEnd, pageStart, outside = true)
                    continue
                }
                // Played: inside 0..length, its tail coming back in at the start each loop it lasts
                // (and a note nudged early off step 1 sounding at the end of the loop before).
                val loops = ceil((end - length) / length).toInt().coerceAtLeast(0)
                for (k in -1..loops) {
                    val shift = k * length
                    clip(out, i, hit, start - shift, end - shift, pageStart, minOf(pageEnd, length), pageStart, outside = false)
                }
            }
        }
        return out
    }

    /** Adds the part of a note sounding over [start]..[end] that lies inside [from]..[to]. */
    private fun clip(
        out: MutableList<RollBar>, step: Int, hit: Hit, start: Float, end: Float,
        from: Int, to: Int, pageStart: Int, outside: Boolean,
    ) {
        val a = maxOf(start, from.toFloat())
        val b = minOf(end, to.toFloat())
        if (b <= a) return
        out += RollBar(
            step = step,
            note = hit.note,
            start = a - pageStart,
            length = b - a,
            velocity = hit.velocity,
            head = a == start,
            runsOff = b < end,
            outside = outside,
        )
    }

    /** Which of the four pages hold a note (the roll's page strip). */
    fun pagesWithNotes(step: (Int) -> Step): List<Boolean> =
        List(PAGES) { p -> (p * COLUMNS until (p + 1) * COLUMNS).any { step(it).on } }

    /**
     * The step of the bar a tap at [column] (0..16 across the page) and [pitch] (a row value,
     * fractional) lands on, or null. A bar takes taps along its length and, if shorter, to the
     * end of its step's column; of the bars there, the one with the nearest row wins, if it is
     * within [maxRows] rows.
     */
    fun stepAt(bars: List<RollBar>, column: Float, pitch: Float, maxRows: Float): Int? {
        var best: RollBar? = null
        var bestDistance = Float.MAX_VALUE
        for (bar in bars) {
            val right = if (bar.head) maxOf(bar.start + bar.length, floor(bar.start) + 1f) else bar.start + bar.length
            if (column < bar.start || column > right) continue
            val distance = abs(pitch - bar.note)
            if (distance > maxRows) continue
            if (distance < bestDistance || (distance == bestDistance && bar.head && best?.head == false)) {
                best = bar
                bestDistance = distance
            }
        }
        return best?.step
    }
}
