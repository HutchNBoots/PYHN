package com.groovebox.ui.model

import com.groovebox.engine.NativeEngine
import com.groovebox.engine.RecordedEvent
import kotlin.math.roundToInt

/** Sizes of the pattern store (docs/feature-spec.md F3). Same values as NativeEngine's constants. */
object SeqLimits {
    const val SLOTS = NativeEngine.SLOT_COUNT
    const val PATTERNS = NativeEngine.PATTERN_COUNT
    const val STEPS = NativeEngine.MAX_STEPS
    const val NOTES = NativeEngine.MAX_NOTES_PER_STEP
    const val LANES = NativeEngine.MAX_MOTION_LANES
    const val RATES = 4 // NativeEngine.STEP_RATES.size (a val, so not readable without loading the library)
    const val DEFAULT_RATE = 1 // 1/16
    const val MIN_GATE = 0.05f
    const val MIN_TEMPO = 40.0
    const val MAX_TEMPO = 240.0
    const val DEFAULT_TEMPO = 120.0
    const val DEFAULT_VELOCITY = 0.8f
}

/** One step. [gate] < 0 means the pattern's gate. Immutable: an edit replaces the step. */
data class Step(
    val on: Boolean = false,
    val notes: List<Int> = emptyList(),
    val velocity: Float = SeqLimits.DEFAULT_VELOCITY,
    val gate: Float = -1f,
    val probability: Float = 1f,
)

/** A pattern's settings, always within range (see [clamped]). */
data class PatternSettings(
    val length: Int = 16,
    val rate: Int = SeqLimits.DEFAULT_RATE,
    val shuffle: Float = 0f,
    val gate: Float = 0.5f,
) {
    fun clamped() = PatternSettings(
        length = length.coerceIn(1, SeqLimits.STEPS),
        rate = rate.coerceIn(0, SeqLimits.RATES - 1),
        shuffle = shuffle.finiteOr(0f).coerceIn(0f, 1f),
        gate = gate.finiteOr(0.5f).coerceIn(SeqLimits.MIN_GATE, 1f),
    )
}

/** One motion lane: a target (synth param id, or drum key × param count + id) and one optional value per step. */
class MotionLane internal constructor(val target: Int) {
    private val values = FloatArray(SeqLimits.STEPS) { Float.NaN }
    internal val stamps = LongArray(SeqLimits.STEPS)

    /** The value at [step], or NaN when the step has none. */
    fun valueAt(step: Int): Float = values.getOrElse(step) { Float.NaN }

    fun hasValueAt(step: Int): Boolean = !valueAt(step).isNaN()

    internal fun set(step: Int, value: Float, stamp: Long) {
        values[step] = value
        stamps[step] = if (value.isNaN()) 0L else stamp
    }
}

/** One motion value of a saved lane; [stamp] orders values across lanes (latest wins on display). */
data class LanePoint(val step: Int, val value: Float, val stamp: Long)

/** A saved motion lane: its target and its values, by step. */
data class LaneSnapshot(val target: Int, val points: List<LanePoint>)

/**
 * A saved copy of one pattern (M4 session store): settings, the steps that differ from an
 * empty step, and the lanes that hold values, oldest lane first. Immutable, so it can cross
 * threads.
 */
data class PatternSnapshot(
    val slot: Int,
    val pattern: Int,
    val settings: PatternSettings,
    val steps: Map<Int, Step>,
    val lanes: List<LaneSnapshot>,
)

/** Where the store sends every edit: the engine in the app (NativeEngine), a recorder in tests. */
interface PatternSink {
    fun setPatternSettings(slot: Int, pattern: Int, length: Int, rate: Int, shuffle: Float, gate: Float)
    fun setStep(slot: Int, pattern: Int, step: Int, on: Boolean, notes: IntArray, velocity: Float, gate: Float, probability: Float)
    fun setMotionValue(slot: Int, pattern: Int, target: Int, step: Int, value: Float)
    fun clearMotionLane(slot: Int, pattern: Int, target: Int)
    fun clearPattern(slot: Int, pattern: Int)
    fun setTempo(bpm: Double)
}

