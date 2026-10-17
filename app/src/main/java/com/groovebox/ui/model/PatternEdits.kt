package com.groovebox.ui.model

/**
 * What a pad gesture moves on one step (build-22): on a synth slot the whole step (every note);
 * on the drum slot only the lane's sound ([lane], its note), the other sounds staying where
 * they are. Motion values follow the same rule: every lane on a synth slot, on the drum slot
 * only the lanes whose target is one of the lane sound's parameters (drum targets are
 * key × [paramCount] + parameter id).
 */
class EditScope private constructor(val lane: Int?, private val laneKey: Int, private val paramCount: Int) {
    /** The part of [step] this scope moves. */
    fun part(step: Step): Step = if (lane == null) step else Step(step.hits.filter { it.note == lane })

    /** [step] without its part. */
    fun rest(step: Step): Step = if (lane == null) EMPTY else Step(step.hits.filter { it.note != lane })

    /**
     * [step] can take [part]: always on a synth slot; on the drum slot unless the step is full
     * (8 hits) of other sounds while [part] holds the lane's hit.
     */
    fun fits(step: Step, part: Step): Boolean =
        lane == null || !part.on || step.hit(lane) != null || step.hits.size < SeqLimits.HITS

    /**
     * [step] with its part replaced by [part] (the drum slot keeps the other sounds). A step
     * that cannot take it ([fits]) is returned unchanged: the hit is never silently dropped.
     */
    fun merge(step: Step, part: Step): Step {
        if (lane == null) return part
        if (!fits(step, part)) return step
        return Step(rest(step).hits + part.hits.filter { it.note == lane })
    }

    fun ownsTarget(target: Int): Boolean = lane == null || (paramCount > 0 && target >= 0 && target / paramCount == laneKey)

    companion object {
        private val EMPTY = Step()

        /** A synth slot: whole steps, every motion lane. */
        val WHOLE = EditScope(null, -1, 0)

        /** The drum slot's lane: sound [note] (kit key [key]), and the motion of that key's [paramCount] parameters. */
        fun drumLane(note: Int, key: Int, paramCount: Int) = EditScope(note, key, paramCount)
    }
}

/**
 * The Seq screen's pattern edits of build-22 (docs/plans/2026-10-17-cx-programme.md, "Seq (both
 * slot types)"), as pure functions from one pattern's snapshot to the snapshot it becomes. The
 * screen applies the result with [PatternStore.restorePattern] inside one [PatternHistory.edit],
 * so the engine gets only what differs (through setStep / setPatternSettings / setMotionValue)
 * and each edit is one undo step. The edits that put a drum hit onto other steps (paste, move,
 * fill, rotate, insert, delete) return null, changing nothing, when one of those steps is full
 * ([EditScope.fits]); the screen then says "step full". Only steps inside the pattern's length play, so the ones
 * that rotate, shift or fill stay inside it; the copies (×2, page copy) write the steps they
 * name, hidden or not.
 */
object PatternEdits {
    /** Patterns longer than this cannot double (64 steps at most). */
    const val MAX_DOUBLE = SeqLimits.STEPS / 2
    const val PAGE = 16

    /** No steps on and no motion: Dup's "next empty pattern". Settings alone do not count. */
    fun isEmpty(p: PatternSnapshot): Boolean = p.steps.values.none { it.on } && p.lanes.none { it.points.isNotEmpty() }

    /** The first empty pattern after [current] (wrapping, never [current] itself), or null. */
    fun nextEmpty(current: Int, isEmpty: (Int) -> Boolean): Int? {
        for (k in 1 until SeqLimits.PATTERNS) {
            val p = (current + k) % SeqLimits.PATTERNS
            if (isEmpty(p)) return p
        }
        return null
    }

    /** [p] as [slot]'s [pattern] (a pattern copy). */
    fun retargeted(p: PatternSnapshot, slot: Int, pattern: Int) = p.copy(slot = slot, pattern = pattern)

    /**
     * ×2: length L becomes 2L and steps 1..L are copied into L+1..2L with every hit value and
     * every motion value (a step without a value there clears the copy's). Null above
     * [MAX_DOUBLE] steps.
     */
    fun doubled(p: PatternSnapshot): PatternSnapshot? {
        val length = p.settings.length
        if (length > MAX_DOUBLE) return null
        val w = Work(p)
        for (i in 0 until length) w.copyStep(i, i + length)
        return w.snapshot(p.settings.copy(length = 2 * length))
    }

