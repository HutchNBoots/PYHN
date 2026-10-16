package com.groovebox.ui.model

import com.groovebox.engine.Curve
import com.groovebox.engine.ParamSpec
import java.util.Locale
import kotlin.math.roundToInt

/** Which of the Sequencer screen's three strip layers shows (F3, docs/mockups/Sequencer.dc.html). */
enum class SeqLayer { Pattern, Transport, Step }

/**
 * The Sequencer screen's strips as [ParamSpec]s, so the colour strips drive them like any
 * parameter. Values are in the units the pattern store uses (fractions 0..1, step counts,
 * rate index, pattern index 0..15, MIDI notes). Pure Kotlin.
 */
object SeqStrips {
    val RATE_NAMES = listOf("1/8", "1/16", "1/32", "1/16T") // NativeEngine.STEP_RATES

    val Shuffle = ParamSpec(0, "seq.shuffle", "Shuffle", 0.0, 1.0, 0.0, Curve.Linear, "%")
    val Length = ParamSpec(1, "seq.length", "Length", 1.0, SeqLimits.STEPS.toDouble(), 16.0, Curve.Int, "steps")
    val Gate = ParamSpec(2, "seq.gate", "Gate", SeqLimits.MIN_GATE.toDouble(), 1.0, 0.5, Curve.Linear, "%")
    val Rate = ParamSpec(
        3, "seq.rate", "Rate", 0.0, (SeqLimits.RATES - 1).toDouble(), SeqLimits.DEFAULT_RATE.toDouble(),
        Curve.Enum, RATE_NAMES.joinToString("|"),
    )
    val Tempo = ParamSpec(4, "seq.tempo", "Tempo", SeqLimits.MIN_TEMPO, SeqLimits.MAX_TEMPO, SeqLimits.DEFAULT_TEMPO, Curve.Linear, "bpm")
    val Pattern = ParamSpec(5, "seq.pattern", "Pattern", 0.0, (SeqLimits.PATTERNS - 1).toDouble(), 0.0, Curve.Int, "pattern")

    /** Synth step note: C1..C7. Moving it transposes the whole chord. */
    val Note = ParamSpec(6, "seq.note", "Note", 24.0, 96.0, 60.0, Curve.Int, "note")

    /** Drum step "note": one of the 16 kit sounds. */
    val DrumNote = ParamSpec(7, "seq.drumNote", "Note", 48.0, 63.0, 48.0, Curve.Int, "drum")
    val Velocity = ParamSpec(8, "seq.velo", "Velo", 0.0, 1.0, SeqLimits.DEFAULT_VELOCITY.toDouble(), Curve.Linear, "vel")
    val Probability = ParamSpec(9, "seq.prob", "Prob", 0.0, 1.0, 1.0, Curve.Linear, "%")

    /** Key prefix of the Motion strip's spec; the rest of the key is the lane's target. */
    const val MOTION_PREFIX = "seq.motion:"

    val main = listOf(Shuffle, Length, Gate, Rate)

    fun layer(shifted: Boolean, stepSelected: Boolean): SeqLayer = when {
        !shifted -> SeqLayer.Pattern
        stepSelected -> SeqLayer.Step
        else -> SeqLayer.Transport
    }

    /**
     * The Motion strip for a lane: the target parameter's range, curve and units under the
     * label "Motion", with a key that names the target.
     */
    fun motionSpec(target: Int, targetSpec: ParamSpec): ParamSpec =
        targetSpec.copy(id = 100 + target, key = MOTION_PREFIX + target, label = "Motion")

    fun motionTarget(spec: ParamSpec): Int? =
        if (spec.key.startsWith(MOTION_PREFIX)) spec.key.removePrefix(MOTION_PREFIX).toIntOrNull() else null

    /** Strip text for a sequencer spec. [drumName] names a drum note (MIDI 48..63). */
    fun format(spec: ParamSpec, value: Double, drumName: (Int) -> String = { "D${it - 47}" }): String = when (spec.key) {
        Length.key -> value.roundToInt().toString()
        Rate.key -> RATE_NAMES.getOrElse(value.roundToInt()) { "?" }
        Tempo.key -> String.format(Locale.ROOT, "%.1f", value)
        Pattern.key -> (value.roundToInt() + 1).toString()
        Note.key -> NoteNames.name(value.roundToInt())
        DrumNote.key -> drumName(value.roundToInt())
        Velocity.key -> velocity127(value.toFloat()).toString()
        else -> ParamMapping.format(spec, value)
    }

    fun velocity127(v: Float): Int = (v.coerceIn(0f, 1f) * 127f).roundToInt()

    /** The chord moved so its first note lands on [root], kept inside [low]..[high] as a whole. */
    fun transpose(notes: List<Int>, root: Int, low: Int, high: Int): List<Int> {
        if (notes.isEmpty()) return listOf(root.coerceIn(low, high))
        var delta = root - notes.first()
        val lo = notes.min() + delta
        val hi = notes.max() + delta
        if (lo < low) delta += low - lo
        if (hi > high) delta -= hi - high
        return notes.map { (it + delta).coerceIn(low, high) }
    }
}

/** "C4" = MIDI 60, the keyboard's convention (C3 = 48). */
object NoteNames {
    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun name(note: Int): String = NAMES[Math.floorMod(note, 12)] + (Math.floorDiv(note, 12) - 1)
}