/**
 * The UI's mirror of every pattern (8 slots × 16 patterns × 64 steps, settings and motion
 * lanes) plus the tempo: the authority for display (and for saving, in M4). Every edit
 * updates the mirror and sends the matching engine call through [sink]; writes the engine
 * made itself while recording come in through [applyRecorded], which only updates the mirror,
 * with exactly the rules documented on [RecordedEvent]. Out-of-range indexes are ignored.
 *
 * Pure Kotlin. Main thread only.
 */
class PatternStore(private val sink: PatternSink) {
    private class PatternData {
        var settings = PatternSettings()
        val steps = Array(SeqLimits.STEPS) { Step() }
        val lanes = ArrayList<MotionLane>(SeqLimits.LANES)
    }

    private val patterns = Array(SeqLimits.SLOTS) { Array(SeqLimits.PATTERNS) { PatternData() } }
    private var stampCounter = 0L

    /** Called after every change to the mirror (the UI uses it to recompose). */
    var onChange: (() -> Unit)? = null

    /** Bumped on every change. */
    var revision: Long = 0
        private set

    var tempo: Double = SeqLimits.DEFAULT_TEMPO
        private set

    fun settings(slot: Int, pattern: Int): PatternSettings = data(slot, pattern)?.settings ?: PatternSettings()

    fun step(slot: Int, pattern: Int, step: Int): Step = data(slot, pattern)?.steps?.getOrNull(step) ?: Step()

    /** The pattern's lanes, oldest first. */
    fun lanes(slot: Int, pattern: Int): List<MotionLane> = data(slot, pattern)?.lanes?.toList() ?: emptyList()

    fun lane(slot: Int, pattern: Int, target: Int): MotionLane? = data(slot, pattern)?.lanes?.firstOrNull { it.target == target }

    /** The lane that most recently got a value at [step], or null when the step has no motion. */
    fun latestLaneAt(slot: Int, pattern: Int, step: Int): MotionLane? {
        if (step !in 0 until SeqLimits.STEPS) return null
        return data(slot, pattern)?.lanes?.filter { it.hasValueAt(step) }?.maxByOrNull { it.stamps[step] }
    }

    /** [force] sends it to the engine even when the mirror already holds it. */
    fun setTempo(bpm: Double, force: Boolean = false) {
        val v = clampTempo(bpm)
        if (v == tempo && !force) return
        tempo = v
        sink.setTempo(v)
        changed()
    }

    fun setSettings(slot: Int, pattern: Int, settings: PatternSettings) {
        val p = data(slot, pattern) ?: return
        val s = settings.clamped()
        if (s == p.settings) return
        p.settings = s
        sink.setPatternSettings(slot, pattern, s.length, s.rate, s.shuffle, s.gate)
        changed()
    }

    fun updateSettings(slot: Int, pattern: Int, edit: (PatternSettings) -> PatternSettings) =
        setSettings(slot, pattern, edit(settings(slot, pattern)))

    /** Replaces a step (clamped: at most 4 notes, each 0..127, velocity and probability 0..1). */
    fun setStep(slot: Int, pattern: Int, step: Int, value: Step) {
        val p = data(slot, pattern) ?: return
        if (step !in 0 until SeqLimits.STEPS) return
        val s = clampStep(value)
        if (s == p.steps[step]) return
        p.steps[step] = s
        sink.setStep(slot, pattern, step, s.on, s.notes.toIntArray(), s.velocity, s.gate, s.probability)
        changed()
    }

    fun updateStep(slot: Int, pattern: Int, step: Int, edit: (Step) -> Step) =
        setStep(slot, pattern, step, edit(step(slot, pattern, step)))

    /**
     * A pad tap (F3.5): an on step turns off (keeping its notes); an off step turns on with
     * [notes] and [velocity].
     */
    fun toggleStep(slot: Int, pattern: Int, step: Int, notes: List<Int>, velocity: Float) = updateStep(slot, pattern, step) {
        if (it.on) it.copy(on = false) else it.copy(on = true, notes = notes, velocity = velocity)
    }

