package com.groovebox.ui.model

import com.groovebox.engine.NativeEngine
import com.groovebox.engine.RecordedEvent
import kotlin.math.roundToInt

/** Sizes of the pattern store (docs/feature-spec.md F3). Same values as NativeEngine's constants. */
object SeqLimits {
    const val SLOTS = NativeEngine.SLOT_COUNT
    const val PATTERNS = NativeEngine.PATTERN_COUNT
    const val STEPS = NativeEngine.MAX_STEPS

    /** Hits (sounds or notes) a step holds, and the floats per hit in [PatternSink.setStep]. */
    const val HITS = NativeEngine.MAX_HITS_PER_STEP
    const val HIT_FIELDS = NativeEngine.HIT_FIELDS

    /** A synth chord entered from the keys or a pad: at most this many notes (recording can add up to [HITS]). */
    const val CHORD = 4
    const val LANES = NativeEngine.MAX_MOTION_LANES
    const val RATES = 4 // NativeEngine.STEP_RATES.size (a val, so not readable without loading the library)
    const val DEFAULT_RATE = 1 // 1/16
    const val MIN_GATE = 0.05f
    const val MIN_TEMPO = 40.0
    const val MAX_TEMPO = 240.0
    const val DEFAULT_TEMPO = 120.0
    const val DEFAULT_VELOCITY = 0.8f
    const val MIN_VELOCITY = 0.01f

    /** A hit's length that means "the pattern's gate". */
    const val PATTERN_GATE = -1f
    const val MIN_LENGTH = 1f / 16
    const val MAX_LENGTH = 16f
    const val MAX_NUDGE = 0.5f
}

/**
 * One sound (drum slot) or note (synth slot) of a step, with its own values (build-20, the
 * engine's Hit). [length] is in steps, or [SeqLimits.PATTERN_GATE]; [nudge] is a fraction of a
 * step, negative = early.
 */
data class Hit(
    val note: Int,
    val velocity: Float = SeqLimits.DEFAULT_VELOCITY,
    val length: Float = SeqLimits.PATTERN_GATE,
    val probability: Float = 1f,
    val nudge: Float = 0f,
)

/** One step: up to [SeqLimits.HITS] hits, one per note. On when it has a hit. Immutable: an edit replaces it. */
data class Step(val hits: List<Hit> = emptyList()) {
    val on: Boolean
        get() = hits.isNotEmpty()

    val notes: List<Int>
        get() = hits.map { it.note }

    fun hit(note: Int): Hit? = hits.firstOrNull { it.note == note }

    companion object {
        /** A step playing [notes], all with the same values. */
        fun of(notes: List<Int>, velocity: Float = SeqLimits.DEFAULT_VELOCITY, probability: Float = 1f) =
            Step(notes.map { Hit(it, velocity, probability = probability) })
    }
}

/** What a step pad shows for the selected lane (build-20): that sound, only other sounds, or nothing. */
enum class PadMark {
    Lane, Other, Empty;

