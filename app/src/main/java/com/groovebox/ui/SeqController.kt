package com.groovebox.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.Hit
import com.groovebox.ui.model.HitField
import com.groovebox.ui.model.RelativeDrag
import com.groovebox.ui.model.Spread
import com.groovebox.ui.model.Step
import com.groovebox.ui.model.NoteDrag
import com.groovebox.ui.model.ParamMapping
import com.groovebox.ui.model.Scale
import com.groovebox.ui.model.SeqLayer
import com.groovebox.ui.model.SeqStrips
import kotlin.math.roundToInt

/**
 * The Sequencer screen's strips (F3, docs/mockups/Sequencer.dc.html; build-20), for the
 * selected slot's current pattern:
 *  - no step selected: Shuffle, Steps (the pattern's length), Gate, Rate;
 *  - Shift, no step selected: Tempo, Pattern (queued while playing), Key, Scale (F8);
 *  - steps selected (long-press a pad, left thumb): Velocity, Hold, Chance, Nudge of the
 *    selected steps' hits (right thumb). On the drum slot the selected lane's hit on each
 *    step, on a synth slot every note of each step. Build-22: relative ([RelativeDrag]): a
 *    drag moves every selected hit by the same amount from where it was; a step without the
 *    hit is left alone (the strip stays dimmed; a key or pad tap creates the hit);
 *  - Shift with steps selected: Note (synth), —, Spread (build-22, [Spread]), Motion (the latest motion lane of the
 *    step selected last). Note is relative (build-21, [NoteDrag]): a drag moves every note of
 *    every selected step by the same interval and reads "+2" from where it started.
 * Values shown are the step selected last's. Every edit goes through the pattern store and
 * the undo history; one strip drag is one undo step ([gestureStart] .. [gestureEnd]).
 * Main thread only.
 */
@Stable
class SeqController(private val app: GrooveState) : StripHost {
    val locks = StripLocks(app::sessionChanged)

    val layer: SeqLayer
        get() = SeqStrips.layer(app.shift.shifted, app.selectedSteps.isNotEmpty())

    val strips: List<ParamSpec?>
        get() = when (layer) {
            SeqLayer.Pattern -> SeqStrips.main
            SeqLayer.Transport -> listOf(SeqStrips.Tempo, SeqStrips.Pattern, SeqStrips.Key, SeqStrips.ScaleStrip)
            SeqLayer.Step -> SeqStrips.step
            SeqLayer.StepShift -> listOf(if (app.isDrum(app.selectedSlot)) null else SeqStrips.Note, null, SeqStrips.SpreadStrip, motionSpec())
        }

    /** The Motion strip for the step selected last, or null when it has no motion. */
    fun motionSpec(): ParamSpec? {
        val step = app.focusStep ?: return null
        val lane = app.latestLane(step) ?: return null
        val target = app.targetSpec(app.selectedSlot, lane.target) ?: return null
        return SeqStrips.motionSpec(lane.target, target)
    }

    /** The hit the step strips show: the lane's (drum) or the first note's (synth) on the step selected last. */
    fun focusHit(): Hit? {
        val step = app.focusStep ?: return null
        val s = app.step(step)
        return if (app.isDrum(app.selectedSlot)) s.hit(app.laneNote) else s.hits.firstOrNull()
    }

    override val blinkOn: Boolean
        get() = app.blinkOn

    override fun pending(spec: ParamSpec): Boolean =
        spec.key == SeqStrips.Pattern.key && app.queuedOf(app.selectedSlot) >= 0

    /**
     * Dimmed strips: a step strip when the step selected last lacks the hit (build-22: moving it
     * changes only the selected steps that have the hit, and never creates one); the Note strip
     * when no selected step plays a note, and Spread when no selected step has the hit (both
     * then do nothing).
     */
    override fun dimmed(spec: ParamSpec): Boolean = when {
        spec in SeqStrips.step -> focusHit() == null
        spec.key == SeqStrips.Note.key -> app.selectedSteps.none { app.step(it).on }
        spec.key == SeqStrips.SpreadStrip.key -> selectedWithHits().isEmpty()
        else -> false
    }

    /** The Note strip's open drag (build-21). */
    private val noteDrag = NoteDrag()

    /**
     * The Note strip's value: the move the open drag applied (clamped by the range, so dragging
     * on past the clamp shows the same value), 0 between drags. Observable, so the strip follows.
     */
    private var noteMove by mutableIntStateOf(0)

