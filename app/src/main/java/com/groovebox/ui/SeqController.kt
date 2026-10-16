package com.groovebox.ui

import androidx.compose.runtime.Stable
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.DrumLayout
import com.groovebox.ui.model.ParamMapping
import com.groovebox.ui.model.SeqLayer
import com.groovebox.ui.model.SeqStrips
import com.groovebox.ui.model.SeqLimits
import kotlin.math.roundToInt

/**
 * The Sequencer screen's strips (F3, docs/mockups/Sequencer.dc.html), for the selected
 * slot's current pattern:
 *  - main: Shuffle, Length, Gate, Rate;
 *  - Shift, no step selected: Tempo, Pattern (queued while playing), two unused;
 *  - Shift with a step selected: Note, Velo, Prob, Motion (the step's latest motion lane).
 * Every edit goes through the pattern store. Main thread only.
 */
@Stable
class SeqController(private val app: GrooveState) : StripHost {
    private val locks = StripLocks()

    val layer: SeqLayer
        get() = SeqStrips.layer(app.shift.shifted, app.selectedStep != null)

    val strips: List<ParamSpec?>
        get() = when (layer) {
            SeqLayer.Pattern -> SeqStrips.main
            SeqLayer.Transport -> listOf(SeqStrips.Tempo, SeqStrips.Pattern, null, null)
            SeqLayer.Step -> listOf(noteSpec, SeqStrips.Velocity, SeqStrips.Probability, motionSpec())
        }

    private val noteSpec: ParamSpec
        get() = if (app.isDrum(app.selectedSlot)) SeqStrips.DrumNote else SeqStrips.Note

    /** The Motion strip for the selected step, or null when the step has no motion. */
    fun motionSpec(): ParamSpec? {
        val step = app.selectedStep ?: return null
        val lane = app.latestLane(step) ?: return null
        val target = app.targetSpec(app.selectedSlot, lane.target) ?: return null
        return SeqStrips.motionSpec(lane.target, target)
    }

    override val blinkOn: Boolean
        get() = app.blinkOn

    override fun pending(spec: ParamSpec): Boolean =
        spec.key == SeqStrips.Pattern.key && app.queuedOf(app.selectedSlot) >= 0

    override fun value(spec: ParamSpec): Double {
        val settings = app.settings()
        val stepIndex = app.selectedStep
        val step = stepIndex?.let(app::step)
        SeqStrips.motionTarget(spec)?.let { target ->
            val v = stepIndex?.let { app.store.lane(app.selectedSlot, app.editPattern, target)?.valueAt(it) }
            return if (v == null || v.isNaN()) spec.default else v.toDouble()
        }
        return when (spec.key) {
            SeqStrips.Shuffle.key -> settings.shuffle.toDouble()
            SeqStrips.Length.key -> settings.length.toDouble()
            SeqStrips.Gate.key -> settings.gate.toDouble()
            SeqStrips.Rate.key -> settings.rate.toDouble()
            SeqStrips.Tempo.key -> app.tempoBpm
            SeqStrips.Pattern.key -> app.queuedOf(app.selectedSlot).takeIf { it >= 0 }?.toDouble()
                ?: app.patternOf(app.selectedSlot).toDouble()
            SeqStrips.Note.key, SeqStrips.DrumNote.key -> (step?.notes?.firstOrNull() ?: spec.default.toInt()).toDouble()
            SeqStrips.Velocity.key -> (step?.velocity ?: SeqLimits.DEFAULT_VELOCITY).toDouble()
            SeqStrips.Probability.key -> (step?.probability ?: 1f).toDouble()
            else -> spec.default
        }.coerceIn(spec.min, spec.max)
    }

    override fun setValue(spec: ParamSpec, value: Double) {
        val v = value.coerceIn(spec.min, spec.max)
        val slot = app.selectedSlot
        val pattern = app.editPattern
        val store = app.store
        app.stripMoved()
        SeqStrips.motionTarget(spec)?.let { target ->
            app.selectedStep?.let { store.setMotionValue(slot, pattern, target, it, v.toFloat()) }
            return
        }
        when (spec.key) {
            SeqStrips.Shuffle.key -> store.updateSettings(slot, pattern) { it.copy(shuffle = v.toFloat()) }
            SeqStrips.Length.key -> store.updateSettings(slot, pattern) { it.copy(length = v.roundToInt()) }
            SeqStrips.Gate.key -> store.updateSettings(slot, pattern) { it.copy(gate = v.toFloat()) }
            SeqStrips.Rate.key -> store.updateSettings(slot, pattern) { it.copy(rate = v.roundToInt()) }
            SeqStrips.Tempo.key -> app.setTempo(v)
            SeqStrips.Pattern.key -> app.selectPattern(v.roundToInt())
            SeqStrips.Note.key, SeqStrips.DrumNote.key -> editStep { s ->
                s.copy(notes = SeqStrips.transpose(s.notes, v.roundToInt(), spec.min.toInt(), spec.max.toInt()))
            }
            SeqStrips.Velocity.key -> editStep { it.copy(velocity = v.toFloat()) }
            SeqStrips.Probability.key -> editStep { it.copy(probability = v.toFloat()) }
        }
    }

    /** Double-tap: back to the default; on the Motion strip, removes the step's value. */
    override fun reset(spec: ParamSpec) {
        val target = SeqStrips.motionTarget(spec)
        val step = app.selectedStep
        if (target != null) {
            if (step != null) app.store.setMotionValue(app.selectedSlot, app.editPattern, target, step, Float.NaN)
            return
        }
        setValue(spec, spec.default)
    }

    override fun isLocked(spec: ParamSpec): Boolean = locks.isLocked(spec.key)

    override fun setLocked(spec: ParamSpec, locked: Boolean) = locks.set(spec.key, locked)

    override fun format(spec: ParamSpec, value: Double): String =
        if (SeqStrips.motionTarget(spec) != null) {
            ParamMapping.format(spec, value)
        } else {
            SeqStrips.format(spec, value) { note -> app.drum.keyLabels[note] ?: "${note - DrumLayout.LOWEST_NOTE + 1}" }
        }

    private inline fun editStep(crossinline edit: (com.groovebox.ui.model.Step) -> com.groovebox.ui.model.Step) {
        val step = app.selectedStep ?: return
        app.store.updateStep(app.selectedSlot, app.editPattern, step) { edit(it) }
    }
}