    companion object {
        /** [lane] is the drum lane's note, or null on a synth slot (any hit counts). */
        fun of(step: Step, lane: Int?): PadMark = when {
            !step.on -> Empty
            lane == null || step.hit(lane) != null -> Lane
            else -> Other
        }
    }
}

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
    /** [hits] holds [hitCount] × [SeqLimits.HIT_FIELDS] floats: note, velocity, length, probability, nudge. */
    fun setStep(slot: Int, pattern: Int, step: Int, hits: FloatArray, hitCount: Int)
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

    /** Replaces a step (clamped, see [clampStep]). */
    fun setStep(slot: Int, pattern: Int, step: Int, value: Step) {
        val p = data(slot, pattern) ?: return
        if (step !in 0 until SeqLimits.STEPS) return
        val s = clampStep(value)
        if (s == p.steps[step]) return
        p.steps[step] = s
        send(slot, pattern, step, s)
        changed()
    }

    fun updateStep(slot: Int, pattern: Int, step: Int, edit: (Step) -> Step) =
        setStep(slot, pattern, step, edit(step(slot, pattern, step)))

    /**
     * A pad tap on a synth slot (F3.5): a step that plays turns off (empty); an empty one plays
     * [notes] at [velocity].
     */
    fun toggleStep(slot: Int, pattern: Int, step: Int, notes: List<Int>, velocity: Float) = updateStep(slot, pattern, step) {
        if (it.on) Step() else Step.of(notes, velocity)
    }

    /**
     * A pad tap on the drum slot (build-20 lanes): adds or removes the sound [note] on the step,
     * leaving the other sounds. Returns false, changing nothing, when the step is full.
     */
    fun toggleHit(slot: Int, pattern: Int, step: Int, note: Int, velocity: Float): Boolean {
        val old = step(slot, pattern, step)
        val new = if (old.hit(note) != null) {
            Step(old.hits.filter { it.note != note })
        } else {
            if (old.hits.size >= SeqLimits.HITS) return false
            Step(old.hits + Hit(note, velocity))
        }
        setStep(slot, pattern, step, new)
        return true
    }

    /**
     * Keys played while the step is selected: the step plays [notes] at [velocity]; the length,
     * chance and nudge the chord shares stay as they were (its first hit's).
     */
    fun setStepNotes(slot: Int, pattern: Int, step: Int, notes: List<Int>, velocity: Float) =
        updateStep(slot, pattern, step) { old ->
            val shared = old.hits.firstOrNull() ?: Hit(0)
            Step(notes.map { shared.copy(note = it, velocity = velocity) })
        }

    /**
     * A step strip moved (build-20): on the drum slot ([lane] = the sound's note) only that
     * sound's hit, created with the defaults when the step lacks it (and has room); on a synth
     * slot ([lane] null) every hit, or, on an empty step, the hits [entry] gives.
     */
    fun editHits(slot: Int, pattern: Int, step: Int, lane: Int?, entry: () -> List<Hit>, edit: (Hit) -> Hit) =
        updateStep(slot, pattern, step) { old ->
            when {
                lane != null && old.hit(lane) != null -> Step(old.hits.map { if (it.note == lane) edit(it) else it })
                lane != null -> if (old.hits.size >= SeqLimits.HITS) old else Step(old.hits + edit(Hit(lane)))
                old.on -> Step(old.hits.map(edit))
                else -> Step(entry().map(edit))
            }
        }

    /** Shift + Clear on the drum slot: the sound [note] leaves every step of the pattern. */
    fun clearLane(slot: Int, pattern: Int, note: Int) {
        for (i in 0 until SeqLimits.STEPS) {
            val old = step(slot, pattern, i)
            if (old.hit(note) != null) setStep(slot, pattern, i, Step(old.hits.filter { it.note != note }))
        }
    }

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

    /** A copy of one pattern (for undo), empty or not. */
    fun patternSnapshot(slot: Int, pattern: Int): PatternSnapshot {
        val p = data(slot, pattern) ?: return PatternSnapshot(slot, pattern, DEFAULT_SETTINGS, emptyMap(), emptyList())
        return snapshotOf(slot, pattern, p)
    }

    /**
     * Puts one pattern back as [snap] holds it (undo, redo), sending the engine only what
     * differs: settings if they differ, each step that differs (an empty one included), a
     * clear for each lane the snapshot lacks, and each lane value that differs. Never a
     * clearPattern: that would reset a playing pattern's length (and so its position) and the
     * engine's motion overrides, and could flood the command queue.
     */
    fun restorePattern(snap: PatternSnapshot) {
        val p = data(snap.slot, snap.pattern) ?: return
        val slot = snap.slot
        val pattern = snap.pattern
        var any = false
        val s = snap.settings.clamped()
        if (s != p.settings) {
            p.settings = s
            sink.setPatternSettings(slot, pattern, s.length, s.rate, s.shuffle, s.gate)
            any = true
        }
        for (i in 0 until SeqLimits.STEPS) {
            val want = snap.steps[i]?.let(::clampStep) ?: EMPTY_STEP
            if (want == p.steps[i]) continue
            p.steps[i] = want
            send(slot, pattern, i, want)
            any = true
        }
        // The lanes the snapshot holds (as [load] accepts them), in its order.
        val wanted = ArrayList<LaneSnapshot>()
        for (laneSnap in snap.lanes) {
            if (wanted.size >= SeqLimits.LANES) break
            if (wanted.any { it.target == laneSnap.target }) continue
            val points = laneSnap.points.filter { it.step in 0 until SeqLimits.STEPS && !it.value.isNaN() && !it.value.isInfinite() }
            if (points.isNotEmpty()) wanted += LaneSnapshot(laneSnap.target, points)
        }
        val gone = p.lanes.filter { lane -> wanted.none { it.target == lane.target } }
        for (lane in gone) {
            p.lanes.remove(lane)
            sink.clearMotionLane(slot, pattern, lane.target)
            any = true
        }
        val ordered = ArrayList<MotionLane>(wanted.size)
        for (laneSnap in wanted) {
            val lane = p.lanes.firstOrNull { it.target == laneSnap.target } ?: MotionLane(laneSnap.target)
            ordered += lane
            val values = arrayOfNulls<LanePoint>(SeqLimits.STEPS)
            for (pt in laneSnap.points) values[pt.step] = pt
            for (i in 0 until SeqLimits.STEPS) {
                val pt = values[i]
                val v = pt?.value ?: Float.NaN
                val stamp = pt?.stamp?.coerceAtLeast(1L) ?: 0L
                if (stamp > stampCounter) stampCounter = stamp
                if (lane.valueAt(i).sameAs(v)) {
                    lane.stamps[i] = stamp // the mirror's display order only; the engine has the value
                    continue
                }
                lane.set(i, v, stamp)
                sink.setMotionValue(slot, pattern, laneSnap.target, i, v)
                any = true
            }
        }
        if (ordered != p.lanes) {
            p.lanes.clear()
            p.lanes.addAll(ordered)
            any = true
        }
        if (any) changed()
    }

    private fun snapshotOf(slot: Int, pattern: Int, p: PatternData): PatternSnapshot {
        val steps = HashMap<Int, Step>()
        for (i in 0 until SeqLimits.STEPS) if (p.steps[i] != EMPTY_STEP) steps[i] = p.steps[i]
        val lanes = p.lanes.mapNotNull { lane ->
            val points = (0 until SeqLimits.STEPS).filter { lane.hasValueAt(it) }
                .map { LanePoint(it, lane.valueAt(it), lane.stamps[it]) }
            if (points.isEmpty()) null else LaneSnapshot(lane.target, points)
        }
        return PatternSnapshot(slot, pattern, p.settings, steps, lanes)
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
                val snap = snapshotOf(slot, pattern, patterns[slot][pattern])
                if (snap.settings == DEFAULT_SETTINGS && snap.steps.isEmpty() && snap.lanes.isEmpty()) continue
                out += snap
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
            load(p, snap)
        }
        changed()
    }

    /** Fills an empty pattern from [snap] (clamped), sending the engine what differs from empty. */
    private fun load(p: PatternData, snap: PatternSnapshot) {
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
            send(snap.slot, snap.pattern, i, st)
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

    private fun send(slot: Int, pattern: Int, step: Int, s: Step) {
        val buf = FloatArray(s.hits.size * SeqLimits.HIT_FIELDS)
        s.hits.forEachIndexed { h, hit ->
            val o = h * SeqLimits.HIT_FIELDS
            buf[o] = hit.note.toFloat()
            buf[o + 1] = hit.velocity
            buf[o + 2] = hit.length
            buf[o + 3] = hit.probability
            buf[o + 4] = hit.nudge
        }
        sink.setStep(slot, pattern, step, buf, s.hits.size)
    }

    /** Applies a write the engine made while recording. Mirror only: the engine already has it. */
    fun applyRecorded(event: RecordedEvent) {
        val p = data(event.slot, event.pattern) ?: return
        val i = event.step
        if (i !in 0 until SeqLimits.STEPS) return
        when (event.kind) {
            // Build-20 rule: a hit (note, played velocity, pattern gate, 1, 0) unless the note is
            // there; a full step has its last hit replaced.
            RecordedEvent.Kind.Note -> {
                val old = p.steps[i]
                val note = event.noteOrTarget
                if (old.hit(note) != null) return
                val hit = clampHit(Hit(note, event.value))
                val hits = if (old.hits.size >= SeqLimits.HITS) old.hits.take(SeqLimits.HITS - 1) + hit else old.hits + hit
                p.steps[i] = Step(hits)
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

        /** At most [SeqLimits.HITS] hits, one per note (the first wins), each clamped by [clampHit]. */
        fun clampStep(s: Step): Step {
            val seen = HashSet<Int>()
            val hits = ArrayList<Hit>(minOf(s.hits.size, SeqLimits.HITS))
            for (h in s.hits) {
                if (hits.size >= SeqLimits.HITS) break
                val c = clampHit(h)
                if (seen.add(c.note)) hits += c
            }
            return if (hits == s.hits) s else Step(hits)
        }

        /** Note 0..127, velocity 0.01..1, length the pattern gate or 1/16..16, chance 0..1, nudge ±0.5; NaN → default. */
        fun clampHit(h: Hit) = Hit(
            note = h.note.coerceIn(0, 127),
            velocity = h.velocity.finiteOr(SeqLimits.DEFAULT_VELOCITY).coerceIn(SeqLimits.MIN_VELOCITY, 1f),
            length = clampLength(h.length),
            probability = h.probability.finiteOr(1f).coerceIn(0f, 1f),
            nudge = h.nudge.finiteOr(0f).coerceIn(-SeqLimits.MAX_NUDGE, SeqLimits.MAX_NUDGE),
        )

        fun clampLength(length: Float): Float = when {
            length.isNaN() || length < 0f -> SeqLimits.PATTERN_GATE
            else -> length.coerceIn(SeqLimits.MIN_LENGTH, SeqLimits.MAX_LENGTH)
        }
    }
}

private fun Float.finiteOr(fallback: Float) = if (isNaN() || isInfinite()) fallback else this

private fun Float.sameAs(other: Float) = this == other || (isNaN() && other.isNaN())