    /** A finger is on the Note strip; its drag may have been ended under it ([endNoteDrag]). */
    private var noteGesture = false

    private fun startNoteDrag() {
        noteDrag.start(app.selectedSlot, app.editPattern, app.selectedSteps.associateWith { app.step(it) })
        noteMove = 0
    }

    /**
     * Ends the open Note drag, keeping what it wrote. The app calls it when the drag's steps
     * change under it (keys or live recording into them, undo / redo, deselection), so the
     * drag never writes back notes captured before; the rest of that strip gesture does nothing.
     */
    fun endNoteDrag() {
        noteDrag.end()
        noteMove = 0
    }

    /** The open Note drag holds [step] of [slot]'s [pattern]. */
    fun noteDragHolds(slot: Int, pattern: Int, step: Int): Boolean = noteDrag.holds(slot, pattern, step)

    /**
     * Writes the open Note drag moved by [move] (one undo step with the rest of the drag), into
     * the slot and pattern it started on only: shown another one, it ends instead.
     */
    private fun applyNoteDrag(move: Int) {
        val slot = app.selectedSlot
        val pattern = app.editPattern
        val moved = noteDrag.moveTo(move, app.keyScale, slot, pattern)
        noteMove = noteDrag.offset
        for ((step, s) in moved) app.history.edit(slot, pattern) { app.store.updateStep(slot, pattern, step) { s } }
    }

    // --- build-22: relative step strips and Spread ---

    private val hitDrag = RelativeDrag()
    private var hitField: HitField? = null

    /** The value shown on a step strip while its drag runs over a focus step without the hit. */
    private var hitShown by mutableStateOf<Double?>(null)

    /** Spread's open drag: the steps as they were, its seed, and the amount shown. */
    private var spreadFrom: Map<Int, Step>? = null
    private var spreadSlot = -1
    private var spreadPattern = -1
    private var spreadSeed = 1L
    private var spreadShown by mutableDoubleStateOf(0.0)

    /** The selected steps that hold the hit the strips edit (the lane's, or any note). */
    private fun selectedWithHits(): Map<Int, Step> {
        val lane = if (app.isDrum(app.selectedSlot)) app.laneNote else null
        return app.selectedSteps.associateWith { app.step(it) }
            .filterValues { if (lane == null) it.on else it.hit(lane) != null }
    }

    private fun fieldOf(spec: ParamSpec): HitField? = when (spec.key) {
        SeqStrips.HitVelocity.key -> HitField.Velocity
        SeqStrips.HitLength.key -> HitField.Hold
        SeqStrips.HitChance.key -> HitField.Chance
        SeqStrips.HitNudge.key -> HitField.Nudge
        else -> null
    }

    private fun startHitDrag(spec: ParamSpec, field: HitField) {
        val lane = if (app.isDrum(app.selectedSlot)) app.laneNote else null
        hitDrag.start(app.selectedSlot, app.editPattern, selectedWithHits(), lane, value(spec))
        hitField = field
        hitShown = null
    }

    private fun startSpread() {
        spreadFrom = selectedWithHits()
        spreadSlot = app.selectedSlot
        spreadPattern = app.editPattern
        spreadSeed = spreadSeed * 6364136223846793005L + 1442695040888963407L
        spreadShown = 0.0
    }

    /**
     * Ends the open step-strip and Spread drags, keeping what they wrote (keys, recording,
     * undo or a new selection changed the steps under them); the rest of such a gesture does nothing.
     */
    fun endHitDrags() {
        hitDrag.end()
        hitField = null
        hitShown = null
        spreadFrom = null
        spreadShown = 0.0
    }

    fun hitDragHolds(slot: Int, pattern: Int, step: Int): Boolean =
        hitDrag.holds(slot, pattern, step) || (spreadFrom?.containsKey(step) == true && slot == spreadSlot && pattern == spreadPattern)

    private fun applyHitDrag(field: HitField, v: Double) {
        val moved = hitDrag.moveTo(v, field, app.selectedSlot, app.editPattern)
        if (focusHit() == null) hitShown = v
        app.writeSteps(moved)
    }