    /** Shift + ×2: half the length, rounded up (13 → 7); the steps past it stay, hidden. Null at 1 step. */
    fun halved(p: PatternSnapshot): PatternSnapshot? {
        val length = p.settings.length
        if (length <= 1) return null
        return p.copy(settings = p.settings.copy(length = (length + 1) / 2))
    }

    /**
     * Shift + tap page [to]: the steps page [from] uses (all 16, or fewer on the pattern's last
     * page, e.g. 12 of a 12-step pattern) copied, hits and motion, to [pageCopyStart]. The pattern
     * lengthens to take them in (at most 64 steps).
     */
    fun pageCopied(p: PatternSnapshot, from: Int, to: Int): PatternSnapshot {
        val pages = SeqLimits.STEPS / PAGE
        if (from == to || from !in 0 until pages || to !in 0 until pages) return p
        val length = p.settings.length
        val used = pageUsed(length, from)
        if (used == 0) return p
        val start = pageCopyStart(length, to)
        val count = minOf(used, SeqLimits.STEPS - start)
        val w = Work(p)
        for (c in 0 until count) w.copyStep(from * PAGE + c, start + c)
        return w.snapshot(p.settings.copy(length = maxOf(length, start + count)))
    }

    /** Steps of page [page] inside a pattern of [length] steps. */
    fun pageUsed(length: Int, page: Int): Int = (length - page * PAGE).coerceIn(0, PAGE)

    /**
     * Where a page copy to page [to] lands: on that page when the pattern is whole pages long
     * (or the page is inside it); otherwise straight after the pattern's end, so a 12-step
     * pattern copied becomes 24 steps without a gap.
     */
    fun pageCopyStart(length: Int, to: Int): Int =
        if (length % PAGE == 0 || to * PAGE < length) to * PAGE else length

    /**
     * Shift + tap a pad with steps selected: the selected steps' parts (and motion) copied so
     * the earliest lands on [anchor], keeping their spacing. Steps that would land past step 64
     * are dropped; empty sources are skipped.
     */
    fun pasted(p: PatternSnapshot, source: Collection<Int>, anchor: Int, scope: EditScope): PatternSnapshot? {
        val from = source.filter { it in 0 until SeqLimits.STEPS }.sorted()
        if (from.isEmpty() || anchor !in 0 until SeqLimits.STEPS) return p
        val w = Work(p)
        val original = Work(p)
        val offset = anchor - from.first()
        for (i in from) {
            val j = i + offset
            if (j !in 0 until SeqLimits.STEPS || !scope.part(original.steps[i]).on) continue
            if (!scope.fits(w.steps[j], scope.part(original.steps[i]))) return null
            w.steps[j] = scope.merge(w.steps[j], scope.part(original.steps[i]))
            w.copyMotion(original, i, j, scope)
        }
        return w.snapshot(p.settings)
    }

    /**
     * Drag a lit pad onto another: [from]'s part (with its values and motion) goes to [to],
     * replacing what [to] had of it; [from] keeps it only with [copy]. Nothing when [from] has
     * no part to move.
     */
    fun moved(p: PatternSnapshot, from: Int, to: Int, copy: Boolean, scope: EditScope): PatternSnapshot? {
        if (from == to || from !in 0 until SeqLimits.STEPS || to !in 0 until SeqLimits.STEPS) return p
        val original = Work(p)
        val part = scope.part(original.steps[from])
        if (!part.on) return p
        if (!scope.fits(original.steps[to], part)) return null
        val w = Work(p)
        if (!copy) {
            w.steps[from] = scope.rest(w.steps[from])
            w.clearMotion(from, scope)
        }
        w.steps[to] = scope.merge(w.steps[to], part)
        w.copyMotion(original, from, to, scope)
        return w.snapshot(p.settings)
    }

    /**
     * Fill (Shift + tap pad A, then pad B): [part] (the lane's hit, or the pen's notes) on every
     * |B − A|th step from the earlier of the two to the pattern's end.
     */
    fun filled(p: PatternSnapshot, a: Int, b: Int, part: Step, scope: EditScope): PatternSnapshot? {
        val every = kotlin.math.abs(b - a)
        val start = minOf(a, b)
        if (every == 0 || start < 0 || !part.on) return p
        val w = Work(p)
        var i = start
        while (i < p.settings.length) {
            if (!scope.fits(w.steps[i], part)) return null
            w.steps[i] = scope.merge(w.steps[i], part)
            i += every
        }
        return w.snapshot(p.settings)
    }

