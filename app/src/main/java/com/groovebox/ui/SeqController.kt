package com.groovebox.ui

import androidx.compose.runtime.Stable
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.Hit
import com.groovebox.ui.model.ParamMapping
import com.groovebox.ui.model.Scale
import com.groovebox.ui.model.SeqLayer
import com.groovebox.ui.model.SeqStrips
import com.groovebox.ui.model.Step
import kotlin.math.roundToInt

/**
 * The Sequencer screen's strips (F3, docs/mockups/Sequencer.dc.html; build-20), for the
 * selected slot's current pattern:
 *  - no step selected: Shuffle, Length, Gate, Rate;
 *  - Shift, no step selected: Tempo, Pattern (queued while playing), Key, Scale (F8);
 *  - steps selected (long-press a pad, left thumb): Velocity, Length, Chance, Nudge of the
 *    selected steps' hits (right thumb). On the drum slot the selected lane's hit on each
 *    step, created when a step lacks it; on a synth slot every note of each step;
 *  - Shift with steps selected: Note (synth), —, —, Motion (the latest motion lane of the
 *    step selected last).
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
            SeqLayer.StepShift -> listOf(if (app.isDrum(app.selectedSlot)) null else SeqStrips.Note, null, null, motionSpec())
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

    /** A step strip over a step without that hit: dimmed; moving it creates the hit. */
    override fun dimmed(spec: ParamSpec): Boolean = spec in SeqStrips.step && focusHit() == null

    override fun gestureStart(spec: ParamSpec) = app.history.begin()

    override fun gestureEnd(spec: ParamSpec) = app.history.end()

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
            SeqStrips.Note.key -> (focus?.let { app.step(it).notes.firstOrNull() } ?: spec.default.toInt()).toDouble()
            SeqStrips.HitVelocity.key -> hit?.velocity?.toDouble() ?: spec.default
            SeqStrips.HitLength.key -> hit?.let { SeqStrips.lengthIndex(it.length).toDouble() } ?: spec.default
            SeqStrips.HitChance.key -> hit?.probability?.toDouble() ?: spec.default
            SeqStrips.HitNudge.key -> hit?.nudge?.toDouble() ?: spec.default
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
            // F8.4: on a synth slot the chord moves through in-key notes only (chromatic with the scale off).
            SeqStrips.Note.key -> for (step in app.selectedSteps) {
                history.edit(slot, pattern) {
                    store.updateStep(slot, pattern, step) { s ->
                        if (!s.on) {
                            s
                        } else {
                            val notes = app.keyScale.transpose(s.notes, v.roundToInt(), spec.min.toInt(), spec.max.toInt())
                            Step(s.hits.zip(notes) { h, n -> h.copy(note = n) })
                        }
                    }
                }
            }
            SeqStrips.HitVelocity.key -> app.editSelectedHits { it.copy(velocity = v.toFloat()) }
            SeqStrips.HitLength.key -> app.editSelectedHits { it.copy(length = SeqStrips.lengthAt(v)) }
            SeqStrips.HitChance.key -> app.editSelectedHits { it.copy(probability = v.toFloat()) }
            SeqStrips.HitNudge.key -> app.editSelectedHits { it.copy(nudge = v.toFloat()) }
        }
    }

    /** Double-tap: back to the default; on the Motion strip, removes the selected steps' values. */
    override fun reset(spec: ParamSpec) {
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

    override fun format(spec: ParamSpec, value: Double): String =
        if (SeqStrips.motionTarget(spec) != null) ParamMapping.format(spec, value) else SeqStrips.format(spec, value)
}