    /** Keys played while the step is selected: it turns on with these notes. */
    fun setStepNotes(slot: Int, pattern: Int, step: Int, notes: List<Int>, velocity: Float) =
        updateStep(slot, pattern, step) { it.copy(on = true, notes = notes, velocity = velocity) }

    /**
     * One motion value at one step; NaN clears it. A new target needs a free lane (at most 8,
     * as in the engine): returns false, and changes nothing, when there is none.
     */
    fun setMotionValue(slot: Int, pattern: Int, target: Int, step: Int, value: Float): Boolean {
        val p = data(slot, pattern) ?: return false
        if (step !in 0 until SeqLimits.STEPS) return false
        val lane = p.lanes.firstOrNull { it.target == target } ?: run {
            if (value.isNaN() || p.lanes.size >= SeqLimits.LANES) return false
            MotionLane(target).also { p.lanes += it }
        }
        if (lane.valueAt(step).sameAs(value)) return true
        lane.set(step, value, ++stampCounter)
        sink.setMotionValue(slot, pattern, target, step, value)
        changed()
        return true
    }

    fun clearMotionLane(slot: Int, pattern: Int, target: Int) {
        val p = data(slot, pattern) ?: return
        if (!p.lanes.removeAll { it.target == target }) return
        sink.clearMotionLane(slot, pattern, target)
        changed()
    }

    /** Every step off, motion cleared, settings back to the defaults (the engine does the same). */
    fun clearPattern(slot: Int, pattern: Int) {
        val p = data(slot, pattern) ?: return
        p.settings = PatternSettings()
        p.steps.fill(Step())
        p.lanes.clear()
        sink.clearPattern(slot, pattern)
        changed()
    }

    /**
     * Every pattern that differs from an empty one, for saving (F7.4). Lanes without any
     * value are left out: the engine cannot be given an empty lane, so a restored session
     * has none either.
     */
    fun snapshot(): List<PatternSnapshot> {
        val out = ArrayList<PatternSnapshot>()
        for (slot in 0 until SeqLimits.SLOTS) {
            for (pattern in 0 until SeqLimits.PATTERNS) {
                val p = patterns[slot][pattern]
                val steps = HashMap<Int, Step>()
                for (i in 0 until SeqLimits.STEPS) if (p.steps[i] != EMPTY_STEP) steps[i] = p.steps[i]
                val lanes = p.lanes.mapNotNull { lane ->
                    val points = (0 until SeqLimits.STEPS).filter { lane.hasValueAt(it) }
                        .map { LanePoint(it, lane.valueAt(it), lane.stamps[it]) }
                    if (points.isEmpty()) null else LaneSnapshot(lane.target, points)
                }
                if (p.settings == DEFAULT_SETTINGS && steps.isEmpty() && lanes.isEmpty()) continue
                out += PatternSnapshot(slot, pattern, p.settings, steps, lanes)
            }
        }
        return out
    }