    /** Rotate: every part inside the pattern moves [by] steps later (earlier when negative), wrapping at its length. */
    fun rotated(p: PatternSnapshot, by: Int, scope: EditScope): PatternSnapshot? {
        val length = p.settings.length
        if (length <= 1 || Math.floorMod(by, length) == 0) return p
        return shifted(p, scope) { i -> Math.floorMod(i - by, length) }
    }

    /** Insert: from [at] on, every part inside the pattern moves one step later; [at] becomes empty, the last part falls off. */
    fun inserted(p: PatternSnapshot, at: Int, scope: EditScope): PatternSnapshot? {
        if (at !in 0 until p.settings.length) return p
        return shifted(p, scope) { i -> if (i < at) i else if (i == at) -1 else i - 1 }
    }

    /** Delete: [at]'s part goes and every later part inside the pattern moves one step earlier; the last step becomes empty. */
    fun deleted(p: PatternSnapshot, at: Int, scope: EditScope): PatternSnapshot? {
        val length = p.settings.length
        if (at !in 0 until length) return p
        return shifted(p, scope) { i -> if (i < at) i else if (i + 1 < length) i + 1 else -1 }
    }

    /** Clear (build-22): every note off; Steps, Rate, Gate, Shuffle and the motion stay. */
    fun notesCleared(p: PatternSnapshot): PatternSnapshot = p.copy(steps = emptyMap())

    /** Each step inside the pattern takes the part of step `source(i)` (−1 = nothing), motion too. */
    private fun shifted(p: PatternSnapshot, scope: EditScope, source: (Int) -> Int): PatternSnapshot? {
        val original = Work(p)
        val w = Work(p)
        for (i in 0 until p.settings.length) {
            val from = source(i)
            val part = if (from < 0) Step() else scope.part(original.steps[from])
            if (!scope.fits(w.steps[i], part)) return null
            w.steps[i] = scope.merge(w.steps[i], part)
            if (from < 0) w.clearMotion(i, scope) else w.copyMotion(original, from, i, scope)
        }
        return w.snapshot(p.settings)
    }

    /** A pattern opened up for editing: 64 steps and each lane's 64 values. */
    private class Work(private val base: PatternSnapshot) {
        val steps: Array<Step> = Array(SeqLimits.STEPS) { base.steps[it] ?: Step() }
        val lanes: List<Pair<Int, Array<LanePoint?>>> = base.lanes.map { lane ->
            val values = arrayOfNulls<LanePoint>(SeqLimits.STEPS)
            for (pt in lane.points) if (pt.step in 0 until SeqLimits.STEPS) values[pt.step] = pt
            lane.target to values
        }

        /** Step [to] becomes a copy of step [from] (as it is now), with every lane's value. */
        fun copyStep(from: Int, to: Int) {
            if (from !in 0 until SeqLimits.STEPS || to !in 0 until SeqLimits.STEPS) return
            steps[to] = steps[from]
            for ((_, values) in lanes) values[to] = values[from]?.copy(step = to)
        }

        /** The scope's lanes at [to] take [source]'s values at [from] (none clears). */
        fun copyMotion(source: Work, from: Int, to: Int, scope: EditScope) {
            for ((k, lane) in lanes.withIndex()) {
                if (!scope.ownsTarget(lane.first)) continue
                lane.second[to] = source.lanes[k].second[from]?.copy(step = to)
            }
        }

        fun clearMotion(at: Int, scope: EditScope) {
            for ((target, values) in lanes) if (scope.ownsTarget(target)) values[at] = null
        }

        fun snapshot(settings: PatternSettings): PatternSnapshot {
            val out = HashMap<Int, Step>()
            for (i in 0 until SeqLimits.STEPS) if (steps[i].on) out[i] = steps[i]
            val lanesOut = lanes.map { (target, values) -> LaneSnapshot(target, values.filterNotNull()) }
            return PatternSnapshot(base.slot, base.pattern, settings, out, lanesOut)
        }
    }
}