    private fun applySpread(v: Double) {
        val from = spreadFrom ?: return
        if (app.selectedSlot != spreadSlot || app.editPattern != spreadPattern) {
            endHitDrags()
            return
        }
        val lane = if (app.isDrum(spreadSlot)) app.laneNote else null
        spreadShown = v
        app.writeSteps(Spread.apply(from, lane, v.toFloat(), spreadSeed))
    }

    /** Any step-strip gesture open (so a strip gesture's later values know it was ended under it). */
    private val hitGestures = HashSet<String>()

    /** Open strip drags: spec key → their undo group's token. */
    private val drags = HashMap<String, Int>()

    override fun gestureStart(spec: ParamSpec) {
        drags.remove(spec.key)?.let(app.history::end)
        drags[spec.key] = app.history.begin()
        if (spec.key == SeqStrips.Note.key) {
            noteGesture = true
            startNoteDrag()
        }
        fieldOf(spec)?.let { startHitDrag(spec, it); hitGestures += spec.key }
        if (spec.key == SeqStrips.SpreadStrip.key) {
            startSpread()
            hitGestures += spec.key
        }
    }

    override fun gestureEnd(spec: ParamSpec) {
        drags.remove(spec.key)?.let(app.history::end)
        if (spec.key == SeqStrips.Note.key) {
            noteGesture = false
            endNoteDrag()
        }
        if (hitGestures.remove(spec.key)) endHitDrags()
    }

    override fun value(spec: ParamSpec): Double {
        val settings = app.settings()
        val focus = app.focusStep
        SeqStrips.motionTarget(spec)?.let { target ->
            val v = focus?.let { app.store.lane(app.selectedSlot, app.editPattern, target)?.valueAt(it) }
            return if (v == null || v.isNaN()) spec.default else v.toDouble()
        }
        val hit = focusHit()
        return when (spec.key) {
            SeqStrips.Shuffle.key -> settings.shuffle.toDouble()
            SeqStrips.Length.key -> settings.length.toDouble()
            SeqStrips.Gate.key -> settings.gate.toDouble()
            SeqStrips.Rate.key -> settings.rate.toDouble()
            SeqStrips.Tempo.key -> app.tempoBpm
            SeqStrips.Pattern.key -> app.queuedOf(app.selectedSlot).takeIf { it >= 0 }?.toDouble()
                ?: app.patternOf(app.selectedSlot).toDouble()
            SeqStrips.Key.key -> app.keyScale.root.toDouble()
            SeqStrips.ScaleStrip.key -> app.keyScale.scale.ordinal.toDouble()
            SeqStrips.Note.key -> noteMove.toDouble()
            SeqStrips.SpreadStrip.key -> spreadShown
            SeqStrips.HitVelocity.key -> hit?.velocity?.toDouble() ?: hitShown.takeIf { hitField == HitField.Velocity } ?: spec.default
            SeqStrips.HitLength.key -> hit?.let { SeqStrips.lengthIndex(it.length).toDouble() } ?: hitShown.takeIf { hitField == HitField.Hold } ?: spec.default
            SeqStrips.HitChance.key -> hit?.probability?.toDouble() ?: hitShown.takeIf { hitField == HitField.Chance } ?: spec.default
            SeqStrips.HitNudge.key -> hit?.nudge?.toDouble() ?: hitShown.takeIf { hitField == HitField.Nudge } ?: spec.default
            else -> spec.default
        }.coerceIn(spec.min, spec.max)
    }

