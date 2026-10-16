package com.groovebox.ui.model

import com.groovebox.engine.Curve
import com.groovebox.engine.ParamSpec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Maps a parameter between its real value and the strip's normalised 0..1 position, per the
 * parameter's [Curve], and formats values for the strips and the display. Pure Kotlin.
 */
object ParamMapping {
    private const val MINUS = '−'
    private const val MORPH = '›'

    /** Within this distance of a whole shape index, a morph parameter shows the shape's name. */
    private const val MORPH_SNAP = 0.08

    fun fromNormalized(spec: ParamSpec, normalized: Double): Double {
        val n = if (normalized.isNaN()) 0.0 else normalized.coerceIn(0.0, 1.0)
        val span = spec.max - spec.min
        val v = when (spec.curve) {
            Curve.Linear -> spec.min + span * n
            Curve.Exp -> spec.min * (spec.max / spec.min).pow(n)
            Curve.Pow3 -> spec.min + span * n * n * n
            Curve.Int, Curve.Enum -> (spec.min + span * n).roundToInt().toDouble()
        }
        // Guard the ends against floating-point drift (e.g. Exp at n = 1).
        return v.coerceIn(spec.min, spec.max)
    }

    fun toNormalized(spec: ParamSpec, value: Double): Double {
        if (value.isNaN() || spec.max <= spec.min) return 0.0
        val v = value.coerceIn(spec.min, spec.max)
        val n = when (spec.curve) {
            Curve.Linear, Curve.Int, Curve.Enum -> (v - spec.min) / (spec.max - spec.min)
            Curve.Exp -> ln(v / spec.min) / ln(spec.max / spec.min)
            Curve.Pow3 -> cbrt((v - spec.min) / (spec.max - spec.min))
        }
        return n.coerceIn(0.0, 1.0)
    }

    /** Short text for a strip or the display, e.g. "2.4k", "120 ms", "+40%", "Saw›Sq". */
    fun format(spec: ParamSpec, value: Double): String {
        if (spec.curve == Curve.Enum) return enumLabel(spec, value)
        if ('|' in spec.unit) return morphLabel(spec.unit.split('|'), value - spec.min)
        return when (spec.unit) {
            "Hz" -> hertz(value)
            "s" -> seconds(value)
            "%" -> percent(value, bipolar = spec.min < 0.0)
            "c" -> signed((value).roundToInt()) + "c"
            "dB" -> "${value.roundToInt()} dB"
            "oct" -> signed(value.roundToInt())
            "st" -> signed(value.roundToInt()) + " st"
            "" -> fixed(value, 2)
            else -> fixed(value, 2) + " " + spec.unit
        }
    }

    private fun hertz(v: Double): String {
        if (v >= 999.5) {
            val k = v / 1000.0
            return if (k >= 9.95) "${k.roundToInt()}k" else fixed(k, 1) + "k"
        }
        return when {
            v >= 9.95 -> "${v.roundToInt()} Hz"
            v >= 0.995 -> fixed(v, 1) + " Hz"
            else -> fixed(v, 2) + " Hz"
        }
    }

    private fun seconds(v: Double): String {
        val ms = v * 1000.0
        if (ms < 999.5) return "${ms.roundToInt()} ms"
        return if (v >= 9.95) "${v.roundToInt()} s" else fixed(v, 1) + " s"
    }

    private fun percent(v: Double, bipolar: Boolean): String {
        val pct = (v * 100.0).roundToInt()
        return (if (bipolar) signed(pct) else pct.toString()) + "%"
    }

    /** "+12", "−12" (true minus sign), "0". */
    private fun signed(n: Int): String = when {
        n > 0 -> "+$n"
        n < 0 -> "$MINUS${-n}"
        else -> "0"
    }

    private fun fixed(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)

    private fun enumLabel(spec: ParamSpec, value: Double): String {
        val options = spec.options
        if (options.isEmpty()) return fixed(value, 0)
        val i = (value - spec.min).roundToInt().coerceIn(0, options.lastIndex)
        return titleCase(options[i])
    }

    /** A continuous parameter that morphs between named shapes, e.g. osc.wave. */
    private fun morphLabel(names: List<String>, position: Double): String {
        val p = position.coerceIn(0.0, names.lastIndex.toDouble())
        val nearest = p.roundToInt()
        if (abs(p - nearest) <= MORPH_SNAP) return titleCase(names[nearest])
        val lo = floor(p).toInt()
        return shortName(names[lo]) + MORPH + shortName(names[lo + 1])
    }

    private val ACRONYMS = setOf("pw", "lfo", "hpf")

    /** "poly" → "Poly", "s&h" → "S&H", "pw" → "PW". */
    private fun titleCase(label: String): String {
        if (label.lowercase(Locale.ROOT) in ACRONYMS) return label.uppercase(Locale.ROOT)
        val out = StringBuilder(label.length)
        var startOfWord = true
        for (c in label) {
            out.append(if (startOfWord) c.uppercaseChar() else c)
            startOfWord = !c.isLetterOrDigit()
        }
        return out.toString()
    }

    /** Names of up to four letters stay whole; longer ones shorten to two ("square" → "Sq"). */
    private fun shortName(label: String): String {
        val t = titleCase(label)
        return if (t.length <= 4) t else t.take(2)
    }
}
