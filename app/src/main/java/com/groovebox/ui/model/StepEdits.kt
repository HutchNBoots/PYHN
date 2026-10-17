package com.groovebox.ui.model

import kotlin.math.roundToInt

/**
 * Chord editing from the pen keys while steps are selected (build-22): the first key after the
 * selection changes replaces the steps' notes with that key; each further key toggles its note
 * (added if the step lacks it and has room for [SeqLimits.CHORD] notes, removed if it has it).
 * Pure Kotlin.
 */
object ChordEdit {
    /** The notes a step plays after [key] is pressed; [first] is the first key since the selection changed. */
    fun press(notes: List<Int>, key: Int, first: Boolean): List<Int> = when {
        first -> listOf(key)
        key in notes -> notes - key
        notes.size >= SeqLimits.CHORD -> notes
        else -> notes + key
    }

    /**
     * [step] playing [notes]: a note it already has keeps its hit (velocity, hold, chance,
     * nudge); a new one takes the step's shared values (its first hit's), or the defaults on an
     * empty step. An empty list empties the step.
     */
    fun withNotes(step: Step, notes: List<Int>): Step {
        val shared = step.hits.firstOrNull() ?: Hit(0)
        return Step(notes.map { n -> step.hit(n) ?: shared.copy(note = n) })
    }
}

/**
 * Accent (build-22, Shift + tap a lit synth pad): a fixed boost carried by the velocity, no
 * engine change. A step is accented when every hit plays at [VELOCITY]; the toggle puts every
 * hit at [VELOCITY], or back to the default velocity.
 */
object Accent {
    const val VELOCITY = 1f

    fun isOn(step: Step): Boolean = step.on && step.hits.all { it.velocity >= VELOCITY }

    fun toggled(step: Step): Step {
        if (!step.on) return step
        val v = if (isOn(step)) SeqLimits.DEFAULT_VELOCITY else VELOCITY
        return Step(step.hits.map { it.copy(velocity = v) })
    }
}

/** The four values a step strip edits. */
enum class HitField { Velocity, Hold, Chance, Nudge }

/**
 * One drag of a step strip over several selected steps (build-22): relative. [start] takes
 * the steps as they are and the strip's value at touch-down; [moveTo] moves every hit of those
 * steps (the lane's on the drum slot) by the drag's distance from there, each clamped on its
 * own, so accents and pushes keep their differences. Hold moves by strip positions
 * ([SeqStrips.LENGTHS]). Steps without the hit take no part (a strip never creates one).
 * Pure Kotlin.
 */
class RelativeDrag {
    private var steps: Map<Int, Step>? = null
    private var lane: Int? = null
    private var anchor = 0.0
    private var slot = -1
    private var pattern = -1

    val active: Boolean
        get() = steps != null

    fun start(slot: Int, pattern: Int, selected: Map<Int, Step>, lane: Int?, anchor: Double) {
        this.slot = slot
        this.pattern = pattern
        this.lane = lane
        this.anchor = anchor
        steps = selected.filterValues { s -> if (lane == null) s.on else s.hit(lane) != null }
    }

    fun holds(slot: Int, pattern: Int, step: Int) = slot == this.slot && pattern == this.pattern && steps?.containsKey(step) == true

    /** The steps with [field] moved by `value − anchor`; empty when no drag is open, or for another pattern (which ends it). */
    fun moveTo(value: Double, field: HitField, slot: Int, pattern: Int): Map<Int, Step> {
        val from = steps ?: return emptyMap()
        if (slot != this.slot || pattern != this.pattern) {
            end()
            return emptyMap()
        }
        val delta = value - anchor
        return from.mapValues { (_, s) -> Step(s.hits.map { h -> if (lane == null || h.note == lane) shift(h, field, delta) else h }) }
    }

    fun end() {
        steps = null
        slot = -1
        pattern = -1
    }

