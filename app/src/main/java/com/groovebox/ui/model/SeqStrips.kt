package com.groovebox.ui.model

import com.groovebox.engine.Curve
import com.groovebox.engine.ParamSpec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Which of the Sequencer screen's strip layers shows (F3, docs/mockups/Sequencer.dc.html;
 * build-20): the pattern's, Shift's tempo / key, the selected steps' (no Shift) and the
 * selected steps' Shift layer (Note, Motion).
 */
enum class SeqLayer { Pattern, Transport, Step, StepShift }

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

    /** The session's key (F8): its root, 0..11 = C..B. Shift layer, no step selected. */
    val Key = ParamSpec(
        10, "seq.key", "Key", 0.0, 11.0, 0.0, Curve.Enum, KeyScale.KEY_NAMES.joinToString("|"),
    )

    /** The session's scale (F8), [Scale] by ordinal; 0 = Off. */
    val ScaleStrip = ParamSpec(
        11, "seq.scale", "Scale", 0.0, (Scale.entries.size - 1).toDouble(), 0.0, Curve.Enum,
        Scale.entries.joinToString("|") { it.label },
    )

    /** Key prefix of the Motion strip's spec; the rest of the key is the lane's target. */
    const val MOTION_PREFIX = "seq.motion:"

    /**
     * The selected steps' Length strip positions (build-20), in steps: the pattern's gate
     * (leftmost), then 1/16 of a step up to 16 steps.
     */
    val LENGTHS: List<Float> = listOf(
        SeqLimits.PATTERN_GATE, 1f / 16, 1f / 8, 3f / 16, 1f / 4, 3f / 8, 1f / 2, 3f / 4,
        1f, 1.5f, 2f, 2.5f, 3f, 4f, 6f, 8f, 12f, 16f,
    )

    // The selected steps' strips (build-20), without Shift: the hit's own values.
    val HitVelocity = ParamSpec(12, "seq.hit.velo", "Velocity", SeqLimits.MIN_VELOCITY.toDouble(), 1.0, SeqLimits.DEFAULT_VELOCITY.toDouble(), Curve.Linear, "%")
    val HitLength = ParamSpec(
        13, "seq.hit.length", "Length", 0.0, (LENGTHS.size - 1).toDouble(), 0.0, Curve.Enum,
        LENGTHS.joinToString("|") { formatLength(it) },
    )
    val HitChance = ParamSpec(14, "seq.hit.chance", "Chance", 0.0, 1.0, 1.0, Curve.Linear, "%")
    val HitNudge = ParamSpec(15, "seq.hit.nudge", "Nudge", -SeqLimits.MAX_NUDGE.toDouble(), SeqLimits.MAX_NUDGE.toDouble(), 0.0, Curve.Linear, "%")

    val main = listOf(Shuffle, Length, Gate, Rate)
    val step = listOf(HitVelocity, HitLength, HitChance, HitNudge)

    fun layer(shifted: Boolean, stepSelected: Boolean): SeqLayer = when {
        stepSelected -> if (shifted) SeqLayer.StepShift else SeqLayer.Step
        shifted -> SeqLayer.Transport
        else -> SeqLayer.Pattern
    }

    /** The Length strip position showing [length]: the nearest one (a v2 gate may lie between). */
    fun lengthIndex(length: Float): Int {
        if (length.isNaN() || length < 0f) return 0
        var best = 1
        for (i in 1 until LENGTHS.size) if (abs(LENGTHS[i] - length) < abs(LENGTHS[best] - length)) best = i
        return best
    }

    /** The length at Length strip position [index]. */
    fun lengthAt(index: Double): Float = LENGTHS[index.roundToInt().coerceIn(0, LENGTHS.lastIndex)]

    /** "gate", "1/16", "3/4", "1", "2½", "16"; a length between those as a decimal ("0.3"). */
    fun formatLength(length: Float): String {
        if (length.isNaN() || length < 0f) return "gate"
        val sixteenths = length * 16
        if (abs(sixteenths - sixteenths.roundToInt()) < 1e-3f) {
            val n = sixteenths.roundToInt()
            if (n in 1 until 16) {
                var num = n
                var den = 16
                while (num % 2 == 0) { num /= 2; den /= 2 }
                return "$num/$den"
            }
        }
        val halves = length * 2
        if (length >= 1f && abs(halves - halves.roundToInt()) < 1e-3f) {
            val h = halves.roundToInt()
            return if (h % 2 == 0) "${h / 2}" else "${h / 2}½"
        }
        return String.format(Locale.ROOT, "%.2f", length).trimEnd('0').trimEnd('.')
    }

    /** "−12%", "0", "+25%" (of a step). */
    fun formatNudge(nudge: Float): String {
        val pct = (nudge * 100).roundToInt()
        return when {
            pct == 0 -> "0"
            pct > 0 -> "+$pct%"
            else -> "\u2212${-pct}%"
        }
    }

    fun percent(v: Double): String = "${(v * 100).roundToInt()}%"

    /**
     * The Motion strip for a lane: the target parameter's range, curve and units under the
     * label "Motion", with a key that names the target.
     */
    fun motionSpec(target: Int, targetSpec: ParamSpec): ParamSpec =
        targetSpec.copy(id = 100 + target, key = MOTION_PREFIX + target, label = "Motion")

    fun motionTarget(spec: ParamSpec): Int? =
        if (spec.key.startsWith(MOTION_PREFIX)) spec.key.removePrefix(MOTION_PREFIX).toIntOrNull() else null

    /** Strip text for a sequencer spec. */
    fun format(spec: ParamSpec, value: Double): String = when (spec.key) {
        Length.key -> value.roundToInt().toString()
        Rate.key -> RATE_NAMES.getOrElse(value.roundToInt()) { "?" }
        Tempo.key -> String.format(Locale.ROOT, "%.1f", value)
        Pattern.key -> (value.roundToInt() + 1).toString()
        Note.key -> NoteNames.name(value.roundToInt())
        HitVelocity.key, HitChance.key -> percent(value)
        HitLength.key -> formatLength(lengthAt(value))
        HitNudge.key -> formatNudge(value.toFloat())
        Key.key -> KeyScale.KEY_NAMES[value.roundToInt().coerceIn(0, 11)]
        ScaleStrip.key -> Scale.entries[value.roundToInt().coerceIn(0, Scale.entries.lastIndex)].label
        else -> ParamMapping.format(spec, value)
    }

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