    override fun setValue(spec: ParamSpec, value: Double) {
        val v = value.coerceIn(spec.min, spec.max)
        val slot = app.selectedSlot
        val pattern = app.editPattern
        val store = app.store
        val history = app.history
        app.stripMoved()
        SeqStrips.motionTarget(spec)?.let { target ->
            for (step in app.selectedSteps) history.edit(slot, pattern) { store.setMotionValue(slot, pattern, target, step, v.toFloat()) }
            return
        }
        when (spec.key) {
            SeqStrips.Shuffle.key -> history.edit(slot, pattern) { store.updateSettings(slot, pattern) { it.copy(shuffle = v.toFloat()) } }
            SeqStrips.Length.key -> history.edit(slot, pattern) { store.updateSettings(slot, pattern) { it.copy(length = v.roundToInt()) } }
            SeqStrips.Gate.key -> history.edit(slot, pattern) { store.updateSettings(slot, pattern) { it.copy(gate = v.toFloat()) } }
            SeqStrips.Rate.key -> history.edit(slot, pattern) { store.updateSettings(slot, pattern) { it.copy(rate = v.roundToInt()) } }
            SeqStrips.Tempo.key -> app.setTempo(v)
            SeqStrips.Pattern.key -> app.selectPattern(v.roundToInt())
            SeqStrips.Key.key -> app.setKey(v.roundToInt())
            SeqStrips.ScaleStrip.key -> app.setScale(Scale.entries[v.roundToInt().coerceIn(0, Scale.entries.lastIndex)])
            // Build-21: relative to where the drag started, in scale degrees with a scale on.
            SeqStrips.Note.key -> when {
                noteDrag.active -> applyNoteDrag(v.roundToInt())
                noteGesture -> Unit // the drag was ended under the finger: the rest of the gesture does nothing
                else -> {
                    // Not from a strip gesture (accessibility): a one-off move, one undo step.
                    val token = history.begin()
                    startNoteDrag()
                    applyNoteDrag(v.roundToInt())
                    endNoteDrag()
                    history.end(token)
                }
            }
            SeqStrips.HitVelocity.key, SeqStrips.HitLength.key, SeqStrips.HitChance.key, SeqStrips.HitNudge.key -> {
                val field = fieldOf(spec)!!
                when {
                    hitDrag.active && hitField == field -> applyHitDrag(field, v)
                    spec.key in hitGestures -> Unit // ended under the finger
                    else -> {
                        // Not from a strip gesture (accessibility): a one-off relative move, one undo step.
                        val token = history.begin()
                        startHitDrag(spec, field)
                        applyHitDrag(field, v)
                        endHitDrags()
                        history.end(token)
                    }
                }
            }
            SeqStrips.SpreadStrip.key -> when {
                spreadFrom != null -> applySpread(v)
                spec.key in hitGestures -> Unit
                else -> {
                    val token = history.begin()
                    startSpread()
                    applySpread(v)
                    endHitDrags()
                    history.end(token)
                }
            }
        }
    }

    /**
     * Double-tap: back to the default; on the Motion strip, removes the selected steps' values;
     * on the Note strip, the drag back to 0 (no change: a double-tap moves no note).
     */
    override fun reset(spec: ParamSpec) {
        if (spec.key == SeqStrips.Note.key) {
            if (noteDrag.active) applyNoteDrag(0)
            return
        }
        if (spec.key == SeqStrips.SpreadStrip.key) {
            if (spreadFrom != null) applySpread(0.0)
            return
        }
        fieldOf(spec)?.let { field ->
            // Double-tap: every selected hit back to the default (absolute), one undo step.
            endHitDrags()
            val slot = app.selectedSlot
            val pattern = app.editPattern
            val lane = if (app.isDrum(slot)) app.laneNote else null
            val d = spec.default
            val token = app.history.begin()
            for (step in app.selectedSteps) {
                app.history.edit(slot, pattern) {
                    app.store.editHits(slot, pattern, step, lane) { h ->
                        when (field) {
                            HitField.Velocity -> h.copy(velocity = d.toFloat())
                            HitField.Hold -> h.copy(length = SeqStrips.lengthAt(d))
                            HitField.Chance -> h.copy(probability = d.toFloat())
                            HitField.Nudge -> h.copy(nudge = d.toFloat())
                        }
                    }
                }
            }
            app.history.end(token)
            return
        }
        val target = SeqStrips.motionTarget(spec)
        if (target != null) {
            val slot = app.selectedSlot
            val pattern = app.editPattern
            for (step in app.selectedSteps) {
                app.history.edit(slot, pattern) { app.store.setMotionValue(slot, pattern, target, step, Float.NaN) }
            }
            return
        }
        setValue(spec, spec.default)
    }

    override fun isLocked(spec: ParamSpec): Boolean = locks.isLocked(spec.key)

    override fun setLocked(spec: ParamSpec, locked: Boolean) = locks.set(spec.key, locked)

    override fun format(spec: ParamSpec, value: Double): String = when {
        SeqStrips.motionTarget(spec) != null -> ParamMapping.format(spec, value)
        // With a scale on the move is in scale degrees: "+2°".
        spec.key == SeqStrips.Note.key -> SeqStrips.formatMove(value.roundToInt(), degrees = app.keyScale.isOn)
        else -> SeqStrips.format(spec, value)
    }
}