    companion object {
        fun shift(h: Hit, field: HitField, delta: Double): Hit = when (field) {
            HitField.Velocity -> h.copy(velocity = (h.velocity + delta).toFloat().coerceIn(SeqLimits.MIN_VELOCITY, 1f))
            HitField.Chance -> h.copy(probability = (h.probability + delta).toFloat().coerceIn(0f, 1f))
            HitField.Nudge -> h.copy(nudge = (h.nudge + delta).toFloat().coerceIn(-SeqLimits.MAX_NUDGE, SeqLimits.MAX_NUDGE))
            HitField.Hold -> {
                val i = (SeqStrips.lengthIndex(h.length) + delta.roundToInt()).coerceIn(0, SeqStrips.LENGTHS.lastIndex)
                h.copy(length = SeqStrips.LENGTHS[i])
            }
        }
    }
}

/**
 * Spread (build-22, the Shift-layer strip with steps selected): a seeded random humanise.
 * At [amount] 0..1 every hit's velocity moves by up to ±[VELOCITY_RANGE] × amount and its
 * nudge by up to ±[NUDGE_RANGE] × amount around its own value, each hit by its own fixed
 * random factor from [seed], so the same seed and amount always give the same result and
 * dragging back to 0 gives the steps back. Pure Kotlin (its own generator, the same on every JVM).
 */
object Spread {
    const val VELOCITY_RANGE = 0.3f
    const val NUDGE_RANGE = 0.25f

    fun apply(steps: Map<Int, Step>, lane: Int?, amount: Float, seed: Long): Map<Int, Step> {
        val a = amount.coerceIn(0f, 1f)
        return steps.mapValues { (i, s) ->
            Step(
                s.hits.map { h ->
                    if (lane != null && h.note != lane) return@map h
                    val rv = unit(seed, i, h.note, 0)
                    val rn = unit(seed, i, h.note, 1)
                    h.copy(
                        velocity = (h.velocity + a * VELOCITY_RANGE * rv).coerceIn(SeqLimits.MIN_VELOCITY, 1f),
                        nudge = (h.nudge + a * NUDGE_RANGE * rn).coerceIn(-SeqLimits.MAX_NUDGE, SeqLimits.MAX_NUDGE),
                    )
                },
            )
        }
    }

    /** −1..1, fixed for (seed, step, note, which). SplitMix64. */
    fun unit(seed: Long, step: Int, note: Int, which: Int): Float {
        var z = seed + 0x9E3779B97F4A7C15uL.toLong() * (1 + step * 131L + note * 7L + which * 100_003L)
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 11).toDouble() / (1L shl 53).toDouble() * 2.0 - 1.0).toFloat()
    }
}

/**
 * Chord names for the Seq footer (build-22): notes low → high, and a simple chord named by its
 * root ("C maj · C4 E4 G4", "A m7 · …", an inversion "F maj/A · A3 C4 F4"). Pure Kotlin.
 */
object ChordName {
    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Shapes by pitch-class set above the root, most specific first. */
    private val SHAPES: List<Pair<Set<Int>, String>> = listOf(
        setOf(0, 4, 7, 11) to "maj7",
        setOf(0, 3, 7, 10) to "m7",
        setOf(0, 4, 7, 10) to "7",
        setOf(0, 4, 7) to "maj",
        setOf(0, 3, 7) to "min",
        setOf(0, 3, 6) to "dim",
        setOf(0, 4, 8) to "aug",
        setOf(0, 5, 7) to "sus4",
        setOf(0, 2, 7) to "sus2",
        setOf(0, 7) to "5",
    )

    /** "C maj", "F maj/A", or null when [notes] are not one of the simple shapes. */
    fun name(notes: List<Int>): String? {
        if (notes.size < 2) return null
        val classes = notes.map { Math.floorMod(it, 12) }.toSet()
        val bass = Math.floorMod(notes.min(), 12)
        // The bass as root first, so an ambiguous shape (aug, sus) names the bass.
        val roots = listOf(bass) + classes.filter { it != bass }.sorted()
        for (root in roots) {
            val shape = classes.map { Math.floorMod(it - root, 12) }.toSet()
            val kind = SHAPES.firstOrNull { it.first == shape }?.second ?: continue
            return NAMES[root] + " " + kind + if (root != bass) "/" + NAMES[bass] else ""
        }
        return null
    }

    /** The footer's reading of a step's notes: "A2", "C maj · C4 E4 G4", "C4 D4 F#4". */
    fun describe(notes: List<Int>): String {
        val sorted = notes.sorted()
        val list = sorted.joinToString(" ") { NoteNames.name(it) }
        return name(sorted)?.let { "$it · $list" } ?: list
    }
}