    /**
     * Replaces every pattern with [saved] (a relaunch, F7.6), sending the engine only what
     * differs from an empty pattern, so a fresh engine ends up equal to the mirror. Patterns
     * not in [saved] become empty. Everything is clamped as by the single edits; out-of-range
     * slots, patterns, steps and lanes beyond 8 are ignored, as are duplicates after the first.
     * Lane order and the stamps' order are kept, so the display picks the same latest lane.
     */
    fun restore(saved: List<PatternSnapshot>) {
        for (slot in 0 until SeqLimits.SLOTS) {
            for (pattern in 0 until SeqLimits.PATTERNS) {
                val p = patterns[slot][pattern]
                if (p.settings != DEFAULT_SETTINGS || p.lanes.isNotEmpty() || p.steps.any { it != EMPTY_STEP }) {
                    p.settings = DEFAULT_SETTINGS
                    p.steps.fill(EMPTY_STEP)
                    p.lanes.clear()
                    sink.clearPattern(slot, pattern)
                }
            }
        }
        val seen = HashSet<Int>()
        for (snap in saved) {
            val p = data(snap.slot, snap.pattern) ?: continue
            if (!seen.add(snap.slot * SeqLimits.PATTERNS + snap.pattern)) continue
            val s = snap.settings.clamped()
            if (s != DEFAULT_SETTINGS) {
                p.settings = s
                sink.setPatternSettings(snap.slot, snap.pattern, s.length, s.rate, s.shuffle, s.gate)
            }
            for ((i, step) in snap.steps.toSortedMap()) {
                if (i !in 0 until SeqLimits.STEPS) continue
                val st = clampStep(step)
                if (st == EMPTY_STEP) continue
                p.steps[i] = st
                sink.setStep(snap.slot, snap.pattern, i, st.on, st.notes.toIntArray(), st.velocity, st.gate, st.probability)
            }
            for (laneSnap in snap.lanes) {
                if (p.lanes.size >= SeqLimits.LANES) break
                if (p.lanes.any { it.target == laneSnap.target }) continue
                val points = laneSnap.points.filter { it.step in 0 until SeqLimits.STEPS && !it.value.isNaN() && !it.value.isInfinite() }
                if (points.isEmpty()) continue
                val lane = MotionLane(laneSnap.target).also { p.lanes += it }
                for (pt in points) {
                    val stamp = pt.stamp.coerceAtLeast(1L)
                    lane.set(pt.step, pt.value, stamp)
                    if (stamp > stampCounter) stampCounter = stamp
                    sink.setMotionValue(snap.slot, snap.pattern, laneSnap.target, pt.step, pt.value)
                }
            }
        }
        changed()
    }

    /** Applies a write the engine made while recording. Mirror only: the engine already has it. */
    fun applyRecorded(event: RecordedEvent) {
        val p = data(event.slot, event.pattern) ?: return
        val i = event.step
        if (i !in 0 until SeqLimits.STEPS) return
        when (event.kind) {
            RecordedEvent.Kind.Note -> {
                val old = p.steps[i]
                val note = event.noteOrTarget
                val notes = when {
                    note in old.notes -> old.notes
                    old.notes.size >= SeqLimits.NOTES -> old.notes.take(SeqLimits.NOTES - 1) + note
                    else -> old.notes + note
                }
                p.steps[i] = old.copy(on = true, notes = notes, velocity = event.value)
            }
            RecordedEvent.Kind.Motion -> {
                val lane = p.lanes.firstOrNull { it.target == event.noteOrTarget } ?: run {
                    if (p.lanes.size >= SeqLimits.LANES) return
                    MotionLane(event.noteOrTarget).also { p.lanes += it }
                }
                lane.set(i, event.value, ++stampCounter)
            }
        }
        changed()
    }

    private fun data(slot: Int, pattern: Int): PatternData? = patterns.getOrNull(slot)?.getOrNull(pattern)

    private fun changed() {
        revision++
        onChange?.invoke()
    }

    companion object {
        private val EMPTY_STEP = Step()
        private val DEFAULT_SETTINGS = PatternSettings()

        /** 40.0..240.0 in 0.1 steps (F3.1). */
        fun clampTempo(bpm: Double): Double {
            val v = if (bpm.isNaN()) SeqLimits.DEFAULT_TEMPO else bpm.coerceIn(SeqLimits.MIN_TEMPO, SeqLimits.MAX_TEMPO)
            return (v * 10).roundToInt() / 10.0
        }

        fun clampStep(s: Step) = Step(
            on = s.on,
            notes = s.notes.take(SeqLimits.NOTES).map { it.coerceIn(0, 127) },
            velocity = s.velocity.finiteOr(SeqLimits.DEFAULT_VELOCITY).coerceIn(0f, 1f),
            gate = if (s.gate.isNaN() || s.gate < 0f) -1f else s.gate.coerceIn(SeqLimits.MIN_GATE, 1f),
            probability = s.probability.finiteOr(1f).coerceIn(0f, 1f),
        )
    }
}

private fun Float.finiteOr(fallback: Float) = if (isNaN() || isInfinite()) fallback else this

private fun Float.sameAs(other: Float) = this == other || (isNaN() && other.isNaN())
